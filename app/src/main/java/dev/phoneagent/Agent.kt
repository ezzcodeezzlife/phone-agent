package dev.phoneagent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** How the screen is shown to the model. */
enum class Mode(val key: String, val label: String) {
    /** Tree with element ids; a marked screenshot is added only when needed (look, trouble, sparse tree). */
    AUTO("auto", "Auto (UI tree, screenshot when needed)"),
    TREE("tree", "UI tree only (element IDs)"),
    SCREENSHOT("screenshot", "Screenshot only (x,y)"),
    HYBRID("hybrid", "Always both (screenshot + IDs)");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO }
}

/** Request shape fallbacks, learned at runtime if the plan route rejects a field. */
object Compat {
    @Volatile var namespace = true
    @Volatile var toolChoice = true
    @Volatile var parallel = true
    @Volatile var reasoning = true
}

/**
 * The agent loop: observe the screen → ask the model for the next action(s) → execute → repeat.
 * Every step is a fresh, self-contained request (task + user messages + action log + current screen), so no
 * server-side conversation state is needed (the plan route requires store=false anyway).
 *
 * The user can steer while it runs: messages (typed or spoken) go into the next request, pause holds the loop,
 * and touching the screen holds it until the user stops. Any interruption drops actions that were planned for the
 * old screen, so the model always decides on what is on screen now.
 */
class Agent(
    private val svc: AgentService,
    val task: String,
    private val mode: Mode,
    val model: String,
    private val effort: String,
    private val askFirst: Boolean,
    /** Earlier task and result when this run is a follow-up. */
    private val context: String?,
    private val goHome: Boolean,
) {
    companion object {
        const val MAX_STEPS = 40
        const val MAX_SCRIPT_STEPS = 12
        /** With "ask first", these buttons may only be pressed as a single tap after confirm, never inside a script. */
        private val FINAL_BUTTON = Regex("^(send|senden|absenden|post|posten|publish|veröffentlichen|pay|bezahlen|zahlen|buy|kaufen|order|bestellen|place order|delete|löschen|transfer|überweisen)( .*)?$")
    }

    @Volatile var stopped = false
    @Volatile var paused = false
        private set
    @Volatile var result: String? = null
        private set
    @Volatile var success = false
        private set
    @Volatile private var step = 0
    @Volatile private var pending: FeedItem? = null

    private class UserMsg(val text: String, val spoken: Boolean, val step: Int)
    private val messages = ArrayList<UserMsg>()
    private val answers = LinkedBlockingQueue<String>()
    private val history = ArrayList<String>()
    private val recentKeys = ArrayList<String>()
    private val events = JSONArray()
    private val apps = LinkedHashSet<String>()
    private var lookRequested = false
    private var shotsSent = 0

    // ---------------------------------------------------------------- steering (main thread)

    /** A message from the user while the run is going: refines the task, or answers a pending question. */
    fun say(text: String, spoken: Boolean) {
        svc.ui { add(FeedItem(FeedItem.Kind.USER, text, spoken = spoken)) }
        val p = pending
        if (p != null) { answer(p, text); return }
        synchronized(messages) { messages += UserMsg(text, spoken, step) }
        event(step, "user", text, if (spoken) "spoken" else "typed", true)
        AgentBus.log("🗣 User: $text")
        if (paused) togglePause()
        svc.interrupt("the user sent a message")
    }

    fun answer(item: FeedItem, a: String) {
        if (pending !== item || item.answer != null) return
        item.answer = a
        svc.ui { update(item) }
        answers.offer(a)
    }

    fun togglePause() {
        if (pending != null) return
        paused = !paused
        svc.interrupt(if (paused) "the user paused the run" else "the user resumed the run")
        svc.ui {
            add(FeedItem(FeedItem.Kind.NOTE, if (paused) "Paused" else "Continuing"))
            if (paused) setPhase(Phase.PAUSED, "Paused · tap ▶ to continue") else setPhase(Phase.WORKING, "Continuing…")
        }
    }

    private fun holdReason(): String? = when {
        paused -> "Paused · tap ▶ to continue"
        svc.appOpen -> "Waiting while the app is open"
        svc.listening -> "Listening…"
        svc.typing -> "Waiting while you type…"
        Prefs.touchWait && svc.userTouching() -> "Your turn · I'll continue when you stop"
        else -> null
    }

    /** Blocks while the user is in control (paused, speaking, typing, touching the screen). */
    private fun waitWhileHeld() {
        var shown: String? = null
        while (!stopped) {
            val r = holdReason() ?: break
            if (r != shown) {
                shown = r
                if (!paused && !svc.listening) svc.ui { setPhase(Phase.HELD, r) }
            }
            Thread.sleep(120)
        }
        if (shown != null && !stopped) svc.ui { setPhase(Phase.WORKING, "Continuing…") }
    }

    /** Shows a question or confirmation and waits for the answer (null when stopped). */
    private fun ask(item: FeedItem): String? {
        answers.clear()
        pending = item
        svc.ui {
            add(item)
            setPhase(Phase.ASKING, if (item.kind == FeedItem.Kind.CONFIRM) "Needs your OK" else "Needs your answer")
            expandAuto()
            buzz()
        }
        try {
            while (!stopped) {
                val a = answers.poll(250, TimeUnit.MILLISECONDS) ?: continue
                svc.ui { setPhase(Phase.WORKING, "Continuing…"); collapseAuto() }
                return a
            }
            return null
        } finally {
            pending = null
        }
    }

    private fun event(step: Int, kind: String, text: String, sub: String, ok: Boolean) {
        synchronized(events) { events.put(JSONObject().put("step", step).put("kind", kind).put("text", text).put("sub", sub).put("ok", ok)) }
    }

    // ---------------------------------------------------------------- loop

    /** AUTO mode: attach a screenshot when the model asked for it, when things go wrong, or when the tree is thin. */
    private fun needScreenshot(elements: Map<Int, El>): Boolean = when (mode) {
        Mode.TREE -> false
        Mode.SCREENSHOT, Mode.HYBRID -> true
        Mode.AUTO -> {
            val recent = history.takeLast(2)
            val trouble = recent.isNotEmpty() && recent.all { it.contains("error") || it.contains("[screen did not change]") || it.contains("[REPEATED") }
            val appInteractive = elements.values.count { it.interactive && it.inApp }
            lookRequested || trouble || appInteractive < 3
        }
    }

    private fun actionKey(c: Call, obs: Observation): String {
        val a = c.args
        val target = when {
            a.has("element") && !a.isNull("element") -> obs.elements[a.optInt("element")]?.label ?: a.optString("element")
            a.has("x") -> "${a.optInt("x") / 25},${a.optInt("y") / 25}"
            else -> ""
        }
        return c.name + "|" + target + "|" + a.optString("text") + a.optString("key") + a.optString("name") + a.optString("direction") + "|" + obs.pkg
    }

    private class Call(val name: String, val args: JSONObject)

    fun run(): String {
        val startedAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        val dir = File(Runs.root(svc), "$stamp-${mode.key}").apply { mkdirs() }
        AgentBus.runFile = File(dir, "log.txt")
        AgentBus.log("▶ Task: $task")
        AgentBus.log("  Mode: ${mode.label} · Model: $model · Effort: $effort · Ask first: $askFirst")
        val t0 = SystemClock.elapsedRealtime()
        var inTok = 0L
        var outTok = 0L
        var llmMs = 0L
        var answer: String? = null
        var status = "failed"
        var lastSig: Int? = null
        var idle = 0

        svc.ui { setPhase(Phase.WORKING, "Starting…") }
        if (goHome) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(900)
        }

        loop@ while (step < MAX_STEPS) {
            // The user may touch the screen while the UI settles, so check again until both are quiet.
            do {
                waitWhileHeld()
                if (stopped) break@loop
                svc.settle()
            } while (holdReason() != null)
            step++
            val step = step
            val mark = svc.interrupts.get()
            val obs = svc.observe(marks = mode == Mode.HYBRID || mode == Mode.AUTO) { needScreenshot(it) }
            obs.jpeg?.let { File(dir, "step-%02d.jpg".format(step)).writeBytes(it) }
            File(dir, "step-%02d-tree.txt".format(step)).writeText(obs.tree)
            if (lastSig != null && obs.signature == lastSig && history.isNotEmpty()) {
                history[history.lastIndex] = history.last() + "  [screen did not change]"
            }
            lastSig = obs.signature
            val app = svc.appLabel(obs.pkg)
            if (obs.pkg.isNotEmpty()) apps += app
            svc.ui { setStatus("Thinking…") }

            val withShot = obs.shotForModel
            lookRequested = false
            if (withShot) shotsSent++
            val req = buildRequest(obs, step, withShot)
            val tReq = SystemClock.elapsedRealtime()
            val resp = try { call(req) } catch (e: Exception) {
                if (stopped) break
                AgentBus.log("✖ API: ${e.message}")
                answer = "AI request failed: ${e.message}"
                break
            }
            val dt = SystemClock.elapsedRealtime() - tReq
            llmMs += dt
            val usage = resp.optJSONObject("usage")
            val i = usage?.optInt("input_tokens") ?: 0
            val o = usage?.optInt("output_tokens") ?: 0
            inTok += i
            outTok += o

            val texts = ArrayList<String>()
            val calls = ArrayList<Call>()
            val output = resp.optJSONArray("output") ?: JSONArray()
            for (k in 0 until output.length()) {
                val item = output.getJSONObject(k)
                when (item.optString("type")) {
                    "message" -> {
                        val content = item.optJSONArray("content") ?: JSONArray()
                        for (m in 0 until content.length()) content.getJSONObject(m).optString("text").takeIf { it.isNotBlank() }?.let { texts += it }
                    }
                    "function_call" -> {
                        val name = item.optString("name").substringAfterLast('.').substringAfterLast("__")
                        val args = try { JSONObject(item.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
                        calls += Call(name, args)
                    }
                }
            }
            AgentBus.log("— Step $step (${obs.pkg}) · AI ${"%.1f".format(dt / 1000.0)} s · $i in / $o out" + if (withShot && mode == Mode.AUTO) " · 📷" else "")
            texts.forEach { AgentBus.log("💬 ${it.take(400)}") }
            if (stopped) break

            if (svc.interrupts.get() != mark) {
                // The user did something while the model was thinking; its plan is for an old screen.
                AgentBus.log("↺ Re-planning: ${svc.lastInterrupt}")
                history += "$step. (your planned ${calls.joinToString { it.name }.ifEmpty { "action" }} was not done: ${svc.lastInterrupt} meanwhile. Decide again from the current screen.)"
                continue@loop
            }

            if (calls.isEmpty()) {
                history += "$step. (no tool call) ${texts.joinToString(" ").take(200)}"
                if (++idle >= 2) { answer = texts.joinToString(" ").ifBlank { "No action." }; break }
                continue
            }
            idle = 0
            for (c in calls.take(4)) {
                if (stopped) break@loop
                waitWhileHeld()
                if (stopped) break@loop
                if (svc.interrupts.get() != mark) {
                    AgentBus.log("↺ Skipping the rest of step $step: ${svc.lastInterrupt}")
                    history += "$step. (skipped your remaining planned actions: ${svc.lastInterrupt}. Decide again from the current screen.)"
                    continue@loop
                }
                when (c.name) {
                    "finish" -> {
                        answer = c.args.optString("answer").ifBlank { "Done." }
                        success = c.args.optBoolean("success", true)
                        status = if (success) "done" else "failed"
                        AgentBus.log("➜ ${pretty(c, obs)} → done")
                        break@loop
                    }
                    "ask_user" -> {
                        val q = c.args.optString("question").ifBlank { "How should I continue?" }
                        val opts = c.args.optJSONArray("options")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }?.take(4) ?: emptyList()
                        AgentBus.log("❓ $q ${if (opts.isNotEmpty()) opts.toString() else ""}")
                        val a = ask(FeedItem(FeedItem.Kind.QUESTION, q, options = opts)) ?: break@loop
                        AgentBus.log("   ↳ $a")
                        history += "$step. ask_user(\"${q.take(120)}\") → the user answered: \"${a.take(300)}\""
                        event(step, "question", q, a, true)
                        continue@loop
                    }
                    "run_script" -> {
                        AgentBus.log("➜ run_script (${c.args.optJSONArray("steps")?.length() ?: 0} steps)" + c.args.optString("why").let { if (it.isNotBlank()) " – $it" else "" })
                        val log = runScript(c, obs, step, mark)
                        history += "$step. $log"
                        continue@loop
                    }
                    "confirm" -> {
                        val what = c.args.optString("summary").ifBlank { c.args.optString("action") }.ifBlank { "Continue?" }
                        val button = c.args.optString("button").ifBlank { "Do it" }.take(24)
                        AgentBus.log("🛡 Confirm: $what [$button]")
                        val a = ask(FeedItem(FeedItem.Kind.CONFIRM, what, sub = button)) ?: break@loop
                        val res = when (a) {
                            FeedItem.CONFIRM_YES -> "APPROVED by the user. Do it now (press the final button)."
                            FeedItem.CONFIRM_NO -> "DECLINED by the user. Do NOT do it. Finish and say that it was not done."
                            else -> "Not approved yet; the user replied: \"${a.take(300)}\". Adjust accordingly and call confirm again before the final action."
                        }
                        AgentBus.log("   ↳ $res")
                        history += "$step. confirm(\"${what.take(160)}\") → $res"
                        event(step, "confirm", what, when (a) { FeedItem.CONFIRM_YES -> "approved"; FeedItem.CONFIRM_NO -> "cancelled"; else -> a }, a == FeedItem.CONFIRM_YES)
                        continue@loop
                    }
                }
                val pretty = pretty(c, obs)
                svc.ui { setStatus(doing(c, obs)) }
                val res = try { exec(c, obs) } catch (e: Exception) { "error: ${e.message}" }
                AgentBus.log("➜ $pretty → $res")
                val ok = !(res.startsWith("error") || res.contains("failed") || res.startsWith("no app") || res.startsWith("unknown"))
                val why = c.args.optString("why")
                val text = did(c, obs, res)
                svc.ui { add(FeedItem(FeedItem.Kind.ACTION, text, sub = why, ok = ok)) }
                event(step, "action", text, why, ok)
                // Loop detection: the same action on the same target within the last 6 actions.
                val key = actionKey(c, obs)
                val repeated = c.name !in setOf("wait", "scroll", "swipe", "look") && recentKeys.takeLast(6).contains(key)
                recentKeys += key
                // Scrolling back and forth on the same list is a loop too.
                val scrolling = c.name == "scroll" && recentKeys.size >= 6 && recentKeys.takeLast(6).all { it.startsWith("scroll|") }
                history += "$step. $pretty → $res" + when {
                    repeated -> "  [REPEATED – you already did this recently; it did not get you closer. Try a different approach.]"
                    scrolling -> "  [You have only scrolled for 6 actions. Stop scrolling: use what you have seen, or finish with what you know.]"
                    else -> ""
                }
                if (calls.size > 1) svc.settle(1500)
            }
        }
        if (stopped && status != "done") status = "stopped"
        val finalAnswer = answer ?: if (stopped) "Stopped by you." else "Stopped: reached the step limit ($MAX_STEPS)."
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        result = finalAnswer
        AgentBus.log("■ Result: $finalAnswer")
        AgentBus.log("  $step steps · ${"%.0f".format(secs)} s total (AI ${"%.0f".format(llmMs / 1000.0)} s) · tokens $inTok in / $outTok out · screenshots sent to AI: $shotsSent")
        val msgs = JSONArray()
        synchronized(messages) { messages.forEach { msgs.put(JSONObject().put("step", it.step).put("text", it.text).put("spoken", it.spoken)) } }
        File(dir, "summary.json").writeText(JSONObject()
            .put("task", task).put("mode", mode.key).put("model", model).put("effort", effort).put("ask_first", askFirst)
            .put("started_at", startedAt).put("ended_at", System.currentTimeMillis()).put("status", status)
            .put("steps", step).put("seconds", secs).put("llm_seconds", llmMs / 1000.0)
            .put("input_tokens", inTok).put("output_tokens", outTok)
            .put("screenshots_sent", shotsSent).put("success", success).put("answer", finalAnswer)
            .put("apps", JSONArray(apps.toList())).put("messages", msgs).put("events", synchronized(events) { JSONArray(events.toString()) })
            .apply { if (context != null) put("follow_up_of", context) }
            .toString(2))
        AgentBus.runFile = null
        val title = when (status) { "done" -> "Done"; "stopped" -> "Stopped"; else -> "Didn't finish" }
        svc.ui {
            add(FeedItem(FeedItem.Kind.RESULT, finalAnswer, sub = duration(secs), options = listOf(title), ok = status == "done"))
            setPhase(if (status == "done") Phase.DONE else Phase.FAILED, if (status == "stopped") "Stopped" else finalAnswer.lineSequence().first().take(120))
            expand()
        }
        Runs.prune(svc)
        return finalAnswer
    }

    // ---------------------------------------------------------------- request

    private fun instructions(): String {
        val screen = when (mode) {
            Mode.TREE -> """The current screen is given as a list of UI elements from the Android accessibility tree, one per line:
  [id] Type "label" flags @(x,y)
grouped by window (App, Keyboard, System). Use element ids for tap / long_press / type_text / scroll. The @(x,y) centers are in a ${'$'}W x ${'$'}H coordinate space that you can use for swipe or for tapping points."""
            Mode.SCREENSHOT -> """The current screen is given as a screenshot (${'$'}W x ${'$'}H pixels, origin top-left). Find targets visually and pass the pixel coordinates (x,y) of the CENTER of the target. type_text types into the currently focused input field, so tap the field first."""
            Mode.HYBRID -> """The current screen is given as a screenshot (${'$'}W x ${'$'}H pixels) where interactive elements have numbered colored boxes, plus the matching element list from the accessibility tree:
  [id] Type "label" flags @(x,y)
Prefer element ids. Use x,y pixel coordinates for things that have no box/id."""
            Mode.AUTO -> """The current screen is given as a list of UI elements from the Android accessibility tree, one per line:
  [id] Type "label" flags @(x,y)
grouped by window (App, Keyboard, System). Prefer element ids for tap / long_press / type_text / scroll. Coordinates are in a ${'$'}W x ${'$'}H space.
Some turns also include a screenshot of the same screen with numbered boxes matching the ids. If what you need is NOT in the list (icons without labels such as ☰ menus, images, maps, canvas or game content) and no screenshot is attached, call look; on the next turn tap it with x,y from the screenshot."""
        }
        val safety = if (askFirst) """- Safety: never send messages or emails, post, buy, pay, delete data or change account, security, privacy or payment settings unless the task or the user's messages explicitly ask for exactly that. When they do, prepare everything, then call confirm with exactly what will happen (recipient and text, amount, what gets deleted) BEFORE pressing the final button. Press it only after confirm returned APPROVED, as a single tap (never inside run_script); if it was declined, do not do it."""
        else """- Safety: never send messages or emails, post, buy, pay, delete data or change account, security, privacy or payment settings unless the task or the user's messages explicitly ask for exactly that. When they do, you may complete it without asking."""
        return """You are Phone Agent. You operate the user's Android phone to complete the user's task by calling the phone tools.
Each turn you receive the task, messages the user sent during the run, the log of your previous actions, and the CURRENT screen. Then you act.

$screen

How to work:
- Be fast. When you can predict the next steps, call run_script with several steps in ONE turn instead of one action per turn. Typical cases: open an app and follow a menu path you know (open_app "Settings" → tap "Connected devices" → tap "Connection preferences" → tap "Bluetooth"), fill in a form you can see (type into "Name", type into "Email", tap "Next"), or search (tap "Search", then type with submit). Script steps find their target by its visible text, so they also work on screens you have not seen yet: use the exact words the screen will show, in the language of the phone's UI. Each step waits up to 3 s for its target. The script stops at the first step that fails; you then get the current screen and continue with a new script or single actions.
- Use single tools (element ids or x,y) when you cannot predict the next screen or the target has no text (unlabeled icons). Do not call several single tools in one turn when the first one changes the screen; use run_script for that.
- To start an app, use open_app with the app's name instead of searching for icons.
- If an action did not have the expected effect ("[screen did not change]"), do not repeat it blindly: try another element, scroll, go back, or use a search field inside the app.
- Dismiss onboarding screens, tips and permission dialogs in the most neutral way (e.g. "Skip", "Not now", "Allow only while using the app") when they block the task. Ignore banners and notices that do not block the task.
- Look things up in the apps; do not guess facts.
- If an ordinary setting blocks the task (e.g. Do Not Disturb prevents creating alarms), change it directly and mention it in your final answer. Do not go back and forth about it.
- The user watches the run and can steer you at any time. Their messages appear under MESSAGES FROM THE USER; they refine or change the task, and the newest message wins. Act on them right away without commenting.
- The user may also use the phone themselves during the run. If the screen changed unexpectedly, continue from what you see now.
- If the task is ambiguous or needs information you cannot find on the phone (which of two matching contacts, what time, which account), call ask_user with one short question and up to 4 short answer options instead of guessing. Do not ask about things you can look up or reasonably decide yourself.
$safety
- Never type passwords or one-time codes. If a login is required, finish and tell the user.
- When the task is done (or impossible), call finish with success and a short answer in the language of the task. Include any information the user asked for."""
    }

    private fun buildRequest(obs: Observation, step: Int, withShot: Boolean): JSONObject {
        val text = StringBuilder()
        if (context != null) text.append("CONTEXT: this is a follow-up to an earlier task. ").append(context).append("\n\n")
        text.append("TASK: ").append(task).append("\n\n")
        val msgs = synchronized(messages) { messages.toList() }
        if (msgs.isNotEmpty()) {
            text.append("MESSAGES FROM THE USER DURING THE RUN (newest last; they refine or override the task):\n")
            msgs.forEach { text.append("- (before step ").append(it.step + 1).append(") \"").append(it.text).append("\"\n") }
            text.append('\n')
        }
        text.append("PREVIOUS ACTIONS:\n")
        if (history.isEmpty()) text.append("(none yet)\n") else history.takeLast(40).forEach { text.append(it).append('\n') }
        text.append("\nSTEP ").append(step).append(" of max ").append(MAX_STEPS).append(".\n")
        if (step == MAX_STEPS) text.append("This is the LAST step: call finish now and report what was done.\n")
        text.append("CURRENT SCREEN — foreground app: ").append(obs.pkg.ifEmpty { "?" })
            .append(", coordinate space ").append(obs.w).append('x').append(obs.h).append('\n')
        obs.screenshotNote?.let { text.append("Note: ").append(it).append('\n') }
        if (mode == Mode.AUTO) text.append(if (withShot) "(screenshot with numbered boxes attached)\n" else "(no screenshot this turn – call look if you need to see the screen)\n")
        if (mode != Mode.SCREENSHOT) text.append(obs.tree)

        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", text.toString()))
        if (withShot && obs.jpeg != null) {
            content.put(JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(obs.jpeg, Base64.NO_WRAP))
                .put("detail", "high"))
        }
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions().replace("\$W", obs.w.toString()).replace("\$H", obs.h.toString()))
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("tools", tools())
        if (Compat.toolChoice) body.put("tool_choice", "required")
        if (Compat.parallel) body.put("parallel_tool_calls", true)
        if (Compat.reasoning) body.put("reasoning", JSONObject().put("effort", effort))
        return body
    }

    /** Sends the request; if the plan route rejects an optional field, drops it and retries. */
    private fun call(body: JSONObject): JSONObject {
        repeat(5) {
            try {
                return OpenAI.respond(body)
            } catch (e: OpenAI.ApiError) {
                if (e.status != 400) throw e
                val m = ((e.message ?: "") + " " + (e.param ?: "")).lowercase()
                when {
                    Compat.toolChoice && m.contains("tool_choice") -> { Compat.toolChoice = false; body.remove("tool_choice") }
                    Compat.parallel && m.contains("parallel_tool_calls") -> { Compat.parallel = false; body.remove("parallel_tool_calls") }
                    Compat.reasoning && m.contains("reasoning") -> { Compat.reasoning = false; body.remove("reasoning") }
                    Compat.namespace && (m.contains("namespace") || m.contains("tools")) -> { Compat.namespace = false; body.put("tools", tools()) }
                    else -> throw e
                }
                AgentBus.log("↻ Request adjusted after: ${e.message?.take(200)}")
            }
        }
        return OpenAI.respond(body)
    }

    private fun tools(): JSONArray {
        val ids = mode != Mode.SCREENSHOT
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun enumProp(desc: String, vararg values: String) = JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))
        fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
            props.put("why", prop("string", "Very short reason, shown in the user's log"))
            return JSONObject().put("type", "function").put("name", name).put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(required)).put("additionalProperties", false))
        }
        fun point(): JSONObject = JSONObject().apply {
            if (ids) put("element", prop("integer", "Element id from the list (preferred)"))
            put("x", prop("integer", "X coordinate${if (ids) " (only if there is no element id)" else ""}"))
            put("y", prop("integer", "Y coordinate"))
        }
        val pointReq = if (ids) listOf() else listOf("x", "y")
        val fns = JSONArray()
        fns.put(fn("tap", if (ids) "Tap an element by id, or a point x,y." else "Tap the screen at x,y.", point(), pointReq))
        fns.put(fn("long_press", "Long-press an element or point (e.g. to select or open a context menu).", point(), pointReq))
        fns.put(fn("type_text",
            "Type text into an input field. " + (if (ids) "Give the field's element id, or omit it to use the focused field. " else "Types into the focused field; tap the field first. ") +
                "Replaces the field's text unless clear=false. submit=true presses Enter/Search/Send on the keyboard afterwards.",
            JSONObject().put("text", prop("string", "Text to type")).apply {
                if (ids) put("element", prop("integer", "Element id of the input field"))
                put("clear", prop("boolean", "Replace existing text (default true)"))
                put("submit", prop("boolean", "Press the keyboard's Enter/Search action afterwards"))
            }, listOf("text")))
        fns.put(fn("swipe", "Swipe/drag from (x1,y1) to (x2,y2).",
            JSONObject().put("x1", prop("integer", "start x")).put("y1", prop("integer", "start y"))
                .put("x2", prop("integer", "end x")).put("y2", prop("integer", "end y"))
                .put("duration_ms", prop("integer", "Duration, default 400")),
            listOf("x1", "y1", "x2", "y2")))
        fns.put(fn("scroll", "Scroll to reveal more content. 'down' reveals content further below." + (if (ids) " Optionally give the scrollable element id." else ""),
            JSONObject().put("direction", enumProp("Direction", "up", "down", "left", "right")).apply {
                if (ids) put("element", prop("integer", "Scrollable element id"))
            }, listOf("direction")))
        fns.put(fn("run_script",
            "Run several actions in a row without looking at the screen in between – much faster than one action per turn. " +
                "Steps find their target by its visible text (target), so they work on screens you have not seen yet; each step waits up to 3 s for it to appear. " +
                "The script stops at the first step that fails, and you get a report plus the current screen.",
            JSONObject().put("steps", JSONObject().put("type", "array").put("description", "2-$MAX_SCRIPT_STEPS steps, run in order")
                .put("items", scriptStepSchema())), listOf("steps")))
        fns.put(fn("press_key", "Press a system key.",
            JSONObject().put("key", enumProp("Key", "back", "home", "recents", "notifications", "quick_settings", "enter")), listOf("key")))
        fns.put(fn("open_app", "Launch an installed app by its name, e.g. 'Settings', 'Chrome', 'Calendar'.",
            JSONObject().put("name", prop("string", "App name")), listOf("name")))
        fns.put(fn("wait", "Wait for something to load (max 5 s).", JSONObject().put("seconds", prop("number", "Seconds")), listOf("seconds")))
        if (mode == Mode.AUTO) fns.put(fn("look",
            "Get a screenshot (with numbered boxes) of the current screen on your next turn. Use it when the target is not in the element list: unlabeled icons, images, maps, canvas or game content.",
            JSONObject(), listOf()))
        fns.put(fn("ask_user",
            "Ask the user a short question when the task is ambiguous or needs information you cannot find on the phone. The run waits for the answer (tap, typed or spoken).",
            JSONObject().put("question", prop("string", "One short question in the task's language"))
                .put("options", JSONObject().put("type", "array").put("description", "Up to 4 short answer options (may be empty)")
                    .put("items", JSONObject().put("type", "string"))),
            listOf("question", "options")))
        if (askFirst) fns.put(fn("confirm",
            "Required right before an irreversible final step (send, post, buy/pay, delete, change account or security settings). Shows the user exactly what will happen and waits for approval.",
            JSONObject().put("summary", prop("string", "Exactly what will happen, e.g. 'Send \"Running 10 min late\" to Alex Becker in Messages'"))
                .put("button", prop("string", "Short label of the action, e.g. 'Send', 'Pay 12.90 €', 'Delete'")),
            listOf("summary", "button")))
        fns.put(fn("finish", "End the task with the final answer for the user (in the task's language).",
            JSONObject().put("success", prop("boolean", "Whether the task was completed")).put("answer", prop("string", "Answer / summary for the user")),
            listOf("success", "answer")))
        if (!Compat.namespace) return fns
        return JSONArray().put(JSONObject().put("type", "namespace").put("name", "phone")
            .put("description", "Operate the user's Android phone").put("tools", fns))
    }

    private fun scriptStepSchema(): JSONObject {
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun enumProp(desc: String, vararg values: String) = JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))
        val props = JSONObject()
            .put("action", enumProp("What to do", "tap", "long_press", "type", "scroll", "press_key", "open_app", "wait", "wait_for"))
            .put("target", prop("string", "tap/long_press/wait_for: the element's visible text or description, exactly as shown. type: the field's label or hint (omit to type into the focused field). scroll: the scrollable list (optional)."))
            .put("element", prop("integer", "Element id from the CURRENT screen; only valid in the first step"))
            .put("x", prop("integer", "X coordinate, if the target has no text"))
            .put("y", prop("integer", "Y coordinate"))
            .put("text", prop("string", "type: the text to type"))
            .put("submit", prop("boolean", "type: press Enter/Search afterwards"))
            .put("clear", prop("boolean", "type: replace the field's text (default true)"))
            .put("key", enumProp("press_key: the key", "back", "home", "recents", "notifications", "quick_settings", "enter"))
            .put("name", prop("string", "open_app: the app's name"))
            .put("direction", enumProp("scroll: direction", "up", "down", "left", "right"))
            .put("until", prop("string", "scroll: keep scrolling until this text is on screen (max 8 times)"))
            .put("seconds", prop("number", "wait: seconds (max 5); wait_for: timeout (default 5, max 10)"))
        return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(listOf("action"))).put("additionalProperties", false)
    }

    // ---------------------------------------------------------------- scripts

    private class StepResult(val ok: Boolean, val msg: String)

    /** Runs a run_script call step by step and stops at the first failure. Returns the entry for the action history. */
    private fun runScript(c: Call, obs: Observation, step: Int, mark: Int): String {
        val arr = c.args.optJSONArray("steps")
        val steps = if (arr == null) emptyList() else (0 until minOf(arr.length(), MAX_SCRIPT_STEPS)).mapNotNull { arr.optJSONObject(it) }
        if (steps.isEmpty()) return "run_script → error: no steps given"
        val why = c.args.optString("why")
        val lines = ArrayList<String>()
        var notRunFrom = -1
        var prev = ""
        for ((k, st) in steps.withIndex()) {
            waitWhileHeld()
            if (stopped) { notRunFrom = k + 1; break }
            if (svc.interrupts.get() != mark) {
                lines += "(interrupted: ${svc.lastInterrupt})"
                notRunFrom = k + 1
                break
            }
            if (k > 0) svc.settle(maxMs = 2500, quietMs = 300, minMs = if (prev == "open_app") 700 else 150)
            val label = stepTarget(st, obs, k)
            svc.ui { setStatus(stepDoing(st, label)) }
            val r = try { execStep(st, k, obs, prev) } catch (e: Exception) { StepResult(false, "error: ${e.message}") }
            val pretty = stepPretty(st, label)
            AgentBus.log("   ${k + 1}) $pretty → ${r.msg}")
            lines += "${k + 1}) $pretty → ${r.msg}"
            val text = stepDid(st, label, r)
            val sub = if (k == 0) why else ""
            svc.ui { add(FeedItem(FeedItem.Kind.ACTION, text, sub = sub, ok = r.ok)) }
            event(step, "action", text, sub, r.ok)
            prev = st.optString("action")
            if (!r.ok) { notRunFrom = k + 2; break }
        }
        val sb = StringBuilder("run_script")
        if (why.isNotBlank()) sb.append(" – ").append(why)
        sb.append(':')
        lines.forEach { sb.append("\n   ").append(it) }
        sb.append("\n   ").append(when {
            notRunFrom < 0 -> "All ${steps.size} steps done."
            notRunFrom > steps.size -> "Script stopped at its last step. Continue from the current screen."
            else -> "Script stopped; steps $notRunFrom-${steps.size} were not run. Continue from the current screen."
        })
        return sb.toString()
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[\"'“”„‘’«»…]"), "").replace(Regex("\\s+"), " ").trim()

    /** How well an element matches [q] (normalized): 100 = exact text, less for partial matches; adjusted for what the step needs. */
    private fun score(e: El, q: String, want: String): Pair<Int, Boolean> {
        val parts = ArrayList<String>()
        e.label.split(" | ").forEach { parts += norm(it.removePrefix("#")) }
        e.node.hintText?.let { parts += norm(it.toString()) }
        e.node.contentDescription?.let { parts += norm(it.toString()) }
        e.node.viewIdResourceName?.substringAfter(":id/")?.let { parts += norm(it.replace('_', ' ')) }
        var best = 0
        for (p in parts) {
            if (p.isEmpty()) continue
            val v = when {
                p == q -> 100
                q.length >= 3 && p.startsWith(q) && !p[q.length].isLetterOrDigit() -> 80
                q.length >= 3 && Regex("(^|\\W)" + Regex.escape(q) + "($|\\W)").containsMatchIn(p) -> 60
                q.length >= 4 && p.contains(q) -> 45
                else -> 0
            }
            if (v > best) best = v
        }
        if (best == 0) return 0 to false
        var sc = best
        when (want) {
            "type" -> sc += if (e.node.isEditable) 30 else -40
            "scroll" -> sc += if (e.node.isScrollable) 30 else -40
            "tap" -> if (e.interactive) sc += 8
        }
        if (!e.inApp) sc -= 12
        if (!e.node.isEnabled) sc -= 25
        return sc to (best == 100)
    }

    /** Finds the element matching [query] on the current screen, waiting up to [timeoutMs] for it to appear. */
    private fun find(query: String, want: String, timeoutMs: Long): El? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val start = SystemClock.uptimeMillis()
        while (true) {
            var best: El? = null
            var bestScore = 0
            var exact = false
            for (e in svc.elements().values) {
                val (sc, ex) = score(e, q, want)
                if (sc > bestScore) { best = e; bestScore = sc; exact = ex }
            }
            val waited = SystemClock.uptimeMillis() - start
            if (best != null && exact && bestScore >= 60) return best
            // A partial match may be the previous screen still fading out; give the exact one a moment to show up.
            if (best != null && bestScore >= 50 && waited >= minOf(800L, timeoutMs)) return best
            if (waited >= timeoutMs || stopped) return null
            Thread.sleep(200)
        }
    }

    private fun stepPoint(st: JSONObject, obs: Observation): Pair<Float, Float>? {
        val x = st.optDouble("x", -1.0)
        val y = st.optDouble("y", -1.0)
        return if (x > 0 && y > 0) (x.toFloat() / obs.scale) to (y.toFloat() / obs.scale) else null
    }

    /** The element a step points at: its id (first step only), else its target text, else the text of the given id. */
    private fun stepElement(st: JSONObject, k: Int, obs: Observation, want: String, timeoutMs: Long): Pair<El?, String?> {
        val hasId = st.has("element") && !st.isNull("element")
        if (hasId && k == 0) return (obs.elements[st.optInt("element")] ?: return null to "error: element ${st.opt("element")} does not exist") to null
        val q = st.optString("target").trim().ifEmpty {
            if (hasId) obs.elements[st.optInt("element")]?.label?.substringBefore(" | ")?.takeIf { !it.startsWith("#") } ?: "" else ""
        }
        if (q.isEmpty()) return null to null
        val e = find(q, want, timeoutMs) ?: return null to "error: nothing matching \"$q\" on the screen"
        return e to null
    }

    private fun execStep(st: JSONObject, k: Int, obs: Observation, prev: String): StepResult {
        val action = st.optString("action")
        val timeout = if (prev == "open_app") 5000L else 3000L
        fun fail(m: String) = StepResult(false, m)
        return when (action) {
            "tap", "long_press" -> {
                val (e, err) = stepElement(st, k, obs, "tap", timeout)
                if (err != null) return fail(err)
                val name = e?.label?.substringBefore(" | ") ?: st.optString("target")
                if (askFirst && FINAL_BUTTON.matches(norm(name))) {
                    return fail("error: \"$name\" is a final send/pay/delete button. Call confirm first, then tap it as a single action (not in run_script).")
                }
                val long = action == "long_press"
                if (e != null) {
                    val r = Rect(e.rect)
                    r.intersect(0, 0, (obs.w / obs.scale).toInt(), (obs.h / obs.scale).toInt())
                    when {
                        svc.tap(r.exactCenterX(), r.exactCenterY(), if (long) 800 else 60) -> StepResult(true, "ok")
                        svc.click(e.node, long) -> StepResult(true, "ok (accessibility click)")
                        else -> fail("tap failed")
                    }
                } else {
                    val pt = stepPoint(st, obs) ?: return fail("error: give a target text, an element id (first step) or x,y")
                    if (svc.tap(pt.first, pt.second, if (long) 800 else 60)) StepResult(true, "ok") else fail("tap failed")
                }
            }
            "type" -> {
                val text = st.optString("text")
                val clear = st.optBoolean("clear", true)
                val submit = st.optBoolean("submit", false)
                val (e, err) = stepElement(st, k, obs, "type", timeout)
                if (err != null) return fail(err)
                val res = if (e != null) typeInto(e.node, e.rect, text, clear, submit) else {
                    val focused = svc.focusedInput()
                    val only = if (focused == null) svc.elements().values.filter { it.node.isEditable && it.inApp }.singleOrNull() else null
                    typeInto(focused ?: only?.node, only?.rect, text, clear, submit)
                }
                StepResult(!(res.startsWith("error") || res.contains("failed")), res)
            }
            "scroll" -> {
                val dir = st.optString("direction", "down")
                val container = if (st.optString("target").isNotBlank()) stepElement(st, k, obs, "scroll", 1500).first else null
                val sw = obs.w / obs.scale
                val sh = obs.h / obs.scale
                val until = st.optString("until").trim()
                if (until.isEmpty()) {
                    val res = scrollWith(dir, container, sw, sh)
                    return StepResult(!res.contains("failed"), res)
                }
                repeat(8) { i ->
                    if (stopped) return fail("stopped")
                    if (find(until, "any", 0) != null) return StepResult(true, "ok, \"$until\" is on screen" + if (i > 0) " after $i scrolls" else "")
                    val before = svc.screenSignature()
                    scrollWith(dir, container, sw, sh)
                    svc.settle(maxMs = 1500, quietMs = 250, minMs = 200)
                    if (svc.screenSignature() == before) return fail("reached the end of the list; \"$until\" not found")
                }
                if (find(until, "any", 0) != null) StepResult(true, "ok, \"$until\" is on screen after 8 scrolls")
                else fail("\"$until\" not found after 8 scrolls")
            }
            "press_key" -> {
                val res = pressKey(st.optString("key"))
                StepResult(res.startsWith("ok"), res)
            }
            "open_app" -> {
                val res = svc.openApp(st.optString("name"))
                StepResult(res.startsWith("opened "), res)
            }
            "wait" -> { Thread.sleep((st.optDouble("seconds", 1.0).coerceIn(0.2, 5.0) * 1000).toLong()); StepResult(true, "ok") }
            "wait_for" -> {
                val q = st.optString("target")
                val ms = (st.optDouble("seconds", 5.0).coerceIn(0.5, 10.0) * 1000).toLong()
                if (find(q, "any", ms) != null) StepResult(true, "ok, \"$q\" is on screen") else fail("\"$q\" did not appear within ${ms / 1000} s")
            }
            else -> fail("error: unknown action \"$action\"")
        }
    }

    private fun quote(t: String) = "“" + (if (t.length > 40) t.take(40) + "…" else t) + "”"

    /** The step's target as shown to the user ("“Bluetooth”"), or "". */
    private fun stepTarget(st: JSONObject, obs: Observation, k: Int): String {
        st.optString("target").trim().takeIf { it.isNotEmpty() }?.let { return quote(it) }
        if (st.has("element") && !st.isNull("element")) {
            obs.elements[st.optInt("element")]?.label?.substringBefore(" | ")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }?.let { return quote(it) }
        }
        return ""
    }

    private fun stepPretty(st: JSONObject, label: String): String {
        val a = st.optString("action")
        return a + when (a) {
            "tap", "long_press", "wait_for" -> " " + label.ifEmpty { if (st.has("x")) "(${st.optInt("x")},${st.optInt("y")})" else "?" }
            "type" -> " \"${st.optString("text").take(80)}\"" + (if (label.isNotEmpty()) " into $label" else "") + (if (st.optBoolean("submit")) " + enter" else "")
            "scroll" -> " ${st.optString("direction", "down")}" + st.optString("until").let { if (it.isNotBlank()) " until \"$it\"" else "" }
            "press_key" -> " ${st.optString("key")}"
            "open_app" -> " \"${st.optString("name")}\""
            "wait" -> " ${st.optDouble("seconds", 1.0)} s"
            else -> ""
        }
    }

    private fun stepDoing(st: JSONObject, t: String): String = when (st.optString("action")) {
        "tap" -> if (t.isEmpty()) "Tapping the screen" else "Tapping $t"
        "long_press" -> if (t.isEmpty()) "Long-pressing the screen" else "Long-pressing $t"
        "type" -> "Typing “${st.optString("text").take(40)}”"
        "scroll" -> st.optString("until").let { if (it.isNotBlank()) "Looking for ${quote(it)}" else "Scrolling ${st.optString("direction", "down")}" }
        "press_key" -> keyText(st.optString("key"), false)
        "open_app" -> "Opening ${st.optString("name")}"
        "wait_for" -> "Waiting for $t"
        else -> "Waiting for the app"
    }

    private fun stepDid(st: JSONObject, t: String, r: StepResult): String {
        val a = st.optString("action")
        if (!r.ok) return when (a) {
            "tap", "long_press", "wait_for" -> if (r.msg.startsWith("error: nothing matching") || r.msg.contains("did not appear")) "Couldn't find ${t.ifEmpty { "the target" }}" else "Couldn't tap ${t.ifEmpty { "there" }}"
            "type" -> "Couldn't type “${st.optString("text").take(40)}”"
            "scroll" -> st.optString("until").let { if (it.isNotBlank()) "Couldn't find ${quote(it)}" else "Couldn't scroll" }
            "open_app" -> "Couldn't find the app “${st.optString("name")}”"
            else -> "Step failed"
        }
        return when (a) {
            "tap" -> if (t.isEmpty()) "Tapped the screen" else "Tapped $t"
            "long_press" -> if (t.isEmpty()) "Long-pressed the screen" else "Long-pressed $t"
            "type" -> "Typed “${st.optString("text").take(60)}”" + if (r.msg.contains("enter pressed")) " and pressed Enter" else ""
            "scroll" -> st.optString("until").let { if (it.isNotBlank()) "Scrolled to ${quote(it)}" else "Scrolled ${st.optString("direction", "down")}" }
            "press_key" -> keyText(st.optString("key"), true)
            "open_app" -> "Opened " + r.msg.removePrefix("opened ").substringBefore(" (")
            "wait_for" -> "$t appeared"
            else -> "Waited"
        }
    }

    // ---------------------------------------------------------------- execute

    private class Target(val x: Float, val y: Float, val node: AccessibilityNodeInfo?)

    private fun target(a: JSONObject, obs: Observation): Target? {
        val e = if (a.has("element") && !a.isNull("element")) obs.elements[a.optInt("element")] else null
        if (e != null) {
            val r = Rect(e.rect)
            // Tap the visible part of the element.
            r.intersect(0, 0, (obs.w / obs.scale).toInt(), (obs.h / obs.scale).toInt())
            return Target(r.exactCenterX(), r.exactCenterY(), e.node)
        }
        val x = a.optDouble("x", -1.0)
        val y = a.optDouble("y", -1.0)
        if (x > 0 && y > 0) return Target(x.toFloat() / obs.scale, y.toFloat() / obs.scale, null)
        return null
    }

    private fun targetError(a: JSONObject, obs: Observation): String {
        val what = if (a.has("element")) "element ${a.opt("element")} does not exist (valid ids: 1–${obs.elements.size})" else "missing element or x,y"
        return "error: $what. If your target is not in the list, " + when (mode) {
            Mode.AUTO -> "call look to get a screenshot, then tap with x,y."
            Mode.TREE -> "use x,y coordinates or another element."
            else -> "tap with x,y pixel coordinates from the screenshot."
        }
    }

    private fun exec(c: Call, obs: Observation): String {
        val a = c.args
        return when (c.name) {
            "tap" -> {
                val t = target(a, obs) ?: return targetError(a, obs)
                when {
                    svc.tap(t.x, t.y) -> "ok"
                    t.node != null && svc.click(t.node) -> "ok (accessibility click)"
                    else -> "tap failed"
                }
            }
            "look" -> { lookRequested = true; "ok – a screenshot will be attached next turn" }
            "long_press" -> {
                val t = target(a, obs) ?: return targetError(a, obs)
                when {
                    svc.tap(t.x, t.y, 800) -> "ok"
                    t.node != null && svc.click(t.node, long = true) -> "ok (accessibility long click)"
                    else -> "long press failed"
                }
            }
            "type_text" -> typeText(a, obs)
            "swipe" -> {
                val s = obs.scale
                val ok = svc.swipe(a.optDouble("x1").toFloat() / s, a.optDouble("y1").toFloat() / s,
                    a.optDouble("x2").toFloat() / s, a.optDouble("y2").toFloat() / s, a.optLong("duration_ms", 400).coerceIn(50, 3000))
                if (ok) "ok" else "swipe failed"
            }
            "scroll" -> scroll(a, obs)
            "press_key" -> pressKey(a.optString("key"))
            "open_app" -> svc.openApp(a.optString("name"))
            "wait" -> { Thread.sleep((a.optDouble("seconds", 1.0).coerceIn(0.2, 5.0) * 1000).toLong()); "ok" }
            "finish" -> "done"
            else -> "unknown tool ${c.name}"
        }
    }

    private fun global(action: Int) = if (svc.performGlobalAction(action)) "ok" else "failed"

    private fun pressKey(key: String): String = when (key) {
        "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        "enter" -> if (svc.imeEnter(null)) "ok" else "enter failed (no focused field?)"
        else -> "unknown key"
    }

    private fun typeText(a: JSONObject, obs: Observation): String {
        val text = a.optString("text")
        val clear = a.optBoolean("clear", true)
        val submit = a.optBoolean("submit", false)
        if (a.has("element") && !a.isNull("element")) {
            val e = obs.elements[a.optInt("element")] ?: return "error: unknown element"
            return typeInto(e.node, e.rect, text, clear, submit)
        }
        return typeInto(svc.focusedInput(), null, text, clear, submit)
    }

    /** Types into [field], tapping it first ([rect]) if it isn't the focused input yet. */
    private fun typeInto(field: AccessibilityNodeInfo?, rect: Rect?, text: String, clear: Boolean, submit: Boolean): String {
        var node = field
        if (node != null && rect != null && (!node.isEditable || !node.isFocused)) {
            svc.tap(rect.exactCenterX(), rect.exactCenterY())
            Thread.sleep(600)
            node = svc.focusedInput() ?: node
        }
        if (node == null) return "error: no focused input field – tap the field first"
        node.refresh()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) svc.fx { outline(bounds) }
        val existing = if (clear || node.isShowingHintText) "" else node.text?.toString() ?: ""
        var how = "typed"
        if (!svc.setText(node, existing + text)) {
            how = if (svc.paste(node, text)) "pasted" else return "typing failed (field not editable?)"
        }
        if (submit) {
            Thread.sleep(300)
            how += if (svc.imeEnter(svc.focusedInput() ?: node)) ", enter pressed" else ", enter failed – tap the search/send button instead"
        }
        return how
    }

    private fun scroll(a: JSONObject, obs: Observation): String {
        val e = if (a.has("element") && !a.isNull("element")) obs.elements[a.optInt("element")] else null
        return scrollWith(a.optString("direction", "down"), e, obs.w / obs.scale, obs.h / obs.scale)
    }

    /** Scrolls [e] (or the middle of the screen) with the accessibility action, else with a swipe. */
    private fun scrollWith(dir: String, e: El?, sw: Float, sh: Float): String {
        var area = e?.rect?.let { Rect(it) } ?: Rect(0, (sh * 0.2f).toInt(), sw.toInt(), (sh * 0.8f).toInt())
        // Swipe above the overlay when there is room, so the panel never has to let touches through meanwhile.
        val top = svc.overlayTop()
        if (top < area.bottom) {
            val free = Rect(area.left, maxOf(area.top, (sh * 0.12f).toInt()), area.right, top - (sh * 0.02f).toInt())
            if (free.height() >= sh * 0.2f) area = free
        }
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * 0.3f
        val dy = area.height() * 0.3f
        val (x1, y1, x2, y2) = when (dir) {
            "up" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx - dx, cy, cx + dx, cy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx, cy + dy, cx, cy - dy)
        }
        if (e != null && e.node.isScrollable && svc.scrollNode(e.node, dir)) {
            svc.fx { swipe(x1, y1, x2, y2, 350) }
            return "ok"
        }
        return if (svc.swipe(x1, y1, x2, y2, 450)) "ok (swipe)" else "scroll failed"
    }

    private fun pretty(c: Call, obs: Observation): String {
        val a = JSONObject(c.args.toString())
        val why = a.optString("why")
        a.remove("why")
        // Models often fill x=0,y=0 next to an element id; that is noise in the log.
        if (a.has("element") && !a.isNull("element")) {
            if (obs.elements.containsKey(a.optInt("element"))) { a.remove("x"); a.remove("y") } else a.remove("element")
        }
        val parts = ArrayList<String>()
        for (k in a.keys()) {
            val v = a.get(k)
            if (k == "element") {
                val label = obs.elements[a.optInt(k)]?.label?.take(30)
                parts += "element=$v" + (label?.let { " «$it»" } ?: "")
            } else {
                parts += "$k=" + (if (v is String) "\"${v.take(80)}\"" else v.toString())
            }
        }
        return c.name + "(" + parts.joinToString(", ") + ")" + (if (why.isNotBlank()) " – $why" else "")
    }

    // ---------------------------------------------------------------- texts for the overlay and run history

    private fun targetLabel(c: Call, obs: Observation): String {
        val a = c.args
        if (a.has("element") && !a.isNull("element")) {
            obs.elements[a.optInt("element")]?.label?.substringBefore(" | ")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }?.let {
                return "“" + (if (it.length > 40) it.take(40) + "…" else it) + "”"
            }
        }
        return ""
    }

    private fun keyText(key: String, past: Boolean) = when (key) {
        "back" -> if (past) "Went back" else "Going back"
        "home" -> if (past) "Went to the home screen" else "Going to the home screen"
        "recents" -> if (past) "Opened recent apps" else "Opening recent apps"
        "notifications" -> if (past) "Opened notifications" else "Opening notifications"
        "quick_settings" -> if (past) "Opened quick settings" else "Opening quick settings"
        "enter" -> if (past) "Pressed Enter" else "Pressing Enter"
        else -> if (past) "Pressed $key" else "Pressing $key"
    }

    /** What the agent is doing right now, for the island. */
    private fun doing(c: Call, obs: Observation): String {
        val a = c.args
        val t = targetLabel(c, obs)
        return when (c.name) {
            "tap" -> if (t.isEmpty()) "Tapping the screen" else "Tapping $t"
            "long_press" -> if (t.isEmpty()) "Long-pressing the screen" else "Long-pressing $t"
            "type_text" -> "Typing “${a.optString("text").take(40)}”"
            "swipe" -> "Swiping"
            "scroll" -> "Scrolling ${a.optString("direction", "down")}"
            "press_key" -> keyText(a.optString("key"), false)
            "open_app" -> "Opening ${a.optString("name")}"
            "wait" -> "Waiting for the app"
            "look" -> "Looking at the screen"
            else -> c.name
        }
    }

    /** What the agent did, for the feed and the run history. */
    private fun did(c: Call, obs: Observation, res: String): String {
        val a = c.args
        val t = targetLabel(c, obs)
        return when (c.name) {
            "tap" -> if (t.isEmpty()) "Tapped the screen" else "Tapped $t"
            "long_press" -> if (t.isEmpty()) "Long-pressed the screen" else "Long-pressed $t"
            "type_text" -> "Typed “${a.optString("text").take(60)}”" + if (res.contains("enter pressed")) " and pressed Enter" else ""
            "swipe" -> "Swiped"
            "scroll" -> "Scrolled ${a.optString("direction", "down")}"
            "press_key" -> keyText(a.optString("key"), true)
            "open_app" -> if (res.startsWith("opened ")) "Opened " + res.removePrefix("opened ").substringBefore(" (") else "Couldn't find the app “${a.optString("name")}”"
            "wait" -> "Waited ${"%.0f".format(a.optDouble("seconds", 1.0).coerceIn(0.2, 5.0))} s"
            "look" -> "Took a look at the screen"
            else -> c.name
        }
    }
}

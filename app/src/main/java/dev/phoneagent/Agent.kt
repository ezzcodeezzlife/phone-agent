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
    companion object { const val MAX_STEPS = 40 }

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
        val modelName = Prefs.modelName(model)
        val t0 = SystemClock.elapsedRealtime()
        var inTok = 0L
        var outTok = 0L
        var llmMs = 0L
        var answer: String? = null
        var status = "failed"
        var lastSig: Int? = null
        var idle = 0

        svc.ui { setPhase(Phase.WORKING, "Starting…"); setDetail("Starting · $modelName") }
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
            svc.ui { setDetail("Step $step · $app · $modelName"); setStatus("Thinking…") }

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
                    "confirm" -> {
                        val what = c.args.optString("summary").ifBlank { c.args.optString("action") }.ifBlank { "Continue?" }
                        val button = c.args.optString("button").ifBlank { "Do it" }.take(24)
                        AgentBus.log("🛡 Confirm: $what [$button]")
                        val a = ask(FeedItem(FeedItem.Kind.CONFIRM, what, sub = button)) ?: break@loop
                        val res = when (a) {
                            Overlay.CONFIRM_YES -> "APPROVED by the user. Do it now (press the final button)."
                            Overlay.CONFIRM_NO -> "DECLINED by the user. Do NOT do it. Finish and say that it was not done."
                            else -> "Not approved yet; the user replied: \"${a.take(300)}\". Adjust accordingly and call confirm again before the final action."
                        }
                        AgentBus.log("   ↳ $res")
                        history += "$step. confirm(\"${what.take(160)}\") → $res"
                        event(step, "confirm", what, when (a) { Overlay.CONFIRM_YES -> "approved"; Overlay.CONFIRM_NO -> "cancelled"; else -> a }, a == Overlay.CONFIRM_YES)
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
        val meta = "$step steps · ${duration(secs)} · ${compact(inTok + outTok)} tokens"
        val title = when (status) { "done" -> "Done"; "stopped" -> "Stopped"; else -> "Didn't finish" }
        svc.ui {
            add(FeedItem(FeedItem.Kind.RESULT, finalAnswer, sub = meta, options = listOf(title), ok = status == "done"))
            setPhase(if (status == "done") Phase.DONE else Phase.FAILED, finalAnswer.lineSequence().first().take(120))
            setDetail(meta)
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
        val safety = if (askFirst) """- Safety: never send messages or emails, post, buy, pay, delete data or change account, security, privacy or payment settings unless the task or the user's messages explicitly ask for exactly that. When they do, prepare everything, then call confirm with exactly what will happen (recipient and text, amount, what gets deleted) BEFORE pressing the final button. Press it only after confirm returned APPROVED; if it was declined, do not do it."""
        else """- Safety: never send messages or emails, post, buy, pay, delete data or change account, security, privacy or payment settings unless the task or the user's messages explicitly ask for exactly that. When they do, you may complete it without asking."""
        return """You are Phone Agent. You operate the user's Android phone to complete the user's task by calling the phone tools.
Each turn you receive the task, messages the user sent during the run, the log of your previous actions, and the CURRENT screen. Then you act.

$screen

How to work:
- Usually call exactly ONE tool per turn, then look at the new screen. Only chain 2-3 calls when the outcome is certain (e.g. tap a field, then type into it).
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
            "press_key" -> when (a.optString("key")) {
                "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                "enter" -> if (svc.imeEnter(null)) "ok" else "enter failed (no focused field?)"
                else -> "unknown key"
            }
            "open_app" -> svc.openApp(a.optString("name"))
            "wait" -> { Thread.sleep((a.optDouble("seconds", 1.0).coerceIn(0.2, 5.0) * 1000).toLong()); "ok" }
            "finish" -> "done"
            else -> "unknown tool ${c.name}"
        }
    }

    private fun global(action: Int) = if (svc.performGlobalAction(action)) "ok" else "failed"

    private fun typeText(a: JSONObject, obs: Observation): String {
        val text = a.optString("text")
        val clear = a.optBoolean("clear", true)
        val submit = a.optBoolean("submit", false)
        var node: AccessibilityNodeInfo?
        if (a.has("element") && !a.isNull("element")) {
            val e = obs.elements[a.optInt("element")] ?: return "error: unknown element"
            node = e.node
            if (!e.node.isEditable || !e.node.isFocused) {
                svc.tap(e.rect.exactCenterX(), e.rect.exactCenterY())
                Thread.sleep(600)
                node = svc.focusedInput() ?: e.node
            }
        } else {
            node = svc.focusedInput()
        }
        if (node == null) return "error: no focused input field – tap the field first"
        node.refresh()
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
        val dir = a.optString("direction", "down")
        val e = if (a.has("element") && !a.isNull("element")) obs.elements[a.optInt("element")] else null
        if (e != null && e.node.isScrollable && svc.scrollNode(e.node, dir)) return "ok"
        val sw = obs.w / obs.scale
        val sh = obs.h / obs.scale
        val area = e?.rect ?: Rect(0, (sh * 0.2f).toInt(), sw.toInt(), (sh * 0.8f).toInt())
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * 0.3f
        val dy = area.height() * 0.3f
        val ok = when (dir) {
            "up" -> svc.swipe(cx, cy - dy, cx, cy + dy, 450)
            "left" -> svc.swipe(cx - dx, cy, cx + dx, cy, 450)
            "right" -> svc.swipe(cx + dx, cy, cx - dx, cy, 450)
            else -> svc.swipe(cx, cy + dy, cx, cy - dy, 450)
        }
        return if (ok) "ok (swipe)" else "scroll failed"
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

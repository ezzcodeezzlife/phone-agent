package dev.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One listed UI element. [rect] is in real screen pixels. */
class El(val id: Int, val node: AccessibilityNodeInfo, val rect: Rect, val label: String, val interactive: Boolean, val inApp: Boolean)

/**
 * What the agent sees at one step. Coordinates shown to the model live in a "model space" that is the
 * screen scaled to [w] x [h] (720 px wide); divide by [scale] to get screen pixels.
 */
class Observation(
    val pkg: String,
    val w: Int,
    val h: Int,
    val scale: Float,
    val tree: String,
    val elements: Map<Int, El>,
    val jpeg: ByteArray?,
    val signature: Int,
    val screenshotNote: String?,
    /** The screenshot is meant for the model (taken with the overlay hidden). */
    val shotForModel: Boolean,
)

/**
 * The single switch the user turns on. Reads the screen (accessibility tree + screenshots),
 * performs taps/swipes/text input, and hosts the agent run plus the run overlay (island / panel).
 */
class AgentService : AccessibilityService(), OverlayHost {
    companion object {
        @Volatile var instance: AgentService? = null
        const val MODEL_WIDTH = 720
        private const val MAX_ELEMENTS = 300
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = 0L
    @Volatile var agent: Agent? = null
    /** The last finished run, for follow-ups from the overlay. */
    @Volatile private var lastRun: Agent? = null
    /** The run overlay; it also holds the run conversation that the app shows full screen. */
    var overlay: Overlay? = null
        private set
    private var touches: TouchLayer? = null
    private var voice: Voice? = null
    /** The overlay should be on screen (a run is going or its result is still open). */
    private var overlayWanted = false
    /** Phone Agent's own app is in front: the overlay hides and the agent waits. */
    @Volatile var appOpen = false
        private set
    /** The app's full-screen run view listens here (main thread). */
    var feedListener: (() -> Unit)? = null

    /** Bumped whenever the user does something that makes the agent's current plan stale. */
    val interrupts = AtomicInteger()
    @Volatile var lastInterrupt = ""
        private set
    @Volatile var listening = false
        private set
    @Volatile var typing = false
        private set
    @Volatile private var lastUserTouch = 0L
    /** Until then, touches outside the overlay come from our own gestures. */
    @Volatile private var gestureUntil = 0L

    override fun onServiceConnected() {
        instance = this
        Prefs.init(this)
        Auth.init(this)
        overlay = Overlay(this, this).apply { onChange = { feedListener?.invoke() } }
        touches = TouchLayer(this)
        AgentBus.log("Accessibility service connected")
        AgentBus.changed()
        if (BuildConfig.DEBUG) registerDumpReceiver()
    }

    /**
     * Debug only: `adb shell am broadcast -a dev.phoneagent.DUMP -p dev.phoneagent` saves what the model
     * would see for the current screen (tree + marked screenshot) to files/dumps/, without any AI call.
     */
    private fun registerDumpReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                Thread {
                    val obs = observe(marks = true) { true }
                    val name = i?.getStringExtra("name") ?: java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
                    val dir = java.io.File(getExternalFilesDir("dumps"), name).apply { mkdirs() }
                    java.io.File(dir, "tree.txt").writeText("foreground: ${obs.pkg}\nelements: ${obs.elements.size}\n\n${obs.tree}")
                    obs.jpeg?.let { java.io.File(dir, "hybrid.jpg").writeBytes(it) }
                    AgentBus.log("Dump saved: ${dir.absolutePath} (${obs.elements.size} elements)")
                }.start()
            }
        }
        registerReceiver(receiver, android.content.IntentFilter("dev.phoneagent.DUMP"), RECEIVER_EXPORTED)
        dumpReceiver = receiver
        val voiceTest = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) { voiceTest(i ?: return) }
        }
        registerReceiver(voiceTest, android.content.IntentFilter("dev.phoneagent.VOICE_TEST"), RECEIVER_EXPORTED)
        voiceTestReceiver = voiceTest
    }

    private var voiceTestReceiver: android.content.BroadcastReceiver? = null

    /**
     * Debug only: `am broadcast -a dev.phoneagent.VOICE_TEST -p dev.phoneagent --es text "…" --es tts de-DE --es langs en-US,de-DE`
     * speaks [text] with the phone's text-to-speech into a file and runs speech recognition on it ("automatic" language).
     */
    private fun voiceTest(i: Intent) {
        val text = i.getStringExtra("text") ?: return
        val ttsLang = java.util.Locale.forLanguageTag(i.getStringExtra("tts") ?: "de-DE")
        val langs = i.getStringExtra("langs")?.split(',')?.map { it.trim() }
        val file = java.io.File(cacheDir, "voice-test.wav")
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            AgentBus.log("Voice test: TTS init $status, language ${t.setLanguage(ttsLang)}")
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { AgentBus.log("Voice test: TTS error") }
                override fun onDone(id: String?) {
                    t.shutdown()
                    val bytes = file.readBytes()
                    val rate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    val dataAt = String(bytes, 36, 4).let { if (it == "data") 44 else bytes.size.coerceAtMost(44) }
                    AgentBus.log("Voice test: ${bytes.size} bytes at $rate Hz")
                    // A fresh pipe with the same audio for every attempt (the voice may listen twice).
                    val source = {
                        val (read, write) = android.os.ParcelFileDescriptor.createPipe()
                        Thread {
                            android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                                out.write(bytes, dataAt, bytes.size - dataAt)
                                out.write(ByteArray(rate * 2 * 2))   // 2 s of silence so the recognizer ends
                            }
                        }.start()
                        read to rate
                    }
                    main.post {
                        Voice(this@AgentService,
                            onPartial = { p -> AgentBus.log("Voice test partial: $p") },
                            onFinal = { f -> AgentBus.log("Voice test result: $f") },
                            onEnd = { e -> AgentBus.log("Voice test end: ${e ?: "ok"}") },
                            onRetry = { n -> AgentBus.log("Voice test retry in $n") },
                        ).apply { testAudio = source; testLanguages = langs }.start("")
                    }
                }
            })
            t.synthesizeToFile(text, null, file, "voice-test")
        }
    }

    private var dumpReceiver: android.content.BroadcastReceiver? = null

    override fun onUnbind(intent: Intent?): Boolean {
        dumpReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        dumpReceiver = null
        voiceTestReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        voiceTestReceiver = null
        agent?.stopped = true
        overlay?.hide()
        touches?.hide()
        voice?.cancel()
        instance = null
        AgentBus.changed()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) trackKeyboard()
        if (e.packageName == packageName) return
        lastEventAt = SystemClock.uptimeMillis()
    }

    /** Our overlay sits above the keyboard layer, so it moves up by itself when a keyboard is open. */
    private fun trackKeyboard() {
        val ov = overlay ?: return
        if (!ov.visible) return
        val ime = try { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (_: Exception) { null }
        val inset = if (ime != null) {
            val r = Rect()
            ime.getBoundsInScreen(r)
            (getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height() - r.top).coerceAtLeast(0)
        } else 0
        main.post { ov.setImeInset(inset) }
    }

    // ---------------------------------------------------------------- agent run

    /** Runs [block] on the overlay on the main thread. */
    fun ui(block: Overlay.() -> Unit) { main.post { overlay?.block() } }

    /**
     * Starts a run. A [followUp] continues from the screen as it is (no Home press) and gives the model the previous
     * task and result; the overlay then keeps the conversation.
     */
    fun startAgent(task: String, mode: Mode, model: String, effort: String, followUp: Agent? = null, spoken: Boolean = false): Boolean {
        if (agent != null) return false
        val context = followUp?.let { "Earlier task: \"${it.task}\" → result: \"${it.result ?: "?"}\"" }
        val a = Agent(this, task, mode, model, effort, Prefs.askFirst, context, goHome = followUp == null)
        agent = a
        lastRun = null
        interrupts.incrementAndGet()
        main.post {
            val ov = overlay ?: return@post
            if (followUp == null) { ov.clear(); ov.collapse() }
            ov.setTask(task)
            ov.add(FeedItem(if (followUp == null) FeedItem.Kind.TASK else FeedItem.Kind.USER, task, spoken = spoken))
            overlayWanted = true
            if (!appOpen) ov.show()
            touches?.show()
        }
        Thread {
            val answer = try { a.run() } catch (e: Exception) {
                AgentBus.log("✖ $e")
                ui { add(FeedItem(FeedItem.Kind.RESULT, "Error: ${e.message}", options = listOf("Didn't finish"), ok = false)); setPhase(Phase.FAILED, "Error"); expand() }
                "Error: ${e.message}"
            }
            AgentBus.lastAnswer = answer
            lastRun = a
            agent = null
            main.post {
                // Seen in the app already: don't pop the result up again when the user leaves it.
                if (appOpen) overlayWanted = false
                main.postDelayed({ if (agent == null) touches?.hide() }, 1200)
            }
            AgentBus.changed()
        }.start()
        AgentBus.changed()
        return true
    }

    fun stopAgent() {
        val a = agent ?: return
        a.stopped = true
        ui { setStatus("Stopping…") }
    }

    fun interrupt(reason: String) {
        lastInterrupt = reason
        interrupts.incrementAndGet()
    }

    fun userTouching() = SystemClock.uptimeMillis() - lastUserTouch < 3000

    // ---------------------------------------------------------------- overlay callbacks (main thread)

    override fun onPauseTap() { agent?.togglePause() }

    override fun onStopTap() { if (agent != null) stopAgent() else onClose() }

    override fun onClose() {
        voice?.cancel()
        overlayWanted = false
        overlay?.hide()
        overlay?.clear()
        lastRun = null
    }

    /** "Open full screen" in the panel: the app shows the run; while it is in front the agent waits. */
    override fun onOpenApp() {
        if (agent == null) overlayWanted = false
        try {
            startActivity(Intent(this, MainActivity::class.java).putExtra("live", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { AgentBus.log("Can't open the app: $e") }
    }

    /** Called by the app when it comes to the front or leaves (main thread). */
    fun setAppOpen(open: Boolean) {
        if (appOpen == open) return
        appOpen = open
        val ov = overlay ?: return
        if (open) {
            ov.hide()
            if (agent != null) interrupt("the user opened the Phone Agent app")
        } else if (overlayWanted) {
            ov.show()
        }
    }

    /** A message typed or spoken in the app's run view. */
    fun sendMessage(text: String, spoken: Boolean) = deliver(text, spoken)

    /** "New task" in the app: closes the finished run. */
    fun closeRun() { if (agent == null) onClose() }

    override fun onMicTap() {
        val v = voice ?: Voice(this,
            onPartial = { t -> overlay?.setListening(true, t) },
            onFinal = { t -> overlay?.clearInput(); deliver(t, spoken = true) },
            onEnd = { err ->
                listening = false
                overlay?.setListening(false)
                if (err != null && err != "cancelled") overlay?.add(FeedItem(FeedItem.Kind.NOTE, err))
            },
            onRetry = { name -> overlay?.setListening(true, "", "Say it again in $name…") },
        ).also { voice = it }
        if (v.active) { v.stop(); return }
        if (!Voice.hasPermission(this)) {
            overlay?.add(FeedItem(FeedItem.Kind.NOTE, "Allow the microphone in the Phone Agent app (Settings → Microphone) to talk to the agent."))
            overlay?.expand()
            return
        }
        listening = true
        interrupt("the user started speaking")
        overlay?.setListening(true)
        v.start(Prefs.voiceLang)
    }

    override fun onSend(text: String) = deliver(text, spoken = false)

    override fun onAnswer(item: FeedItem, answer: String) { agent?.answer(item, answer) }

    override fun onOutsideTouch() {
        val now = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("PhoneAgent", "outside touch (agent=${agent != null}, ownGesture=${now < gestureUntil})")
        if (agent == null || now < gestureUntil) return
        lastUserTouch = now
        interrupt("the user touched the screen")
    }

    override fun onTyping(active: Boolean) {
        typing = active
        if (active) interrupt("the user started typing a message")
    }

    /** A typed or spoken message: steers the running agent, or starts a follow-up after a finished run. */
    private fun deliver(text: String, spoken: Boolean) {
        val a = agent
        if (a != null) { a.say(text, spoken); return }
        val model = Prefs.currentModel()
        if (model == null || !Auth.signedIn) { overlay?.add(FeedItem(FeedItem.Kind.NOTE, "Sign in in the Phone Agent app first.")); return }
        startAgent(text, Prefs.mode, model, Prefs.effort, followUp = lastRun, spoken = spoken)
    }

    private val labels = HashMap<String, String>()

    /** "Clock", "Home screen", … for a package name. */
    fun appLabel(pkg: String): String {
        if (pkg.isEmpty()) return "?"
        synchronized(labels) { labels[pkg] }?.let { return it }
        val pm = packageManager
        val home = try { pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName } catch (_: Exception) { null }
        val l = when {
            pkg == home -> "Home screen"
            pkg == "com.android.systemui" -> "System"
            else -> try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) {
                pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
        synchronized(labels) { labels[pkg] = l }
        return l
    }

    /** Runs [block] on the main thread and waits for its result (max 1 s). */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var r: T? = null
        val latch = CountDownLatch(1)
        main.post { try { r = block() } finally { latch.countDown() } }
        latch.await(1, TimeUnit.SECONDS)
        return r
    }

    /** Waits until the UI stopped changing (no accessibility events for [quietMs]), at least [minMs], at most [maxMs]. */
    fun settle(maxMs: Long = 3000, quietMs: Long = 500, minMs: Long = 350) {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(minMs)
        while (SystemClock.uptimeMillis() - lastEventAt < quietMs && SystemClock.uptimeMillis() - start < maxMs) Thread.sleep(60)
    }

    /** The current screen's elements (screen pixels), without a screenshot. For script steps. */
    fun elements(): Map<Int, El> {
        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return buildTree(1f, screen.width().toLong() * screen.height()).second
    }

    /** Signature of the current screen, to see whether an action changed anything. */
    fun screenSignature(): Int {
        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val (tree, _, pkg) = buildTree(1f, screen.width().toLong() * screen.height())
        return (pkg + tree).hashCode()
    }

    // ---------------------------------------------------------------- observe

    /**
     * Reads the screen. [wantShot] decides from the element list whether the model gets a screenshot this step;
     * if so the overlay is hidden while it is taken. A screenshot is still taken every step for the run history.
     */
    fun observe(marks: Boolean, wantShot: (Map<Int, El>) -> Boolean): Observation {
        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val scale0 = if (screen.width() > MODEL_WIDTH) MODEL_WIDTH.toFloat() / screen.width() else 1f
        var t = buildTree(scale0, screen.width().toLong() * screen.height())
        if (sawEmptyWebView) {
            // WebViews (Chrome, Capacitor/Cordova apps …) fill their accessibility tree lazily on the first request.
            Thread.sleep(800)
            t = buildTree(scale0, screen.width().toLong() * screen.height())
        }
        val (tree, els, fgPkg) = t
        val forModel = wantShot(els)
        val full = if (forModel) captureForModel() else screenshot()
        var shotNote: String? = null
        if (full == null) shotNote = "Screenshot not available (protected screen?)"
        val screenW = full?.width ?: screen.width()
        val screenH = full?.height ?: screen.height()
        val scale = if (screenW > MODEL_WIDTH) MODEL_WIDTH.toFloat() / screenW else 1f
        val w = (screenW * scale).toInt()
        val h = (screenH * scale).toInt()

        var jpeg: ByteArray? = null
        if (full != null) {
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(full, w, h, true) else full
            val bmp = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (marks && forModel) drawMarks(bmp, els.values, scale)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
            jpeg = bos.toByteArray()
        }
        return Observation(fgPkg, w, h, scale, tree, els, jpeg, (fgPkg + tree).hashCode(), shotNote, forModel && jpeg != null)
    }

    /** Screenshot without our overlay and touch effects on it. */
    private fun captureForModel(): Bitmap? {
        val latch = CountDownLatch(1)
        main.post {
            val ov = overlay
            val hideOverlay = { if (ov != null && ov.visible) ov.hideForCapture { latch.countDown() } else latch.countDown() }
            val tl = touches
            if (tl != null) tl.clearForCapture(hideOverlay) else hideOverlay()
        }
        latch.await(700, TimeUnit.MILLISECONDS)
        try {
            return screenshot()
        } finally {
            main.post { overlay?.restoreAfterCapture() }
        }
    }

    @Volatile private var sawEmptyWebView = false
    private var walkingApp = false

    private fun buildTree(scale: Float, screenArea: Long): Triple<String, LinkedHashMap<Int, El>, String> {
        sawEmptyWebView = false
        val els = LinkedHashMap<Int, El>()
        val sb = StringBuilder()
        var fgPkg = ""
        val wins = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        for (win in wins.sortedByDescending { it.layer }) {
            val root = win.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == packageName) continue   // our own overlay
            val kind = when (win.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "Keyboard"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "System"
                AccessibilityWindowInfo.TYPE_APPLICATION -> "App"
                else -> "Window"
            }
            if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION && fgPkg.isEmpty()) fgPkg = pkg
            sb.append("== ").append(kind).append(": ").append(pkg)
            win.title?.let { if (it.isNotBlank()) sb.append(" \"").append(it.toString().take(60)).append('"') }
            sb.append(" ==\n")
            walkingApp = win.type == AccessibilityWindowInfo.TYPE_APPLICATION
            walk(root, sb, els, scale, false, screenArea)
        }
        if (els.size >= MAX_ELEMENTS) sb.append("(list truncated)\n")
        return Triple(sb.toString(), els, fgPkg)
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder, els: MutableMap<Int, El>, scale: Float, insideLabeled: Boolean, screenArea: Long) {
        if (els.size >= MAX_ELEMENTS || !n.isVisibleToUser) return
        val r = Rect()
        n.getBoundsInScreen(r)
        if (n.childCount == 0 && n.className?.contains("WebView") == true) sawEmptyWebView = true
        val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || n.isScrollable
        var label = ownLabel(n)
        var childrenInside = insideLabeled
        val small = r.width().toLong() * r.height() < screenArea / 4
        if (interactive && !n.isScrollable && small) {
            if (label.isEmpty()) label = descendantText(n, 3)
            // Texts inside a small labeled button/row are already part of its label.
            if (label.isNotEmpty()) childrenInside = true
        }
        if (label.isEmpty() && interactive) n.viewIdResourceName?.let { label = "#" + it.substringAfter(":id/") }
        val show = !r.isEmpty && (interactive || (label.isNotEmpty() && !insideLabeled))
        if (show) {
            val id = els.size + 1
            els[id] = El(id, n, Rect(r), label, interactive, walkingApp)
            sb.append('[').append(id).append("] ").append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
            if (n.isEditable) {
                n.hintText?.let { if (it.isNotBlank() && it.toString() != label) sb.append(" hint=\"").append(clean(it, 40)).append('"') }
            }
            val flags = ArrayList<String>(6)
            if (n.isClickable) flags += "click"
            if (n.isLongClickable) flags += "long"
            if (n.isEditable) flags += "edit"
            if (n.isCheckable) flags += if (n.isChecked) "checked" else "unchecked"
            if (n.isScrollable) flags += "scroll"
            if (n.isFocused) flags += "focused"
            if (n.isSelected) flags += "selected"
            if (!n.isEnabled) flags += "disabled"
            n.stateDescription?.let { if (it.isNotBlank()) flags += "state=" + clean(it, 30) }
            if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(","))
            sb.append(" @(").append((r.centerX() * scale).toInt()).append(',').append((r.centerY() * scale).toInt()).append(')')
            if (n.isScrollable) sb.append(" size ").append((r.width() * scale).toInt()).append('x').append((r.height() * scale).toInt())
            sb.append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb, els, scale, childrenInside, screenArea)
        }
    }

    private fun ownLabel(n: AccessibilityNodeInfo): String {
        val t = n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() }
        return if (t != null) clean(t, 100) else ""
    }

    private fun descendantText(n: AccessibilityNodeInfo, max: Int): String {
        val out = ArrayList<String>()
        fun rec(x: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= max || depth > 6) return
            for (i in 0 until x.childCount) {
                val c = x.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                val l = ownLabel(c)
                if (l.isNotEmpty()) out += l
                if (out.size >= max) return
                rec(c, depth + 1)
            }
        }
        rec(n, 0)
        return clean(out.joinToString(" | "), 100)
    }

    private fun clean(s: CharSequence, max: Int): String =
        s.toString().replace('\n', ' ').replace('"', '\'').trim().let { if (it.length > max) it.take(max) + "…" else it }

    private fun drawMarks(bmp: Bitmap, els: Collection<El>, scale: Float) {
        val c = Canvas(bmp)
        val colors = intArrayOf(Color.rgb(230, 25, 75), Color.rgb(60, 140, 60), Color.rgb(0, 100, 220), Color.rgb(200, 110, 0), Color.rgb(145, 30, 180))
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val text = Paint().apply { color = Color.WHITE; textSize = 15f; isAntiAlias = true; isFakeBoldText = true }
        for (e in els) {
            if (!e.interactive) continue
            val col = colors[e.id % colors.size]
            val r = RectF(e.rect.left * scale, e.rect.top * scale, e.rect.right * scale, e.rect.bottom * scale)
            stroke.color = col
            c.drawRect(r, stroke)
            val label = e.id.toString()
            val tw = text.measureText(label)
            fill.color = col
            c.drawRect(r.left, r.top, r.left + tw + 6, r.top + 18, fill)
            c.drawText(label, r.left + 3, r.top + 14, text)
        }
    }

    private fun screenshot(): Bitmap? {
        repeat(2) { attempt ->
            val latch = CountDownLatch(1)
            var bmp: Bitmap? = null
            var err = 0
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, true)
                        hb.close()
                    } catch (_: Exception) {}
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { err = errorCode; latch.countDown() }
            })
            latch.await(4, TimeUnit.SECONDS)
            if (bmp != null) return bmp
            // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (3): Android allows ~3 screenshots per second.
            if (err == 3 && attempt == 0) Thread.sleep(400) else return null
        }
        return null
    }

    // ---------------------------------------------------------------- act

    private fun gesture(path: Path, durationMs: Long): Boolean {
        // If the gesture lands on our overlay, the overlay ignores touches meanwhile so the app below gets it.
        val area = RectF().also { path.computeBounds(it, true) }
        val ov = overlay
        val covered = ov != null && onMain {
            ov.bounds()?.let { RectF(it).apply { inset(-8f, -8f) }.intersects(area.left, area.top, area.right + 1, area.bottom + 1) }
        } == true
        if (covered) { onMain { ov?.setPassThrough(true) }; Thread.sleep(90) }
        gestureUntil = SystemClock.uptimeMillis() + durationMs + 800
        try {
            val latch = CountDownLatch(1)
            var ok = false
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val posted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!posted) return false
            latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
            return ok
        } finally {
            gestureUntil = SystemClock.uptimeMillis() + 300
            if (covered) main.postDelayed({ ov?.setPassThrough(false) }, 150)
        }
    }

    fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        fx { tap(x, y, durationMs) }
        return gesture(Path().apply { moveTo(x, y) }, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        fx { swipe(x1, y1, x2, y2, durationMs) }
        return gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)
    }

    /** Top edge of the overlay on screen, or Int.MAX_VALUE when it is hidden. */
    fun overlayTop(): Int = onMain { overlay?.bounds()?.top } ?: Int.MAX_VALUE

    /** Shows a touch effect (main thread, fire and forget). */
    fun fx(block: TouchLayer.() -> Unit) { main.post { touches?.block() } }

    fun click(node: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        var c: AccessibilityNodeInfo? = node
        while (c != null) {
            if ((if (long) c.isLongClickable else c.isClickable) && c.isEnabled) {
                val r = Rect()
                c.getBoundsInScreen(r)
                fx { tap(r.exactCenterX(), r.exactCenterY(), if (long) 600 else 60) }
                return c.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
            }
            c = c.parent
        }
        return false
    }

    fun focusedInput(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

    fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val latch = CountDownLatch(1)
        main.post {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("agent", text))
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun imeEnter(node: AccessibilityNodeInfo?): Boolean =
        (node ?: focusedInput())?.performAction(AccessibilityAction.ACTION_IME_ENTER.id) ?: false

    fun scrollNode(node: AccessibilityNodeInfo, direction: String): Boolean {
        val act = when (direction) {
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> AccessibilityAction.ACTION_SCROLL_DOWN
        }
        if (node.performAction(act.id)) return true
        val forward = direction == "down" || direction == "right"
        return node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun openApp(name: String): String {
        val pm = packageManager
        val q = name.trim().lowercase()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
        var best: android.content.pm.ResolveInfo? = null
        var bestScore = 0
        for (ri in apps) {
            val l = ri.loadLabel(pm).toString().lowercase()
            val p = ri.activityInfo.packageName.lowercase()
            val s = when {
                l == q || p == q -> 4
                l.startsWith(q) -> 3
                l.contains(q) -> 2
                q.contains(l) && l.length > 2 -> 1
                else -> 0
            }
            if (s > bestScore) { best = ri; bestScore = s }
        }
        val b = best ?: return "no app named '$name'. Installed: " +
            apps.map { it.loadLabel(pm).toString() }.sorted().joinToString(", ").take(900)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(b.activityInfo.packageName, b.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return "opened ${b.loadLabel(pm)} (${b.activityInfo.packageName})"
    }
}

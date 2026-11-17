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
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
)

/**
 * The single switch the user turns on. Reads the screen (accessibility tree + screenshots),
 * performs taps/swipes/text input, and hosts the agent run plus a small status overlay with a stop button.
 */
class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
        const val MODEL_WIDTH = 720
        private const val MAX_ELEMENTS = 300
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = 0L
    @Volatile var agent: Agent? = null
    private var overlayRoot: View? = null
    private var overlayText: TextView? = null

    override fun onServiceConnected() {
        instance = this
        AgentBus.log("Bedienungshilfe verbunden")
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
                    val obs = observe(marks = true)
                    val name = i?.getStringExtra("name") ?: java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
                    val dir = java.io.File(getExternalFilesDir("dumps"), name).apply { mkdirs() }
                    java.io.File(dir, "tree.txt").writeText("foreground: ${obs.pkg}\nelements: ${obs.elements.size}\n\n${obs.tree}")
                    obs.jpeg?.let { java.io.File(dir, "hybrid.jpg").writeBytes(it) }
                    AgentBus.log("Dump gespeichert: ${dir.absolutePath} (${obs.elements.size} Elemente)")
                }.start()
            }
        }
        registerReceiver(receiver, android.content.IntentFilter("dev.phoneagent.DUMP"), RECEIVER_EXPORTED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
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
        if (e == null || e.packageName == packageName) return
        lastEventAt = SystemClock.uptimeMillis()
    }

    // ---------------------------------------------------------------- agent run

    fun startAgent(task: String, mode: Mode, model: String, effort: String): Boolean {
        if (agent != null) return false
        val a = Agent(this, task, mode, model, effort)
        agent = a
        main.post { showOverlay() }
        Thread {
            val answer = try { a.run() } catch (e: Exception) { AgentBus.log("✖ $e"); "Fehler: ${e.message}" }
            AgentBus.lastAnswer = answer
            agent = null
            main.post { hideOverlay() }
            AgentBus.changed()
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("show_answer", true))
            } catch (_: Exception) {}
        }.start()
        AgentBus.changed()
        return true
    }

    fun stopAgent() {
        agent?.stopped = true
        overlay("Stoppe…")
    }

    /** Waits until the UI stopped changing (no accessibility events for 500 ms), max [maxMs]. */
    fun settle(maxMs: Long = 3000) {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(350)
        while (SystemClock.uptimeMillis() - lastEventAt < 500 && SystemClock.uptimeMillis() - start < maxMs) Thread.sleep(100)
    }

    // ---------------------------------------------------------------- observe

    fun observe(marks: Boolean): Observation {
        var shotNote: String? = null
        val full = screenshot()
        val screen = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val screenW = full?.width ?: screen.width()
        val screenH = full?.height ?: screen.height()
        if (full == null) shotNote = "Screenshot nicht verfügbar (geschützter Bildschirm?)"
        val scale = if (screenW > MODEL_WIDTH) MODEL_WIDTH.toFloat() / screenW else 1f
        val w = (screenW * scale).toInt()
        val h = (screenH * scale).toInt()

        var t = buildTree(scale, screenW.toLong() * screenH)
        if (sawEmptyWebView) {
            // WebViews (Chrome, Capacitor/Cordova apps …) fill their accessibility tree lazily on the first request.
            Thread.sleep(800)
            t = buildTree(scale, screenW.toLong() * screenH)
        }
        val (tree, els, fgPkg) = t

        var jpeg: ByteArray? = null
        if (full != null) {
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(full, w, h, true) else full
            val bmp = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
            if (marks) drawMarks(bmp, els.values, scale)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
            jpeg = bos.toByteArray()
        }
        return Observation(fgPkg, w, h, scale, tree, els, jpeg, (fgPkg + tree).hashCode(), shotNote)
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
    }

    fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean = gesture(Path().apply { moveTo(x, y) }, durationMs)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    fun click(node: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        var c: AccessibilityNodeInfo? = node
        while (c != null) {
            if ((if (long) c.isLongClickable else c.isClickable) && c.isEnabled) {
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

    // ---------------------------------------------------------------- overlay

    private fun showOverlay() {
        if (overlayRoot != null) return
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xDD202124.toInt()); cornerRadius = 16 * dp }
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
        }
        val statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; maxWidth = (120 * dp).toInt(); isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END; text = "Phone Agent"
        }
        val stopView = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt()); textSize = 12f; text = "  ■ Stop"
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { stopAgent() }
        }
        root.addView(statusView)
        root.addView(stopView)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Sit inside the status bar (next to the clock/battery) so it never covers app buttons.
            gravity = Gravity.TOP or Gravity.END
            x = (4 * dp).toInt()
            y = (6 * dp).toInt()
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try { wm.addView(root, lp); overlayRoot = root; overlayText = statusView } catch (e: Exception) { AgentBus.log("Overlay-Fehler: $e") }
    }

    private fun hideOverlay() {
        overlayRoot?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        overlayRoot = null
        overlayText = null
    }

    fun overlay(status: String) { main.post { overlayText?.text = status } }
}

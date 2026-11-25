package dev.phoneagent

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** What the overlay reports back to the service. */
interface OverlayHost {
    fun onPauseTap()
    fun onStopTap()
    fun onMicTap()
    fun onSend(text: String)
    fun onAnswer(item: FeedItem, answer: String)
    fun onClose()
    fun onOpenApp()
    fun onOutsideTouch()
    fun onTyping(active: Boolean)
}

/**
 * The run overlay. Collapsed it is a small island at the bottom (what the agent does now, plus expand, pause and stop);
 * expanded it is a chat-style panel with the conversation, questions, confirmations and a text/voice input.
 * Touches outside it pass through to the phone, so the user can always tap things themselves.
 * It also holds the run conversation, which the app shows full screen.
 */
class Overlay(private val ctx: Context, private val host: OverlayHost) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val imm = ctx.getSystemService(InputMethodManager::class.java)
    private var root: Root? = null
    private lateinit var wlp: WindowManager.LayoutParams

    var expanded = false
        private set
    var phase = Phase.WORKING
        private set
    var status = ""
        private set
    /** The task (or latest follow-up) the run works on. */
    var task = ""
        private set
    var listening = false
        private set
    private var transcript = ""
    private var listenHint = "Listening…"
    private var imeInset = 0
    private var inputFocus = false
    private val _items = ArrayList<FeedItem>()
    val items: List<FeedItem> get() = _items

    /** Called after every change of the conversation or the status (main thread). */
    var onChange: (() -> Unit)? = null

    // collapsed
    private lateinit var pill: DragFrame
    private lateinit var pillDot: StatusDot
    private lateinit var pillText: TextView
    private lateinit var pillPause: ImageView
    private lateinit var pillStop: ImageView

    // expanded
    private lateinit var panel: LinearLayout
    private lateinit var headDot: StatusDot
    private lateinit var headTitle: TextView
    private lateinit var headSub: TextView
    private lateinit var headPause: ImageView
    private lateinit var headStop: ImageView
    private lateinit var scroll: MaxScroll
    private lateinit var feed: FeedView
    private lateinit var field: EditText
    private lateinit var action: ImageView

    val visible get() = root != null

    // ------------------------------------------------------------------ window

    fun show() {
        if (root != null) return
        val r = Root(ctx)
        buildPill(r)
        buildPanel(r)
        wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "Phone Agent"
        }
        place()
        try { wm.addView(r, wlp); root = r } catch (e: Exception) { AgentBus.log("Overlay error: $e"); return }
        feed.maxBubble = (wlp.width * 0.8f).toInt()
        feed.sync(_items)
        apply()
        scrollToEnd()
    }

    fun hide() {
        setInputFocus(false)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
    }

    /** Width, offset above the navigation bar / keyboard, panel height. */
    private fun place() {
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets
        val nav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
        val top = ins.getInsets(WindowInsets.Type.statusBars()).top
        val margin = ctx.dp(10)
        wlp.width = b.width() - 2 * margin
        wlp.y = maxOf(nav, imeInset) + margin
        val room = b.height() - wlp.y - top - ctx.dp(12)
        if (::scroll.isInitialized) scroll.maxPx = (minOf((b.height() * 0.56f).toInt(), room) - ctx.dp(130)).coerceAtLeast(ctx.dp(120))
    }

    private fun relayout() {
        val r = root ?: return
        place()
        try { wm.updateViewLayout(r, wlp) } catch (_: Exception) {}
    }

    /** Keyboard height from the accessibility window list (our window sits above the keyboard layer, so we move ourselves). */
    fun setImeInset(px: Int) {
        if (px == imeInset) return
        imeInset = px
        relayout()
    }

    /** Lets the agent's own gestures go through the overlay to the app below. */
    fun setPassThrough(on: Boolean) {
        val r = root ?: return
        val f = if (on) wlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else wlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (f == wlp.flags) return
        wlp.flags = f
        try { wm.updateViewLayout(r, wlp) } catch (_: Exception) {}
    }

    /** Hides the overlay for a screenshot that goes to the model; [ready] runs once the hidden frame is on screen. */
    fun hideForCapture(ready: () -> Unit) {
        val r = root
        if (r == null || r.alpha == 0f) { ready(); return }
        r.animate().cancel()
        r.alpha = 0f
        r.viewTreeObserver.registerFrameCommitCallback { r.postDelayed({ ready() }, 48) }
        r.invalidate()
    }

    fun restoreAfterCapture() {
        root?.animate()?.alpha(1f)?.setDuration(160)?.start()
    }

    /** On-screen rectangle of the overlay window, or null when hidden. */
    fun bounds(): Rect? {
        val r = root ?: return null
        if (r.width == 0) return null
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + r.width, loc[1] + r.height)
    }

    private fun setInputFocus(on: Boolean) {
        if (inputFocus == on) return
        inputFocus = on
        val r = root
        if (r != null) {
            wlp.flags = if (on) wlp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else wlp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try { wm.updateViewLayout(r, wlp) } catch (_: Exception) {}
            if (on) {
                r.postDelayed({ field.requestFocus(); imm.showSoftInput(field, 0) }, 120)
            } else {
                imm.hideSoftInputFromWindow(r.windowToken, 0)
                if (::field.isInitialized) field.clearFocus()
            }
        }
        host.onTyping(on)
    }

    // ------------------------------------------------------------------ state

    /** True when the panel opened by itself (question, result); it folds back after the answer. */
    private var autoOpened = false

    /** Opens the panel for a question or result; [collapseAuto] folds it back if the user hadn't opened it. */
    fun expandAuto() {
        if (expanded) return
        expand()
        autoOpened = true
    }

    fun collapseAuto() {
        if (autoOpened && !inputFocus) collapse()
        autoOpened = false
    }

    fun expand() {
        autoOpened = false
        if (expanded) return
        expanded = true
        apply()
        root?.let { panel.alpha = 0f; panel.translationY = ctx.dp(24).toFloat(); panel.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        relayout()
        scrollToEnd()
    }

    fun collapse() {
        autoOpened = false
        if (!expanded) return
        setInputFocus(false)
        expanded = false
        apply()
        relayout()
    }

    fun setTask(text: String) {
        task = text
        apply()
    }

    fun setPhase(p: Phase, statusText: String) {
        phase = p
        status = statusText
        apply()
    }

    fun setStatus(statusText: String) {
        status = statusText
        apply()
    }

    fun setListening(on: Boolean, text: String = "", hint: String = "Listening…") {
        listening = on
        transcript = text
        listenHint = hint
        apply()
    }

    fun clear() {
        _items.clear()
        task = ""
        if (root != null) feed.sync(_items)
        changed()
    }

    fun add(item: FeedItem) {
        _items += item
        if (_items.size > 250) _items.removeAt(0)
        if (root != null) { feed.sync(_items); scrollToEnd() }
        changed()
    }

    /** Re-renders an item after its answer changed. */
    fun update(item: FeedItem) {
        item.rev++
        if (root != null) feed.sync(_items)
        changed()
    }

    fun buzz() { root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    private fun changed() { onChange?.invoke() }

    /** Headline for the current state, shared with the app's run view. */
    fun title(): String = when (phase) {
        Phase.DONE -> "Done"
        Phase.FAILED -> status.takeIf { it.startsWith("Stopped") }?.let { "Stopped" } ?: "Didn't finish"
        Phase.PAUSED -> "Paused"
        Phase.ASKING -> "Needs your answer"
        else -> status.ifEmpty { "Working…" }
    }

    private fun apply() {
        changed()
        if (root == null) return
        pill.visibility = if (expanded) View.GONE else View.VISIBLE
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        val dotColor = if (listening) C.BAD else phase.color
        val pulse = listening || phase.pulse
        for (d in listOf(pillDot, headDot)) { d.color = dotColor; d.pulse = pulse }
        pillText.text = if (listening) transcript.ifEmpty { listenHint } else status
        pillText.ellipsize = if (listening) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
        pillText.setTextColor(if (listening && transcript.isEmpty()) C.SUB else C.TXT)
        val running = phase.running
        for (b in listOf(pillPause, headPause)) {
            b.visibility = if (running && phase != Phase.ASKING) View.VISIBLE else View.GONE
            b.setImageDrawable(Icon(if (phase == Phase.PAUSED) "play" else "pause", C.TXT))
            b.contentDescription = if (phase == Phase.PAUSED) "Continue" else "Pause"
        }
        for (b in listOf(pillStop, headStop)) {
            b.setImageDrawable(Icon(if (running) "stop" else "close", if (running) C.BAD else C.TXT))
            b.background = ctx.pressable(if (running) 0x26FF6B6B else 0x14FFFFFF, 20)
            b.contentDescription = if (running) "Stop" else "Close"
        }
        headTitle.text = if (listening) listenHint else title()
        headSub.text = task
        headSub.visibility = if (task.isEmpty()) View.GONE else View.VISIBLE
        field.hint = when {
            listening -> listenHint
            !running -> "Follow up, e.g. “now do the same for…”"
            phase == Phase.ASKING -> if (_items.lastOrNull { it.kind == FeedItem.Kind.QUESTION || it.kind == FeedItem.Kind.CONFIRM }?.options?.isNotEmpty() == true)
                "Pick an option, or type your answer" else "Type your answer, or tap the mic"
            else -> "Tell the agent something…"
        }
        if (listening && transcript.isNotEmpty()) { field.setText(transcript); field.setSelection(field.text.length) }
        updateAction()
    }

    private fun updateAction() {
        val typing = field.text.isNotBlank() && !listening
        action.setImageDrawable(Icon(if (typing) "send" else "mic", if (listening) 0xFFFFFFFF.toInt() else C.ON_ACCENT))
        action.background = ctx.pressable(if (listening) C.BAD else C.ACCENT, 23)
        action.contentDescription = if (typing) "Send" else "Speak"
    }

    private fun scrollToEnd() {
        if (root == null) return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ views

    private fun glass() = GradientDrawable().apply {
        setColor(C.GLASS)
        cornerRadius = ctx.dp(26).toFloat()
        setStroke(ctx.dp(1), 0x1FFFFFFF)
    }

    private fun buildPill(r: Root) {
        pill = DragFrame(ctx, onUp = { expand() }, onDown = {}, onTap = { expand() }).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            pillDot = StatusDot(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)) }
            pillText = ctx.text("", 15f, C.TXT, 500, 1)
            val open = ctx.iconButton("up") { expand() }.apply { contentDescription = "Show details" }
            pillPause = ctx.iconButton("pause") { host.onPauseTap() }
            pillStop = ctx.iconButton("stop", C.BAD, 0x26FF6B6B) { host.onStopTap() }
            addView(pillDot)
            addView(pillText, lp(0, -2, 1f, left = ctx.dp(8), right = ctx.dp(6)))
            addView(open)
            addView(pillPause, lp(ctx.dp(40), ctx.dp(40), left = ctx.dp(6)))
            addView(pillStop, lp(ctx.dp(40), ctx.dp(40), left = ctx.dp(6)))
        }
        r.addView(pill, FrameLayout.LayoutParams(-1, -2))
    }

    private fun buildPanel(r: Root) {
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = glass() }

        val head = DragFrame(ctx, onUp = {}, onDown = { collapse() }, onTap = null).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(8), ctx.dp(10))
        }
        headDot = StatusDot(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)) }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        headTitle = ctx.text("", 15f, C.TXT, 650, 1)
        headSub = ctx.text("", 12f, C.SUB, 400, 1)
        titles.addView(headTitle)
        titles.addView(headSub)
        val full = ctx.iconButton("full", sizeDp = 36, iconDp = 18) { host.onOpenApp() }.apply { contentDescription = "Open full screen" }
        val down = ctx.iconButton("down", sizeDp = 36) { collapse() }.apply { contentDescription = "Minimize" }
        headPause = ctx.iconButton("pause", sizeDp = 36) { host.onPauseTap() }
        headStop = ctx.iconButton("stop", C.BAD, 0x26FF6B6B, sizeDp = 36) { host.onStopTap() }
        head.addView(headDot)
        head.addView(titles, lp(0, -2, 1f, left = ctx.dp(8), right = ctx.dp(4)))
        head.addView(full)
        head.addView(down, lp(ctx.dp(36), ctx.dp(36), left = ctx.dp(5)))
        head.addView(headPause, lp(ctx.dp(36), ctx.dp(36), left = ctx.dp(5)))
        head.addView(headStop, lp(ctx.dp(36), ctx.dp(36), left = ctx.dp(5)))
        panel.addView(head)
        panel.addView(View(ctx).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, ctx.dp(1)))

        feed = FeedView(ctx) { item, a -> host.onAnswer(item, a) }.apply { setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(8)) }
        scroll = MaxScroll(ctx).apply { addView(feed); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val input = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(10))
        }
        field = EditText(ctx).apply {
            textSize = 14.5f
            setTextColor(C.TXT)
            setHintTextColor(C.MUTE)
            background = round(0x12FFFFFF, ctx.dp(23).toFloat(), ctx.dp(1), C.LINE)
            setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(14), ctx.dp(11))
            minHeight = ctx.dp(46)
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) setInputFocus(true); false }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateAction() }
            })
        }
        action = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            val p = ctx.dp(12)
            setPadding(p, p, p, p)
            setOnClickListener { if (field.text.isNotBlank() && !listening) send() else host.onMicTap() }
        }
        input.addView(field, lp(0, -2, 1f))
        input.addView(action, lp(ctx.dp(46), ctx.dp(46), left = ctx.dp(8)))
        panel.addView(input)
        panel.visibility = View.GONE
        r.addView(panel, FrameLayout.LayoutParams(-1, -2))
    }

    private fun send() {
        val t = field.text.toString().trim()
        if (t.isEmpty()) return
        field.setText("")
        setInputFocus(false)
        host.onSend(t)
    }

    fun clearInput() { if (root != null) field.setText("") }

    // ------------------------------------------------------------------ helper views

    /** Window root: reports touches outside the overlay and handles Back while typing. */
    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (inputFocus) setInputFocus(false)
                host.onOutsideTouch()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) { if (inputFocus) setInputFocus(false) else collapse() }
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    /** Vertical swipe detector: swipe up / down / tap, without stealing taps from the buttons inside. */
    private class DragFrame(c: Context, val onUp: () -> Unit, val onDown: () -> Unit, val onTap: (() -> Unit)?) : LinearLayout(c) {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(c).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (abs(dy) > slop && abs(dy) > abs(ev.rawX - downX)) { dragging = true; return true }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> if (abs(ev.rawY - downY) > slop) dragging = true
                MotionEvent.ACTION_UP -> {
                    val dy = ev.rawY - downY
                    val t = dp(20)
                    when {
                        dragging && dy < -t -> onUp()
                        dragging && dy > t -> onDown()
                        !dragging -> onTap?.invoke()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    /** The feed: a fixed height, so the panel's header and buttons don't move while entries come in. */
    private class MaxScroll(c: Context) : ScrollView(c) {
        var maxPx = Int.MAX_VALUE
            set(v) { field = v; requestLayout() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.EXACTLY))
        }
    }
}

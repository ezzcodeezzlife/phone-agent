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

/** One entry of the run conversation shown in the expanded overlay. */
class FeedItem(
    val kind: Kind,
    val text: String,
    val sub: String = "",
    val options: List<String> = emptyList(),
    val spoken: Boolean = false,
    val ok: Boolean = true,
) {
    enum class Kind { TASK, USER, ACTION, AGENT, QUESTION, CONFIRM, RESULT, NOTE }

    /** Answer to a QUESTION / CONFIRM (main thread). */
    var answer: String? = null
    internal var view: View? = null
}

enum class Phase(val color: Int, val pulse: Boolean, val running: Boolean) {
    WORKING(C.ACCENT, true, true),
    PAUSED(C.WARN, false, true),
    HELD(C.WARN, true, true),
    ASKING(C.WARN, true, true),
    DONE(C.OK, false, false),
    FAILED(C.BAD, false, false),
}

/** What the overlay reports back to the service. */
interface OverlayHost {
    fun onPauseTap()
    fun onStopTap()
    fun onMicTap()
    fun onSend(text: String)
    fun onAnswer(item: FeedItem, answer: String)
    fun onClose()
    fun onOutsideTouch()
    fun onTyping(active: Boolean)
}

/**
 * The run overlay. Collapsed it is a small island at the bottom (what the agent does now, plus mic, pause and stop);
 * pulled up it becomes a chat-style panel with details, the step feed, questions, confirmations and a text/voice input.
 * Touches outside it pass through to the phone, so the user can always tap things themselves.
 */
class Overlay(private val ctx: Context, private val host: OverlayHost) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val imm = ctx.getSystemService(InputMethodManager::class.java)
    private var root: Root? = null
    private lateinit var wlp: WindowManager.LayoutParams

    var expanded = false
        private set
    private var phase = Phase.WORKING
    private var status = ""
    private var detail = ""
    private var listening = false
    private var transcript = ""
    private var imeInset = 0
    private var inputFocus = false
    private val items = ArrayList<FeedItem>()

    // collapsed
    private lateinit var pill: DragFrame
    private lateinit var pillDot: StatusDot
    private lateinit var pillText: TextView
    private lateinit var pillMic: ImageView
    private lateinit var pillPause: ImageView
    private lateinit var pillStop: ImageView

    // expanded
    private lateinit var panel: LinearLayout
    private lateinit var headDot: StatusDot
    private lateinit var headTitle: TextView
    private lateinit var headDetail: TextView
    private lateinit var headPause: ImageView
    private lateinit var headStop: ImageView
    private lateinit var scroll: MaxScroll
    private lateinit var list: LinearLayout
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
        items.forEach { render(it) }
        apply()
    }

    fun hide() {
        setInputFocus(false)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        items.forEach { it.view = null }
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
        if (::scroll.isInitialized) scroll.maxPx = (minOf((b.height() * 0.56f).toInt(), room) - ctx.dp(150)).coerceAtLeast(ctx.dp(120))
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

    fun setPhase(p: Phase, statusText: String) {
        phase = p
        status = statusText
        apply()
    }

    fun setStatus(statusText: String) {
        status = statusText
        if (root != null && !listening) pillText.text = status
    }

    fun setDetail(text: String) {
        detail = text
        if (root != null) headDetail.text = text
    }

    fun setListening(on: Boolean, text: String = "") {
        listening = on
        transcript = text
        apply()
    }

    fun clear() {
        items.clear()
        if (root != null) list.removeAllViews()
    }

    fun add(item: FeedItem) {
        items += item
        if (items.size > 250) {
            val old = items.removeAt(0)
            old.view?.let { list.removeView(it) }
        }
        if (root != null) { render(item); scrollToEnd() }
    }

    /** Re-renders an item after its answer changed. */
    fun update(item: FeedItem) {
        val v = item.view ?: return
        val idx = list.indexOfChild(v)
        if (idx < 0) return
        list.removeViewAt(idx)
        item.view = null
        render(item, idx)
    }

    fun buzz() { root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }

    private fun apply() {
        if (root == null) return
        pill.visibility = if (expanded) View.GONE else View.VISIBLE
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        val dotColor = if (listening) C.BAD else phase.color
        val pulse = listening || phase.pulse
        for (d in listOf(pillDot, headDot)) { d.color = dotColor; d.pulse = pulse }
        pillText.text = if (listening) transcript.ifEmpty { "Listening…" } else status
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
        pillMic.setImageDrawable(Icon("mic", if (listening) 0xFFFFFFFF.toInt() else C.TXT))
        pillMic.background = ctx.pressable(if (listening) C.BAD else 0x14FFFFFF, 20)
        headTitle.text = when (phase) {
            Phase.DONE -> "Done"
            Phase.FAILED -> "Didn't finish"
            Phase.PAUSED -> "Paused"
            Phase.ASKING -> "Needs your answer"
            else -> "Phone Agent"
        }
        headDetail.text = detail
        field.hint = when {
            listening -> "Listening…"
            !running -> "Follow up, e.g. “now do the same for…”"
            phase == Phase.ASKING -> if (items.lastOrNull { it.kind == FeedItem.Kind.QUESTION || it.kind == FeedItem.Kind.CONFIRM }?.options?.isNotEmpty() == true)
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

    private fun handle() = View(ctx).apply {
        background = round(0x4DFFFFFF, ctx.dp(2).toFloat())
        layoutParams = LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(7) }
    }

    private fun buildPill(r: Root) {
        pill = DragFrame(ctx, onUp = { expand() }, onDown = {}, onTap = { expand() }).apply {
            orientation = LinearLayout.VERTICAL
            background = glass()
            addView(handle())
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(3), ctx.dp(8), ctx.dp(9))
            }
            pillDot = StatusDot(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)) }
            pillText = ctx.text("", 15f, C.TXT, 500, 1)
            pillMic = ctx.iconButton("mic") { host.onMicTap() }
            pillPause = ctx.iconButton("pause") { host.onPauseTap() }
            pillStop = ctx.iconButton("stop", C.BAD, 0x26FF6B6B) { host.onStopTap() }
            row.addView(pillDot)
            row.addView(pillText, lp(0, -2, 1f, left = ctx.dp(8), right = ctx.dp(6)))
            row.addView(pillMic)
            row.addView(pillPause, lp(ctx.dp(40), ctx.dp(40), left = ctx.dp(6)))
            row.addView(pillStop, lp(ctx.dp(40), ctx.dp(40), left = ctx.dp(6)))
            addView(row)
        }
        r.addView(pill, FrameLayout.LayoutParams(-1, -2))
    }

    private fun buildPanel(r: Root) {
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = glass() }

        val head = DragFrame(ctx, onUp = {}, onDown = { collapse() }, onTap = null).apply { orientation = LinearLayout.VERTICAL }
        head.addView(handle().apply { setOnClickListener { collapse() } })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(10), ctx.dp(10))
        }
        headDot = StatusDot(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)) }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        headTitle = ctx.text("Phone Agent", 15.5f, C.TXT, 650, 1)
        headDetail = ctx.text("", 12f, C.SUB, 400, 1)
        titles.addView(headTitle)
        titles.addView(headDetail)
        val collapseBtn = ctx.iconButton("down", sizeDp = 38) { collapse() }
        headPause = ctx.iconButton("pause", sizeDp = 38) { host.onPauseTap() }
        headStop = ctx.iconButton("stop", C.BAD, 0x26FF6B6B, sizeDp = 38) { host.onStopTap() }
        row.addView(headDot)
        row.addView(titles, lp(0, -2, 1f, left = ctx.dp(8)))
        row.addView(collapseBtn)
        row.addView(headPause, lp(ctx.dp(38), ctx.dp(38), left = ctx.dp(6)))
        row.addView(headStop, lp(ctx.dp(38), ctx.dp(38), left = ctx.dp(6)))
        head.addView(row)
        panel.addView(head)
        panel.addView(View(ctx).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, ctx.dp(1)))

        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(6))
        }
        scroll = MaxScroll(ctx).apply { addView(list); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
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

    // ------------------------------------------------------------------ feed rendering

    private fun bubble(color: Int, mine: Boolean) = GradientDrawable().apply {
        setColor(color)
        val big = ctx.dp(18).toFloat()
        val small = ctx.dp(6).toFloat()
        cornerRadii = if (mine) floatArrayOf(big, big, big, big, small, small, big, big) else floatArrayOf(big, big, big, big, big, big, small, small)
    }

    private fun render(item: FeedItem, index: Int = -1) {
        val v: View = when (item.kind) {
            FeedItem.Kind.TASK, FeedItem.Kind.USER -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(ctx.text(item.text, 14.5f, C.TXT).apply {
                    background = bubble(C.USER, true)
                    setPadding(ctx.dp(13), ctx.dp(9), ctx.dp(13), ctx.dp(10))
                    maxWidth = (wlp.width * 0.8f).toInt()
                })
                val cap = when {
                    item.spoken -> "spoken"
                    item.kind == FeedItem.Kind.TASK -> "task"
                    else -> ""
                }
                if (cap.isNotEmpty()) addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    if (item.spoken) addView(ctx.icon("mic", C.MUTE, 12).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ctx.dp(3) })
                    addView(ctx.text(cap, 11f, C.MUTE))
                }, lp(-2, -2, top = ctx.dp(3), right = ctx.dp(4)))
            }
            FeedItem.Kind.ACTION -> LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ctx.icon(if (item.ok) "check" else "close", if (item.ok) C.OK else C.BAD, 15).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { topMargin = ctx.dp(2); rightMargin = ctx.dp(8) }
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ctx.text(item.text, 13.5f, C.TXT, 400, 2))
                    if (item.sub.isNotBlank()) addView(ctx.text(item.sub, 12f, C.MUTE, 400, 2))
                }, lp(0, -2, 1f))
            }
            FeedItem.Kind.AGENT -> agentBubble(item.text)
            FeedItem.Kind.NOTE -> ctx.text(item.text, 12f, C.MUTE).apply { gravity = Gravity.CENTER }
            FeedItem.Kind.QUESTION -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(agentBubble(item.text))
                for (o in item.options) {
                    val chosen = item.answer == o
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                        background = if (chosen) ctx.pressable(C.ACCENT_SOFT, 16, C.ACCENT) else ctx.pressable(0x0FFFFFFF, 16, C.LINE)
                        addView(ctx.text(o, 14f, C.TXT, 500, 2), lp(0, -2, 1f))
                        addView(ctx.icon(if (chosen) "check" else "right", if (chosen) C.ACCENT else C.SUB, 18))
                        alpha = if (item.answer != null && !chosen) 0.45f else 1f
                        if (item.answer == null) setOnClickListener { host.onAnswer(item, o) }
                    }, lp(top = ctx.dp(6)))
                }
            }
            FeedItem.Kind.CONFIRM -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(14))
                background = round(0x14FFB547, ctx.dp(20).toFloat(), ctx.dp(1), 0x66FFB547)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ctx.icon("shield", C.WARN, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ctx.dp(8) })
                    addView(ctx.text("Okay to do this?", 14.5f, C.TXT, 650))
                })
                addView(ctx.text(item.text, 14f, C.TXT), lp(top = ctx.dp(8)))
                val a = item.answer
                if (a == null) {
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(ctx.secondaryButton("Cancel") { host.onAnswer(item, CONFIRM_NO) }, lp(0, -2, 1f))
                        addView(ctx.primaryButton(item.sub.ifBlank { "Do it" }) { host.onAnswer(item, CONFIRM_YES) }.apply { minimumHeight = ctx.dp(46) },
                            lp(0, -2, 1f, left = ctx.dp(8)))
                    }, lp(top = ctx.dp(12)))
                } else {
                    val yes = a == CONFIRM_YES
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ctx.icon(if (yes) "check" else "close", if (yes) C.OK else C.SUB, 16).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ctx.dp(6) })
                        addView(ctx.text(when (a) { CONFIRM_YES -> "You approved"; CONFIRM_NO -> "You cancelled"; else -> "You replied" }, 13f, C.SUB, 500))
                    }, lp(top = ctx.dp(10)))
                }
            }
            FeedItem.Kind.RESULT -> LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
                val col = if (item.ok) C.OK else C.BAD
                background = round(0x0FFFFFFF, ctx.dp(20).toFloat(), ctx.dp(1), (col and 0x00FFFFFF) or 0x66000000)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ctx.icon(if (item.ok) "check" else "close", col, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ctx.dp(8) })
                    addView(ctx.text(if (item.ok) "Done" else item.options.firstOrNull() ?: "Didn't finish", 14.5f, C.TXT, 650))
                })
                addView(ctx.text(item.text, 14.5f, C.TXT).apply { setTextIsSelectable(false) }, lp(top = ctx.dp(6)))
                if (item.sub.isNotBlank()) addView(ctx.text(item.sub, 12f, C.MUTE), lp(top = ctx.dp(6)))
            }
        }
        item.view = v
        val params = lp(top = ctx.dp(if (item.kind == FeedItem.Kind.ACTION) 6 else 10))
        if (index >= 0) list.addView(v, index, params) else list.addView(v, params)
    }

    private fun agentBubble(text: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ctx.text(text, 14.5f, C.TXT).apply {
            background = bubble(0x14FFFFFF, false)
            setPadding(ctx.dp(13), ctx.dp(9), ctx.dp(13), ctx.dp(10))
            maxWidth = (wlp.width * 0.85f).toInt()
        })
    }

    companion object {
        const val CONFIRM_YES = "\u0000yes"
        const val CONFIRM_NO = "\u0000no"
    }

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

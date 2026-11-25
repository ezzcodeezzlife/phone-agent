package dev.phoneagent

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** One entry of the run conversation (overlay panel and the full-screen run view in the app). */
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
    /** Bumped when the item changes, so views re-render it. */
    var rev = 0

    companion object {
        const val CONFIRM_YES = "\u0000yes"
        const val CONFIRM_NO = "\u0000no"
    }
}

enum class Phase(val color: Int, val pulse: Boolean, val running: Boolean) {
    WORKING(C.ACCENT, true, true),
    PAUSED(C.WARN, false, true),
    HELD(C.WARN, true, true),
    ASKING(C.WARN, true, true),
    DONE(C.OK, false, false),
    FAILED(C.BAD, false, false),
}

/** Renders a list of [FeedItem]s as chat bubbles, action rows, questions and confirmations. */
class FeedView(ctx: Context, private val onAnswer: (FeedItem, String) -> Unit) : LinearLayout(ctx) {
    /** Widest a bubble may get, in px. */
    var maxBubble = ctx.dp(300)
    private val views = HashMap<FeedItem, Pair<View, Int>>()

    init { orientation = VERTICAL }

    /** Brings the views in line with [items]: adds new ones, re-renders changed ones, drops removed ones. */
    fun sync(items: List<FeedItem>): Boolean {
        var changed = false
        val keep = items.toHashSet()
        val it = views.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) { removeView(e.value.first); it.remove(); changed = true }
        }
        items.forEachIndexed { i, item ->
            val cur = views[item]
            if (cur == null || cur.second != item.rev) {
                cur?.let { removeView(it.first) }
                val v = render(item)
                addView(v, minOf(i, childCount), lp(top = context.dp(if (item.kind == FeedItem.Kind.ACTION) 6 else 10)))
                views[item] = v to item.rev
                changed = true
            }
        }
        return changed
    }

    private fun bubble(color: Int, mine: Boolean) = GradientDrawable().apply {
        setColor(color)
        val big = dp(18).toFloat()
        val small = dp(6).toFloat()
        cornerRadii = if (mine) floatArrayOf(big, big, big, big, small, small, big, big) else floatArrayOf(big, big, big, big, big, big, small, small)
    }

    private fun agentBubble(text: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(context.text(text, 14.5f, C.TXT).apply {
            background = bubble(0x14FFFFFF, false)
            setPadding(dp(13), dp(9), dp(13), dp(10))
            maxWidth = (maxBubble * 1.06f).toInt()
        })
    }

    private fun render(item: FeedItem): View {
        val ctx = context
        return when (item.kind) {
            FeedItem.Kind.TASK, FeedItem.Kind.USER -> LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.END
                addView(ctx.text(item.text, 14.5f, C.TXT).apply {
                    background = bubble(C.USER, true)
                    setPadding(dp(13), dp(9), dp(13), dp(10))
                    maxWidth = maxBubble
                })
                if (item.spoken) addView(ctx.icon("mic", C.MUTE, 12), lp(dp(12), dp(12), top = dp(4), right = dp(6)))
            }
            FeedItem.Kind.ACTION -> LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                addView(ctx.icon(if (item.ok) "check" else "close", if (item.ok) C.OK else C.BAD, 15).apply {
                    (layoutParams as LayoutParams).apply { topMargin = dp(2); rightMargin = dp(8) }
                })
                addView(LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    addView(ctx.text(item.text, 13.5f, C.TXT, 400, 2))
                    if (item.sub.isNotBlank()) addView(ctx.text(item.sub, 12f, C.MUTE, 400, 2))
                }, lp(0, -2, 1f))
            }
            FeedItem.Kind.AGENT -> agentBubble(item.text)
            FeedItem.Kind.NOTE -> ctx.text(item.text, 12f, C.MUTE).apply { gravity = Gravity.CENTER }
            FeedItem.Kind.QUESTION -> LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(agentBubble(item.text))
                for (o in item.options) {
                    val chosen = item.answer == o
                    addView(LinearLayout(ctx).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(10), dp(10), dp(10))
                        background = if (chosen) ctx.pressable(C.ACCENT_SOFT, 16, C.ACCENT) else ctx.pressable(0x0FFFFFFF, 16, C.LINE)
                        addView(ctx.text(o, 14f, C.TXT, 500, 2), lp(0, -2, 1f))
                        addView(ctx.icon(if (chosen) "check" else "right", if (chosen) C.ACCENT else C.SUB, 18))
                        alpha = if (item.answer != null && !chosen) 0.45f else 1f
                        if (item.answer == null) setOnClickListener { onAnswer(item, o) }
                    }, lp(top = dp(6)))
                }
            }
            FeedItem.Kind.CONFIRM -> LinearLayout(ctx).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = round(0x14FFB547, dp(20).toFloat(), dp(1), 0x66FFB547)
                addView(LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ctx.icon("shield", C.WARN, 18).apply { (layoutParams as LayoutParams).rightMargin = dp(8) })
                    addView(ctx.text("Okay to do this?", 14.5f, C.TXT, 650))
                })
                addView(ctx.text(item.text, 14f, C.TXT), lp(top = dp(8)))
                val a = item.answer
                if (a == null) {
                    addView(LinearLayout(ctx).apply {
                        orientation = HORIZONTAL
                        addView(ctx.secondaryButton("Cancel") { onAnswer(item, FeedItem.CONFIRM_NO) }, lp(0, -2, 1f))
                        addView(ctx.primaryButton(item.sub.ifBlank { "Do it" }) { onAnswer(item, FeedItem.CONFIRM_YES) }.apply { minimumHeight = dp(46) },
                            lp(0, -2, 1f, left = dp(8)))
                    }, lp(top = dp(12)))
                } else {
                    val yes = a == FeedItem.CONFIRM_YES
                    addView(LinearLayout(ctx).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ctx.icon(if (yes) "check" else "close", if (yes) C.OK else C.SUB, 16).apply { (layoutParams as LayoutParams).rightMargin = dp(6) })
                        addView(ctx.text(when (a) { FeedItem.CONFIRM_YES -> "You approved"; FeedItem.CONFIRM_NO -> "You cancelled"; else -> "You replied" }, 13f, C.SUB, 500))
                    }, lp(top = dp(10)))
                }
            }
            FeedItem.Kind.RESULT -> LinearLayout(ctx).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val col = if (item.ok) C.OK else C.BAD
                background = round(0x0FFFFFFF, dp(20).toFloat(), dp(1), (col and 0x00FFFFFF) or 0x66000000)
                addView(LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ctx.icon(if (item.ok) "check" else "close", col, 18).apply { (layoutParams as LayoutParams).rightMargin = dp(8) })
                    addView(ctx.text(if (item.ok) "Done" else item.options.firstOrNull() ?: "Didn't finish", 14.5f, C.TXT, 650), lp(0, -2, 1f))
                    if (item.sub.isNotBlank()) addView(ctx.text(item.sub, 12f, C.MUTE))
                })
                addView(ctx.text(item.text, 14.5f, C.TXT).apply { setTextIsSelectable(false) }, lp(top = dp(6)))
            }
        }
    }
}

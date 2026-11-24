package dev.phoneagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Single-series column chart (tokens per day). Thin columns with a 4dp rounded top and a square base,
 * a 2dp gap between neighbours, a recessive baseline, only the peak labelled; tap a column for its value.
 */
class BarChart(ctx: Context) : View(ctx) {
    private var values: List<Long> = emptyList()
    private var names: List<String> = emptyList()
    private var ticks: Map<Int, String> = emptyMap()
    private var unit = ""
    private var selected = -1

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }
    private val line = Paint().apply { color = 0x2EFFFFFF; strokeWidth = ctx.dp(1).toFloat() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.MUTE; textSize = ctx.dp(11).toFloat(); typeface = weight(400) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; textSize = ctx.dp(11.5f).toFloat(); typeface = weight(600) }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TXT; textSize = ctx.dp(12.5f).toFloat(); typeface = weight(600) }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.RAISED }
    private val path = Path()

    /** [names]: per-column name for the tooltip; [ticks]: index → axis label. */
    fun set(values: List<Long>, names: List<String>, ticks: Map<Int, String>, unit: String) {
        this.values = values
        this.names = names
        this.ticks = ticks
        this.unit = unit
        selected = -1
        val peak = values.withIndex().maxByOrNull { it.value }
        contentDescription = if (peak == null || peak.value == 0L) "No usage in this period"
        else "Column chart of $unit per day. Highest: ${compact(peak.value)} on ${names.getOrNull(peak.index)}. Total ${compact(values.sum())}."
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(150))
    }

    private val top get() = dp(30).toFloat()
    private val bottom get() = height - dp(20).toFloat()

    private fun slot() = if (values.isEmpty()) 0f else width.toFloat() / values.size

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        if (values.all { it <= 0 }) {
            val t = "No runs in this period"
            c.drawText(t, (width - tick.measureText(t)) / 2, (top + bottom) / 2, tick)
        }
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
        val slot = slot()
        val gap = dp(2).toFloat()
        val bw = minOf(slot - gap, dp(24).toFloat()).coerceAtLeast(dp(2).toFloat())
        val r = minOf(dp(4).toFloat(), bw / 2)
        val h = bottom - top
        val peak = values.indexOf(values.max())
        for ((i, v) in values.withIndex()) {
            if (v <= 0) continue
            val cx = slot * i + slot / 2
            val bh = (v.toFloat() / max * h).coerceAtLeast(dp(2).toFloat())
            val rect = RectF(cx - bw / 2, bottom - bh, cx + bw / 2, bottom)
            val rr = minOf(r, bh)
            path.reset()
            path.addRoundRect(rect, floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)
            bar.alpha = if (selected >= 0 && selected != i) 110 else 255
            c.drawPath(path, bar)
        }
        c.drawLine(0f, bottom, width.toFloat(), bottom, line)
        for ((i, t) in ticks) {
            val cx = slot * i + slot / 2
            val tw = tick.measureText(t)
            val x = (cx - tw / 2).coerceIn(0f, width - tw)
            c.drawText(t, x, height - dp(4).toFloat(), tick)
        }
        if (selected in values.indices) {
            val text = "${names.getOrNull(selected) ?: ""} · ${compact(values[selected])} $unit"
            val tw = tip.measureText(text)
            val cx = slot * selected + slot / 2
            val x = (cx - tw / 2).coerceIn(dp(6).toFloat(), width - tw - dp(6))
            c.drawRoundRect(x - dp(8), dp(2).toFloat(), x + tw + dp(8), dp(24).toFloat(), dp(8).toFloat(), dp(8).toFloat(), tipBg)
            c.drawText(text, x, dp(17).toFloat(), tip)
        } else if (values[peak] > 0) {
            val text = compact(values[peak])
            val tw = label.measureText(text)
            val cx = slot * peak + slot / 2
            val bh = values[peak].toFloat() / max * h
            c.drawText(text, (cx - tw / 2).coerceIn(0f, width - tw), bottom - bh - dp(6), label)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = (e.x / slot()).toInt().coerceIn(0, values.size - 1)
                if (i != selected) { selected = i; invalidate() }
            }
            MotionEvent.ACTION_CANCEL -> { selected = -1; invalidate() }
        }
        return true
    }
}

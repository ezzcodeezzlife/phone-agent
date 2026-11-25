package dev.phoneagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Shows where the agent touches: a dot with a ripple for taps, a growing ring for long presses, a trail for swipes,
 * and a short outline around fields it types into. A full-screen window that never takes touches.
 * Hidden for screenshots that go to the model.
 */
class TouchLayer(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: FxView? = null

    fun show() {
        if (view != null) return
        val v = FxView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Phone Agent touches"
        }
        try { wm.addView(v, lp); view = v } catch (e: Exception) { AgentBus.log("Touch layer error: $e") }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    /** A tap (or a long press when [holdMs] is long) at screen pixels x,y. */
    fun tap(x: Float, y: Float, holdMs: Long) = view?.add(Fx.Tap(x, y, holdMs))

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = view?.add(Fx.Swipe(x1, y1, x2, y2, durationMs))

    fun outline(r: Rect) = view?.add(Fx.Outline(RectF(r)))

    /** Clears running effects before a screenshot for the model; [ready] runs once the cleared frame is on screen. */
    fun clearForCapture(ready: () -> Unit) {
        val v = view
        if (v == null || v.effects.isEmpty()) { ready(); return }
        v.effects.clear()
        v.viewTreeObserver.registerFrameCommitCallback { v.postDelayed({ ready() }, 16) }
        v.invalidate()
    }

    private sealed class Fx(val start: Long = SystemClock.uptimeMillis()) {
        abstract val total: Long
        class Tap(val x: Float, val y: Float, val hold: Long) : Fx() { override val total = hold + 680 }
        class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dur: Long) : Fx() { override val total = dur + 420 }
        class Outline(val r: RectF) : Fx() { override val total = 900L }
    }

    private class FxView(c: Context) : View(c) {
        val effects = ArrayList<Fx>()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val ease = DecelerateInterpolator(1.6f)
        private val loc = IntArray(2)
        private val dot = dp(15).toFloat()

        fun add(f: Fx) {
            effects += f
            if (effects.size > 12) effects.removeAt(0)
            postInvalidateOnAnimation()
        }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            effects.removeAll { now - it.start > it.total }
            if (effects.isEmpty()) return
            getLocationOnScreen(loc)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            for (f in effects) {
                val t = now - f.start
                when (f) {
                    is Fx.Tap -> drawTap(c, f.x, f.y, t, f.hold)
                    is Fx.Swipe -> drawSwipe(c, f, t)
                    is Fx.Outline -> drawOutline(c, f.r, t)
                }
            }
            c.restore()
            postInvalidateOnAnimation()
        }

        /** Solid dot with a dark halo (visible on light and dark screens). */
        private fun drawDot(c: Canvas, x: Float, y: Float, scale: Float, alpha: Float) {
            if (alpha <= 0f) return
            val r = dot * scale
            fill.color = 0x40000000; fill.alpha = (0x40 * alpha).toInt()
            c.drawCircle(x, y, r + dp(3), fill)
            fill.color = C.ACCENT; fill.alpha = (235 * alpha).toInt()
            c.drawCircle(x, y, r, fill)
            fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * alpha).toInt()
            c.drawCircle(x, y, r * 0.38f, fill)
        }

        private fun drawRipple(c: Canvas, x: Float, y: Float, p: Float) {
            if (p !in 0f..1f) return
            val e = ease.getInterpolation(p)
            ring.strokeWidth = dp(4).toFloat() * (1f - 0.4f * e)
            ring.color = C.ACCENT; ring.alpha = ((1f - p) * 255).toInt()
            c.drawCircle(x, y, dot + dp(34) * e, ring)
        }

        private fun drawTap(c: Canvas, x: Float, y: Float, t: Long, hold: Long) {
            val appear = min(1f, t / 90f)
            if (hold > 250 && t < hold) {
                // Long press: a ring fills up around the dot while it is held.
                ring.strokeWidth = dp(3).toFloat()
                ring.color = C.ACCENT; ring.alpha = 230
                val rr = dot + dp(9)
                c.drawArc(x - rr, y - rr, x + rr, y + rr, -90f, 360f * t / hold, false, ring)
            }
            val after = t - max(hold, 60)
            val fade = if (after <= 0) 1f else max(0f, 1f - after / 420f)
            drawDot(c, x, y, 0.6f + 0.4f * appear, fade)
            if (after > 0) drawRipple(c, x, y, after / 560f)
        }

        private fun drawSwipe(c: Canvas, f: Fx.Swipe, t: Long) {
            val p = min(1f, t / max(1f, f.dur.toFloat()))
            val hx = f.x1 + (f.x2 - f.x1) * p
            val hy = f.y1 + (f.y2 - f.y1) * p
            val after = t - f.dur
            val fade = if (after <= 0) 1f else max(0f, 1f - after / 380f)
            ring.strokeWidth = dp(7).toFloat()
            ring.color = C.ACCENT; ring.alpha = (110 * fade).toInt()
            c.drawLine(f.x1, f.y1, hx, hy, ring)
            ring.strokeWidth = dp(2).toFloat()
            ring.color = 0xFFFFFFFF.toInt(); ring.alpha = (150 * fade).toInt()
            c.drawLine(f.x1, f.y1, hx, hy, ring)
            drawDot(c, hx, hy, 1f, fade)
        }

        private fun drawOutline(c: Canvas, r: RectF, t: Long) {
            val fade = if (t < 450) 1f else max(0f, 1f - (t - 450) / 450f)
            val grow = ease.getInterpolation(min(1f, t / 200f))
            val pad = dp(4) * grow
            val rad = dp(12).toFloat()
            fill.color = C.ACCENT; fill.alpha = (30 * fade).toInt()
            c.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, rad, rad, fill)
            ring.strokeWidth = dp(2.5f).toFloat()
            ring.color = C.ACCENT; ring.alpha = (230 * fade).toInt()
            c.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, rad, rad, ring)
        }
    }
}

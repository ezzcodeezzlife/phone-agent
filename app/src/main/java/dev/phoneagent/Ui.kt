package dev.phoneagent

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared look for the app and the overlay: a dark theme, a few drawn icons, small view helpers. No resources needed. */
object C {
    const val BG = 0xFF0E0E12.toInt()
    const val CARD = 0xFF1B1B21.toInt()
    const val RAISED = 0xFF26262E.toInt()
    const val FIELD = 0xFF15151A.toInt()
    const val LINE = 0x1AFFFFFF
    const val TXT = 0xFFF2F2F5.toInt()
    const val SUB = 0xFFA9A9B6.toInt()
    const val MUTE = 0xFF73737F.toInt()
    const val ACCENT = 0xFF8B78F5.toInt()
    const val ACCENT_SOFT = 0x2E8B78F5
    const val ON_ACCENT = 0xFF120D2B.toInt()
    const val OK = 0xFF3FD68B.toInt()
    const val WARN = 0xFFFFB547.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    const val USER = 0xFF2E2C4A.toInt()
    const val GLASS = 0xFF16161C.toInt()
}

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
fun View.dp(v: Number): Int = context.dp(v)

fun weight(w: Int): Typeface = Typeface.create(Typeface.DEFAULT, w, false)

fun Context.text(s: CharSequence, size: Float = 14f, color: Int = C.TXT, w: Int = 400, lines: Int = 0) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = weight(w)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** Rounded background with a touch ripple. */
fun Context.pressable(color: Int, radiusDp: Number, strokeColor: Int = 0): Drawable {
    val r = dp(radiusDp).toFloat()
    val content = round(color, r, if (strokeColor != 0) dp(1) else 0, strokeColor)
    return RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, round(0xFFFFFFFF.toInt(), r))
}

fun Context.icon(name: String, color: Int = C.TXT, sizeDp: Int = 24, padDp: Int = 0) = ImageView(this).apply {
    setImageDrawable(Icon(name, color))
    scaleType = ImageView.ScaleType.FIT_XY
    val p = dp(padDp)
    setPadding(p, p, p, p)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp + 2 * padDp), dp(sizeDp + 2 * padDp))
}

/** Round icon button. */
fun Context.iconButton(name: String, color: Int = C.TXT, bg: Int = 0x14FFFFFF, sizeDp: Int = 40, iconDp: Int = 20, onClick: () -> Unit) =
    ImageView(this).apply {
        setImageDrawable(Icon(name, color))
        scaleType = ImageView.ScaleType.FIT_XY
        val p = dp((sizeDp - iconDp) / 2f)
        setPadding(p, p, p, p)
        background = pressable(bg, sizeDp / 2f)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
        contentDescription = name
    }

/** Pill-shaped chip with optional leading icon. */
fun Context.chip(label: String, iconName: String? = null, selected: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (iconName != null) 10 else 14), 0, dp(14), 0)
        minimumHeight = dp(34)
        background = if (selected) pressable(C.ACCENT_SOFT, 17, C.ACCENT) else pressable(0x0FFFFFFF, 17, C.LINE)
        if (iconName != null) addView(icon(iconName, if (selected) C.ACCENT else C.SUB, 16).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
        })
        addView(text(label, 13f, if (selected) C.TXT else C.TXT, 500, 1))
        if (onClick != null) setOnClickListener { onClick() }
    }

fun Context.primaryButton(label: String, iconName: String? = null, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    minimumHeight = dp(52)
    setPadding(dp(18), 0, dp(20), 0)
    background = pressable(C.ACCENT, 26)
    if (iconName != null) addView(icon(iconName, C.ON_ACCENT, 20).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
    addView(text(label, 15f, C.ON_ACCENT, 650))
    setOnClickListener { onClick() }
}

fun Context.secondaryButton(label: String, iconName: String? = null, color: Int = C.TXT, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    minimumHeight = dp(46)
    setPadding(dp(16), 0, dp(18), 0)
    background = pressable(0x12FFFFFF, 23, C.LINE)
    if (iconName != null) addView(icon(iconName, color, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
    addView(text(label, 14f, color, 600))
    setOnClickListener { onClick() }
}

fun Context.card(padDp: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padDp), dp(padDp - 2), dp(padDp), dp(padDp))
    background = round(C.CARD, dp(24).toFloat(), dp(1), C.LINE)
}

fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; bottomMargin = bottom; leftMargin = left; rightMargin = right }

/** "16.6k" style numbers. */
fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 10_000 -> "%.0fk".format(n / 1000.0)
    n >= 1_000 -> "%.1fk".format(n / 1000.0).replace(".0k", "k")
    else -> n.toString()
}

fun duration(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s < 60 -> "$s s"
        s < 3600 -> if (s % 60 == 0L || s >= 600) "${s / 60} min" else "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** Status dot: a solid circle, with a soft pulsing ring while [pulse] is on. */
class StatusDot(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    var color = C.ACCENT
        set(v) { field = v; invalidate() }
    var pulse = true
        set(v) { field = v; if (v) anim.start() else { anim.cancel(); phase = 0f }; invalidate() }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pulse) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val core = r * 0.42f
        if (pulse) {
            paint.color = color
            paint.alpha = ((1f - phase) * 90).toInt()
            c.drawCircle(cx, cy, core + (r - core) * phase, paint)
        }
        paint.color = color
        paint.alpha = 255
        c.drawCircle(cx, cy, core, paint)
    }
}

/** Simple line icons drawn on a 24×24 grid, so the app needs no image resources. */
class Icon(private val name: String, color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun stroke(w: Float = 2f) { p.style = Paint.Style.STROKE; p.strokeWidth = w }
    private fun fill() { p.style = Paint.Style.FILL }
    private fun poly(c: Canvas, vararg pts: Float, close: Boolean = false) {
        val path = Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        when (name) {
            "mic" -> {
                fill(); c.drawRoundRect(9f, 3f, 15f, 14.5f, 3f, 3f, p)
                stroke(1.9f); c.drawArc(5.5f, 4.5f, 18.5f, 17.5f, 0f, 180f, false, p)
                c.drawLine(12f, 17.5f, 12f, 21f, p); c.drawLine(9f, 21f, 15f, 21f, p)
            }
            "pause" -> { fill(); c.drawRoundRect(6.5f, 5f, 10f, 19f, 1.3f, 1.3f, p); c.drawRoundRect(14f, 5f, 17.5f, 19f, 1.3f, 1.3f, p) }
            "play" -> { fill(); poly(c, 8.5f, 5.5f, 18.5f, 12f, 8.5f, 18.5f, close = true); stroke(1.6f); poly(c, 8.5f, 5.5f, 18.5f, 12f, 8.5f, 18.5f, close = true) }
            "stop" -> { fill(); c.drawRoundRect(6.5f, 6.5f, 17.5f, 17.5f, 2.4f, 2.4f, p) }
            "close" -> { stroke(2f); c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, p); c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, p) }
            "down" -> { stroke(2f); poly(c, 6f, 9.5f, 12f, 15.5f, 18f, 9.5f) }
            "up" -> { stroke(2f); poly(c, 6f, 14.5f, 12f, 8.5f, 18f, 14.5f) }
            "right" -> { stroke(2f); poly(c, 9.5f, 6f, 15.5f, 12f, 9.5f, 18f) }
            "back" -> { stroke(2f); c.drawLine(19.5f, 12f, 4.5f, 12f, p); poly(c, 10.5f, 6f, 4.5f, 12f, 10.5f, 18f) }
            "send" -> { fill(); poly(c, 3.5f, 20.2f, 21f, 12f, 3.5f, 3.8f, 3.5f, 10.2f, 15f, 12f, 3.5f, 13.8f, close = true) }
            "check" -> { stroke(2.2f); poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f) }
            "home" -> { stroke(1.8f); poly(c, 4f, 10.5f, 12f, 4f, 20f, 10.5f, 20f, 20f, 14.5f, 20f, 14.5f, 14.5f, 9.5f, 14.5f, 9.5f, 20f, 4f, 20f, close = true) }
            "runs" -> {
                fill(); c.drawCircle(5f, 6.5f, 1.4f, p); c.drawCircle(5f, 12f, 1.4f, p); c.drawCircle(5f, 17.5f, 1.4f, p)
                stroke(1.9f); c.drawLine(9.5f, 6.5f, 20f, 6.5f, p); c.drawLine(9.5f, 12f, 20f, 12f, p); c.drawLine(9.5f, 17.5f, 20f, 17.5f, p)
            }
            "chart" -> { fill(); c.drawRoundRect(4f, 13f, 8f, 20f, 1f, 1f, p); c.drawRoundRect(10f, 8f, 14f, 20f, 1f, 1f, p); c.drawRoundRect(16f, 4f, 20f, 20f, 1f, 1f, p) }
            "settings" -> {
                fill()
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                for (k in 0 until 8) {
                    val base = k * 45.0
                    fun pt(r: Double, deg: Double) = floatArrayOf((12 + r * cos(Math.toRadians(deg))).toFloat(), (12 + r * sin(Math.toRadians(deg))).toFloat())
                    val pts = listOf(pt(7.4, base - 22.5), pt(7.4, base - 13.0), pt(10.0, base - 9.0), pt(10.0, base + 9.0), pt(7.4, base + 13.0))
                    pts.forEachIndexed { i, q -> if (k == 0 && i == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]) }
                }
                path.close()
                path.addCircle(12f, 12f, 3.2f, Path.Direction.CW)
                c.drawPath(path, p)
            }
            "replay" -> {
                stroke(1.9f); c.drawArc(5f, 5f, 19f, 19f, -90f, 290f, false, p)
                poly(c, 13.4f, 2.2f, 10.6f, 5f, 13.4f, 7.8f)
            }
            "trash" -> {
                stroke(1.8f); c.drawLine(4.5f, 7f, 19.5f, 7f, p); poly(c, 9.5f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 7f)
                poly(c, 6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f)
                c.drawLine(10.3f, 10.5f, 10.5f, 16.5f, p); c.drawLine(13.7f, 10.5f, 13.5f, 16.5f, p)
            }
            "info" -> { stroke(1.8f); c.drawCircle(12f, 12f, 9f, p); c.drawLine(12f, 11f, 12f, 16.5f, p); fill(); c.drawCircle(12f, 7.8f, 1.15f, p) }
            "shield" -> {
                stroke(1.8f)
                c.drawPath(Path().apply {
                    moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); cubicTo(19f, 15.5f, 16f, 19.5f, 12f, 21f)
                    cubicTo(8f, 19.5f, 5f, 15.5f, 5f, 11f); lineTo(5f, 6f); close()
                }, p)
            }
            "bolt" -> { fill(); poly(c, 13.5f, 2.5f, 5.5f, 13.5f, 11f, 13.5f, 10f, 21.5f, 18.5f, 10f, 13f, 10f, 14f, 2.5f, close = true) }
            "clock" -> { stroke(1.8f); c.drawCircle(12f, 12f, 9f, p); poly(c, 12f, 7f, 12f, 12f, 15.5f, 14f) }
            "open" -> {
                stroke(1.8f); poly(c, 18f, 13.5f, 18f, 19f, 5f, 19f, 5f, 6f, 10.5f, 6f)
                poly(c, 13.5f, 4.5f, 19.5f, 4.5f, 19.5f, 10.5f); c.drawLine(19.5f, 4.5f, 11f, 13f, p)
            }
            "touch" -> {
                stroke(1.8f)
                c.drawPath(Path().apply {
                    moveTo(9f, 12.5f); lineTo(9f, 5.5f); cubicTo(9f, 3.5f, 12f, 3.5f, 12f, 5.5f); lineTo(12f, 11f)
                    lineTo(17f, 12f); cubicTo(18.5f, 12.3f, 19.3f, 13.6f, 19f, 15f); lineTo(18f, 20.5f); lineTo(10.5f, 20.5f)
                    lineTo(6f, 15.5f); cubicTo(5f, 14.3f, 6.8f, 12.7f, 8f, 13.6f); lineTo(9f, 14.5f)
                }, p)
            }
            "keyboard" -> {
                stroke(1.7f); c.drawRoundRect(3f, 6f, 21f, 18f, 2f, 2f, p)
                fill(); for (x in listOf(7f, 10.3f, 13.6f, 17f)) { c.drawCircle(x, 10f, 0.9f, p) }
                stroke(1.7f); c.drawLine(8f, 14.2f, 16f, 14.2f, p)
            }
            "spark" -> {
                fill()
                c.drawPath(Path().apply {
                    moveTo(12f, 2.5f); cubicTo(12.7f, 8f, 16f, 11.3f, 21.5f, 12f); cubicTo(16f, 12.7f, 12.7f, 16f, 12f, 21.5f)
                    cubicTo(11.3f, 16f, 8f, 12.7f, 2.5f, 12f); cubicTo(8f, 11.3f, 11.3f, 8f, 12f, 2.5f); close()
                }, p)
            }
            "user" -> { stroke(1.8f); c.drawCircle(12f, 8.5f, 3.8f, p); c.drawArc(4.5f, 14f, 19.5f, 27f, 180f, 180f, false, p) }
            "full" -> {
                stroke(2f)
                poly(c, 4f, 9.5f, 4f, 4f, 9.5f, 4f); poly(c, 14.5f, 4f, 20f, 4f, 20f, 9.5f)
                poly(c, 20f, 14.5f, 20f, 20f, 14.5f, 20f); poly(c, 9.5f, 20f, 4f, 20f, 4f, 14.5f)
            }
            "plus" -> { stroke(2f); c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            "arrow_up" -> { stroke(2.2f); c.drawLine(12f, 19.5f, 12f, 5f, p); poly(c, 6f, 11f, 12f, 5f, 18f, 11f) }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

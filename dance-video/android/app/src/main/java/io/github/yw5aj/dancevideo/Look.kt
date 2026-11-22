package io.github.yw5aj.dancevideo

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.sin

/** The web version's palette: traditional colour names a Chinese-dance class would know. */
class Pal(val night: Boolean) {
    val bg = if (night) 0xFF131A26.toInt() else 0xFFEAF1F4.toInt()       // 月白 page ground
    val paper = if (night) 0xFF1C2534.toInt() else 0xFFFBFDFD.toInt()    // raised surface
    val ink = if (night) 0xFFE3EEF2.toInt() else 0xFF1F2A3C.toInt()      // 黛
    val muted = if (night) 0xFF93A3B5.toInt() else 0xFF5A687B.toInt()
    val line = if (night) 0xFF303D51.toInt() else 0xFFC6D4DB.toInt()
    val accent = if (night) 0xFFEE6F81.toInt() else 0xFFB1283A.toInt()   // 胭脂
    val onAccent = if (night) 0xFF1D0A0E.toInt() else 0xFFFFF7F5.toInt()
    val soft = if (night) 0xFF3D2A3A.toInt() else 0xFFECD3DC.toInt()     // 藕荷
    val bad = if (night) 0xFFFFA48F.toInt() else 0xFF9A2A12.toInt()

    companion object {
        fun of(ctx: Context) = Pal(
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
    }
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

/** Rounded pill button: filled 胭脂 for the main action, outlined for quiet ones. */
fun Context.pill(pal: Pal, label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 19f else 17f)
    typeface = Typeface.create(null, if (primary) 600 else 500, false)
    letterSpacing = if (primary) 0.02f else 0f
    setTextColor(if (primary) pal.onAccent else pal.ink)
    minHeight = dp(if (primary) 60 else 52)
    setPadding(dp(20), dp(8), dp(20), dp(8))
    val shape = GradientDrawable().apply {
        cornerRadius = dp(30).toFloat()
        setColor(if (primary) pal.accent else 0x00000000)
        if (!primary) setStroke(dp(1.5f), pal.line)
    }
    val mask = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(0xFFFFFFFF.toInt()) }
    background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else (pal.accent and 0x00FFFFFF) or 0x22000000), shape, mask)
    isClickable = true
    isFocusable = true
    setOnClickListener { if (isEnabled) onClick() }
}

fun Context.textLink(pal: Pal, label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTextColor(pal.accent)
    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    setPadding(0, dp(8), 0, dp(8))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/** The progress counted as an eight-count: eight bars that fill in turn, the current one pulsing. */
class BeatsView(ctx: Context, private val pal: Pal, private val font: Typeface) : View(ctx) {
    var progress = 0.0
        set(v) { field = v; invalidate() }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, ctx.resources.displayMetrics)
    }
    private val rect = RectF()
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = context.dp(14 * 1.5f) + context.dp(6) + (textPaint.fontSpacing).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val gap = context.dp(6).toFloat()
        val w = (width - gap * 7) / 8
        val barH = context.dp(14).toFloat()
        val barCy = context.dp(14 * 1.5f) / 2f
        val now = floor(progress * 8).toInt()
        val t = pulse.animatedValue as Float
        for (i in 0 until 8) {
            val f = (progress * 8 - i).coerceIn(0.0, 1.0).toFloat()
            val x = i * (w + gap)
            val scale = if (i == now && progress < 1 && pulse.isRunning) 1f + 0.5f * sin(Math.PI * t).toFloat() else 1f
            val h = barH * scale
            rect.set(x, barCy - h / 2, x + w, barCy + h / 2)
            val r = barH / 2
            barPaint.color = pal.soft
            canvas.drawRoundRect(rect, r, r, barPaint)
            if (f > 0) {
                canvas.save()
                canvas.clipRect(x, rect.top, x + w * f, rect.bottom)
                barPaint.color = pal.accent
                canvas.drawRoundRect(rect, r, r, barPaint)
                canvas.restore()
            }
            textPaint.color = if (f > 0) pal.accent else pal.muted
            canvas.drawText((i + 1).toString(), x + w / 2, context.dp(14 * 1.5f) + context.dp(6) - textPaint.ascent(), textPaint)
        }
    }
}

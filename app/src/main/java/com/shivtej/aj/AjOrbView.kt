package com.shivtej.aj

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AJ का चलता-फिरता robot चेहरा (HUD orb)।
 * बाहर घूमते हुए छल्ले, बीच में robot का visor और चमकती आँखें।
 * IDLE = नीला, धीरे घूमता, आँखें झपकती | LISTENING = हरा-नीला, धड़कता
 * THINKING = बैंगनी, तेज़ घूमता | SPEAKING = नीला, आवाज़ की लहरें
 */
class AjOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                glowColor = 0 // नया glow बनाओ
                invalidate()
            }
        }

    private val start = SystemClock.uptimeMillis()
    private val d = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var glowColor = 0
    private var glowSize = 0f

    private fun colorFor(m: Mode): Int = when (m) {
        Mode.IDLE -> 0xFF29D3FF.toInt()
        Mode.LISTENING -> 0xFF3DF5C4.toInt()
        Mode.THINKING -> 0xFF8B6CFF.toInt()
        Mode.SPEAKING -> 0xFF29D3FF.toInt()
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.96f
        if (r <= 0f) return
        val c = colorFor(mode)
        val speed = when (mode) {
            Mode.THINKING -> 3.2f
            Mode.LISTENING -> 1.6f
            Mode.SPEAKING -> 1.2f
            Mode.IDLE -> 0.5f
        }
        val pulse = when (mode) {
            Mode.LISTENING -> 1f + 0.04f * sin(t * 6f)
            Mode.SPEAKING -> 1f + 0.025f * sin(t * 10f)
            else -> 1f + 0.012f * sin(t * 2f)
        }

        // 1) पीछे की चमक
        if (glowColor != c || glowSize != r) {
            glowColor = c
            glowSize = r
            glowPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(alpha(c, 110), alpha(c, 35), alpha(c, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r * pulse, glowPaint)

        // 2) पतली बाहरी रिंग
        ringPaint.color = alpha(c, 70)
        ringPaint.strokeWidth = 1.2f * d
        canvas.drawCircle(cx, cy, r * 0.88f * pulse, ringPaint)

        // 3) घूमते हुए tick निशान (HUD dial)
        canvas.save()
        canvas.rotate(t * 8f * speed, cx, cy)
        ringPaint.strokeWidth = 1.4f * d
        val tickOuter = r * 0.83f
        for (i in 0 until 72) {
            val big = i % 6 == 0
            ringPaint.color = alpha(c, if (big) 200 else 80)
            val len = if (big) 9f * d else 4f * d
            val a = (i * 5f) * (PI / 180f).toFloat()
            val x1 = cx + cos(a) * tickOuter
            val y1 = cy + sin(a) * tickOuter
            val x2 = cx + cos(a) * (tickOuter - len)
            val y2 = cy + sin(a) * (tickOuter - len)
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }
        canvas.restore()

        // 4) घूमते हुए चमकदार arcs
        arcPaint.color = c
        arcPaint.strokeWidth = 3.2f * d
        val r1 = r * 0.70f
        rect.set(cx - r1, cy - r1, cx + r1, cy + r1)
        for (k in 0 until 3) canvas.drawArc(rect, t * 50f * speed + k * 120f, 62f, false, arcPaint)

        arcPaint.color = alpha(0xFF8B6CFF.toInt(), 220)
        arcPaint.strokeWidth = 2.2f * d
        val r2 = r * 0.61f
        rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
        for (k in 0 until 2) canvas.drawArc(rect, -t * 75f * speed + k * 180f, 38f, false, arcPaint)

        // 5) robot का सिर (core)
        val core = r * 0.48f
        fillPaint.shader = null
        fillPaint.color = 0xFF0B1528.toInt()
        canvas.drawCircle(cx, cy, core, fillPaint)
        ringPaint.color = alpha(c, 160)
        ringPaint.strokeWidth = 2f * d
        canvas.drawCircle(cx, cy, core, ringPaint)

        // 6) visor
        val vw = core * 1.28f
        val vh = core * 0.50f
        val vy = cy - core * 0.08f
        rect.set(cx - vw / 2, vy - vh / 2, cx + vw / 2, vy + vh / 2)
        fillPaint.color = 0xFF03060E.toInt()
        canvas.drawRoundRect(rect, vh / 2, vh / 2, fillPaint)
        ringPaint.color = alpha(c, 110)
        ringPaint.strokeWidth = 1.4f * d
        canvas.drawRoundRect(rect, vh / 2, vh / 2, ringPaint)

        // 7) आँखें (झपकना / बोलते समय धड़कना)
        val blinkPhase = t % 4.2f
        val blink = if (mode == Mode.IDLE && blinkPhase > 4.05f) 0.15f else 1f
        val eyeW = core * 0.26f
        var eyeH = core * 0.20f * blink
        if (mode == Mode.SPEAKING) eyeH *= 0.75f + 0.25f * abs(sin(t * 9f))
        if (mode == Mode.THINKING) eyeH *= 0.55f
        val gap = core * 0.20f
        for (side in listOf(-1, 1)) {
            val ex = cx + side * (gap + eyeW / 2)
            // eye glow
            fillPaint.color = alpha(c, 60)
            rect.set(ex - eyeW / 2 - 4 * d, vy - eyeH / 2 - 4 * d, ex + eyeW / 2 + 4 * d, vy + eyeH / 2 + 4 * d)
            canvas.drawRoundRect(rect, eyeH, eyeH, fillPaint)
            fillPaint.color = c
            rect.set(ex - eyeW / 2, vy - eyeH / 2, ex + eyeW / 2, vy + eyeH / 2)
            canvas.drawRoundRect(rect, eyeH / 2, eyeH / 2, fillPaint)
        }

        // 8) मुँह: आवाज़ की पट्टियाँ
        val bars = 7
        val barGap = core * 0.12f
        val by = cy + core * 0.52f
        arcPaint.strokeWidth = 2.6f * d
        for (i in 0 until bars) {
            val x = cx + (i - (bars - 1) / 2f) * barGap
            val base = when (mode) {
                Mode.SPEAKING -> 0.35f + 0.65f * abs(sin(t * 11f + i * 0.9f))
                Mode.LISTENING -> 0.25f + 0.45f * abs(sin(t * 7f + i * 1.3f))
                Mode.THINKING -> 0.2f + 0.2f * abs(sin(t * 4f + i))
                Mode.IDLE -> 0.18f
            }
            val h = core * 0.22f * base
            arcPaint.color = if (i == bars / 2) 0xFF8B6CFF.toInt() else alpha(c, 230)
            canvas.drawLine(x, by - h, x, by + h, arcPaint)
        }

        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }
}

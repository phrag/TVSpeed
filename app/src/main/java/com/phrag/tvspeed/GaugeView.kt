package com.phrag.tvspeed

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * Speedometer: a 240° arc on a log scale (0–1000 Mbps, so a 5 Mbps VPN and
 * gigabit fibre are both readable), a big centre reading and a phase label.
 */
class GaugeView(ctx: Context) : View(ctx) {

    private val density = resources.displayMetrics.density
    private val stroke = 22 * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(40, 255, 255, 255)
    }
    private val fill = Paint(track).apply { color = Colors.DOWN }
    private val tickText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT2
        textSize = 14 * density
        textAlign = Paint.Align.CENTER
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val unitText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.TEXT2
        textSize = 20 * density
        textAlign = Paint.Align.CENTER
    }
    private val labelText = Paint(unitText).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.15f
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.BG
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val arc = RectF()
    private val pill = RectF()

    private var fraction = 0f
    private var animator: ValueAnimator? = null
    private var reading = "—"
    private var unit = ""
    private var label = "READY"
    private var badge: String? = null

    /** Move the needle (animated) to [mbps]. */
    fun setSpeed(mbps: Double) {
        val target = fractionOf(mbps)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setColor(color: Int) {
        fill.color = color
        invalidate()
    }

    /** Any new reading takes the place of the rating badge. */
    fun setReading(value: String, unit: String, label: String) {
        reading = value
        this.unit = unit
        this.label = label.uppercase()
        badge = null
        invalidate()
    }

    /** A large filled pill in the gap under the arc, e.g. "✓ GOOD", in place of the phase label. */
    fun setBadge(text: String, color: Int) {
        badge = text
        badgeFill.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height - (24 * density).toInt()).toFloat()
        val r = size / 2 - stroke - 22 * density   // room for tick labels outside the arc
        val cx = width / 2f
        val cy = size / 2 + 8 * density
        arc.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arc, START, SWEEP, false, track)
        if (fraction > 0.002f) canvas.drawArc(arc, START, SWEEP * fraction, false, fill)

        val lr = r + stroke / 2 + 16 * density
        for (t in TICKS) {
            val a = Math.toRadians((START + SWEEP * fractionOf(t.toDouble())).toDouble())
            val x = cx + lr * cos(a).toFloat()
            val y = cy + lr * sin(a).toFloat() + tickText.textSize / 3
            canvas.drawText(if (t >= 1000) "1G" else t.toString(), x, y, tickText)
        }

        valueText.textSize = r * 0.48f
        canvas.drawText(reading, cx, cy + valueText.textSize * 0.25f, valueText)
        canvas.drawText(unit, cx, cy + valueText.textSize * 0.25f + unitText.textSize * 1.6f, unitText)
        val b = badge
        if (b == null) {
            canvas.drawText(label, cx, cy + r * 0.92f, labelText)
        } else {
            badgeText.textSize = r * 0.2f
            val h = badgeText.textSize * 1.6f
            val w = badgeText.measureText(b) + badgeText.textSize * 1.4f
            val y = cy + r * 0.84f
            pill.set(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2)
            canvas.drawRoundRect(pill, h / 2, h / 2, badgeFill)
            canvas.drawText(b, cx, y - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
        }
    }

    companion object {
        private const val START = 150f
        private const val SWEEP = 240f
        private const val MAX = 1000.0
        private val TICKS = intArrayOf(0, 5, 10, 25, 50, 100, 250, 500, 1000)

        fun fractionOf(mbps: Double): Float =
            (log10(1 + mbps.coerceIn(0.0, MAX)) / log10(1 + MAX)).toFloat()
    }
}

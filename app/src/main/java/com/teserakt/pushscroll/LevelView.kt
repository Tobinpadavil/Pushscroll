package com.teserakt.pushscroll

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Live bar: how far down you are in the current pushup. */
class LevelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var shown = 0f
    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF232A33.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC6FF3D.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A94A3.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shown += (level - shown) * 0.5f
        val dp = resources.displayMetrics.density
        val barH = 12 * dp
        val top = height - barH
        val rad = barH / 2
        r.set(0f, top, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, rad, rad, track)
        r.set(0f, top, (width * shown).coerceAtLeast(barH), height.toFloat())
        canvas.drawRoundRect(r, rad, rad, fill)
        canvas.drawText("UP", 0f, top - 6 * dp, label)
        val w = label.measureText("DOWN")
        canvas.drawText("DOWN", width - w, top - 6 * dp, label)
        if (kotlin.math.abs(level - shown) > 0.01f) postInvalidateOnAnimation()
    }
}

package dev.firemouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Draws an arrow cursor whose tip sits exactly at (x, y).
 * The tip is the point we click, so what you see is what gets clicked.
 */
class CursorView(context: Context) : View(context) {
    var x0 = 960f; private set
    var y0 = 540f; private set

    private var fillColor = Color.WHITE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
    }

    // Classic arrow shape, tip at (0,0), about 40px tall.
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 38f); lineTo(10f, 29f); lineTo(17f, 44f)
        lineTo(24f, 41f); lineTo(17f, 26f); lineTo(29f, 26f); close()
    }
    private val resetColor = Runnable { fillColor = Color.WHITE; invalidate() }

    fun moveTo(x: Float, y: Float) { x0 = x; y0 = y; invalidate() }

    /** Briefly tint the cursor: green = something was clicked/scrolled, red = nothing there. */
    fun flash(hit: Boolean) {
        fillColor = if (hit) Color.rgb(80, 220, 120) else Color.rgb(240, 80, 80)
        invalidate()
        removeCallbacks(resetColor); postDelayed(resetColor, 250)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(x0, y0)
        fill.color = fillColor
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }
}

package com.duallink.downloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Last two minutes of per-link speed (bytes/s), one sample per second. */
object SpeedHistory {
    const val N = 120
    private val wifi = DoubleArray(N)
    private val cell = DoubleArray(N)
    private var pos = 0
    private var count = 0
    @Volatile var label = ""

    @Synchronized
    fun push(wifiBps: Double, cellBps: Double) {
        wifi[pos] = wifiBps
        cell[pos] = cellBps
        pos = (pos + 1) % N
        if (count < N) count++
    }

    @Synchronized
    fun clear() {
        count = 0
        pos = 0
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): Pair<DoubleArray, DoubleArray> {
        val w = DoubleArray(count)
        val c = DoubleArray(count)
        for (i in 0 until count) {
            val idx = (pos - count + i + N) % N
            w[i] = wifi[idx]
            c[i] = cell[idx]
        }
        return w to c
    }
}

/** Line chart: Wi-Fi (blue), mobile data (orange), total (green). */
class SpeedChart(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val wifiPaint = line(Color.rgb(25, 118, 210))
    private val cellPaint = line(Color.rgb(245, 124, 0))
    private val totalPaint = line(Color.rgb(46, 125, 50)).apply { strokeWidth = dp(2.5f) }
    private val gridPaint = Paint().apply { color = Color.rgb(225, 225, 225); strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = dp(11f) }
    private val bgPaint = Paint().apply { color = Color.rgb(250, 250, 250) }

    private fun line(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeJoin = Paint.Join.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(170f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = dp(22f)
        val bottom = h - dp(6f)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val (wifi, cell) = SpeedHistory.snapshot()
        val total = DoubleArray(wifi.size) { wifi[it] + cell[it] }
        val max = maxOf(total.maxOrNull() ?: 0.0, 128.0 * 1024) * 1.15

        // Grid: 4 horizontal lines with labels.
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        canvas.drawText(fmtMbps(max), dp(4f), top - dp(4f), textPaint)

        // Legend (left side; values = latest second).
        val lw = wifi.lastOrNull() ?: 0.0
        val lc = cell.lastOrNull() ?: 0.0
        textPaint.textAlign = Paint.Align.RIGHT
        var x = w - dp(4f)
        for ((text, color) in listOf(
            "کل ${fmtMbps(lw + lc)}" to totalPaint.color,
            "وای‌فای ${fmtMbps(lw)}" to wifiPaint.color,
            "دیتا ${fmtMbps(lc)}" to cellPaint.color,
        )) {
            textPaint.color = color
            canvas.drawText(text, x, top - dp(6f), textPaint)
            x -= textPaint.measureText(text) + dp(12f)
        }
        textPaint.color = Color.DKGRAY
        if (SpeedHistory.label.isNotEmpty()) {
            canvas.drawText(SpeedHistory.label, w - dp(4f), bottom - dp(4f), textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT

        if (wifi.size < 2) return
        val step = w / (SpeedHistory.N - 1)
        val x0 = w - step * (wifi.size - 1) // newest sample at the right edge
        fun path(v: DoubleArray): Path {
            val p = Path()
            for (i in v.indices) {
                val x = x0 + step * i
                val y = (bottom - (bottom - top) * (v[i] / max)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        canvas.drawPath(path(wifi), wifiPaint)
        canvas.drawPath(path(cell), cellPaint)
        canvas.drawPath(path(total), totalPaint)
    }

    companion object {
        fun fmtMbps(bps: Double): String = String.format("%.1f Mbps", bps * 8 / 1_000_000)
    }
}

package com.bainance.tradess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainance.tradess.indicators.IndicatorSet

/** Candlestick chart + EMA 9/21/50 overlays + volume bars. Drawn with Compose Canvas (no third-party chart lib). */
@Composable
fun CandleChart(ind: IndicatorSet, visible: Int = 70, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(300.dp)) {
        val n = ind.candles.size
        if (n == 0) return@Canvas
        val from = maxOf(0, n - visible)
        val count = n - from
        val labelW = 64.dp.toPx()
        val w = size.width - labelW
        val priceH = size.height * 0.78f
        val volTop = size.height * 0.82f
        val volH = size.height - volTop

        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var vmax = 0.0
        for (i in from until n) {
            val c = ind.candles[i]
            hi = maxOf(hi, c.high); lo = minOf(lo, c.low); vmax = maxOf(vmax, c.volume)
        }
        if (hi == lo) { hi += 1.0; lo -= 1.0 }
        val step = w / count
        fun y(p: Double) = (priceH * (1 - (p - lo) / (hi - lo))).toFloat()
        fun x(i: Int) = (i - from) * step + step / 2

        // grid + price labels
        val label = TextStyle(color = Muted, fontSize = 10.sp)
        for (g in 0..3) {
            val price = hi - (hi - lo) * g / 3.0
            val yy = y(price)
            drawLine(Color(0x22FFFFFF), Offset(0f, yy), Offset(w, yy), 1f)
            drawText(measurer, fmtPriceShort(price), Offset(w + 4.dp.toPx(), (yy - 7.dp.toPx()).coerceAtLeast(0f)), label)
        }
        // candles + volume
        for (i in from until n) {
            val c = ind.candles[i]
            val col = if (c.close >= c.open) Green else Red
            val cx = x(i)
            drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.5f)
            val top = y(maxOf(c.open, c.close))
            val h = maxOf(1.5f, y(minOf(c.open, c.close)) - top)
            drawRect(col, Offset(cx - step * 0.35f, top), Size(step * 0.7f, h))
            if (vmax > 0) {
                val vh = (c.volume / vmax * volH).toFloat()
                drawRect(col.copy(alpha = 0.45f), Offset(cx - step * 0.35f, size.height - vh), Size(step * 0.7f, vh))
            }
        }
        // EMA overlays
        fun line(series: DoubleArray, color: Color) {
            val path = Path()
            var started = false
            for (i in from until n) {
                val v = series[i]
                if (v.isNaN()) continue
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            if (started) drawPath(path, color, style = Stroke(width = 2f))
        }
        line(ind.ema9, Color(0xFFF0B90B))
        line(ind.ema21, Color(0xFF42A5F5))
        line(ind.ema50, Color(0xFFAB47BC))
    }
}

private fun fmtPriceShort(p: Double): String = when {
    p >= 1000 -> "%.1f".format(p)
    p >= 1 -> "%.3f".format(p)
    else -> "%.6f".format(p)
}

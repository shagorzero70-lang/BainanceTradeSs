package com.bainance.tradess

import com.bainance.tradess.data.Candle
import com.bainance.tradess.indicators.IndicatorSet
import com.bainance.tradess.indicators.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    private val eps = 1e-6

    @Test fun emaOnLinearSeries() {
        val v = DoubleArray(10) { it + 1.0 }          // 1..10
        val e = Indicators.ema(v, 3)
        assertTrue(e[0].isNaN() && e[1].isNaN())
        for (i in 2 until 10) assertEquals(i.toDouble(), e[i], eps)   // seed 2.0, then value-1
    }

    @Test fun smaBasic() {
        val s = Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(s[1].isNaN())
        assertEquals(2.0, s[2], eps); assertEquals(4.0, s[4], eps)
    }

    @Test fun rsiMatchesWilderReferenceData() {
        val d = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
            45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64)
        val r = Indicators.rsi(d, 14)
        assertTrue(r[13].isNaN())
        assertEquals(70.46, r[14], 0.05)
        assertEquals(66.25, r[15], 0.05)
        assertEquals(66.48, r[16], 0.05)
    }

    @Test fun rsiExtremes() {
        assertEquals(100.0, Indicators.rsi(DoubleArray(30) { it + 1.0 })[29], eps)
        assertEquals(0.0, Indicators.rsi(DoubleArray(30) { 100.0 - it })[29], eps)
    }

    @Test fun bollingerKnownValues() {
        val b = Indicators.bollinger(DoubleArray(20) { it + 1.0 }, 20, 2.0)
        assertEquals(10.5, b.middle[19], eps)
        assertEquals(22.0325626, b.upper[19], 1e-5)
        assertEquals(-1.0325626, b.lower[19], 1e-5)
        assertTrue(b.upper[18].isNaN())
    }

    @Test fun notEnoughDataIsNaNNotZero() {
        val short = DoubleArray(5) { it + 1.0 }
        assertTrue(Indicators.ema(short, 9).all { it.isNaN() })
        assertTrue(Indicators.rsi(short).all { it.isNaN() })
        assertTrue(Indicators.macd(short).hist.all { it.isNaN() })
    }

    @Test fun macdOnConstantSeriesIsZero() {
        val m = Indicators.macd(DoubleArray(80) { 50.0 })
        assertEquals(0.0, m.line[79], eps); assertEquals(0.0, m.hist[79], eps)
        assertTrue(m.signal[32].isNaN()); assertTrue(!m.signal[33].isNaN())   // 26-1 + 9-1 = 33
    }

    @Test fun stochasticBounds() {
        val n = 40
        val c = DoubleArray(n) { it + 1.0 }
        val s = Indicators.stochastic(c, c, c)
        assertEquals(100.0, s.k[n - 1], eps)
        assertTrue(s.k.filter { !it.isNaN() }.all { it in 0.0..100.0 })
    }

    @Test fun atrOnConstantRange() {
        val h = DoubleArray(30) { 11.0 }; val l = DoubleArray(30) { 9.0 }; val c = DoubleArray(30) { 10.0 }
        assertEquals(2.0, Indicators.atr(h, l, c)[29], eps)
    }

    @Test fun noLookAhead_prefixGivesSameValues() {
        val candles = (0 until 120).map {
            val p = 100 + 10 * Math.sin(it / 7.0) + it * 0.1
            Candle(it * 60_000L, p, p + 1, p - 1, p + 0.3, 10.0, it * 60_000L + 59_999)
        }
        val full = IndicatorSet(candles)
        val prefix = IndicatorSet(candles.take(90))
        val a = full.snapshot(89); val b = prefix.snapshot(89)
        assertEquals(a.ema50, b.ema50, eps); assertEquals(a.rsi, b.rsi, eps)
        assertEquals(a.macdHist, b.macdHist, eps); assertEquals(a.stochD, b.stochD, eps)
        assertEquals(a.bbUpper, b.bbUpper, eps)
    }
}

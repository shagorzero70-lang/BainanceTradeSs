package com.bainance.tradess.indicators

import com.bainance.tradess.data.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure indicator math. Every function returns an array the same length as the input;
 * positions without enough history are Double.NaN (never 0). All values at index i
 * depend only on inputs [0..i] (no look-ahead).
 */
object Indicators {
    private fun firstValid(v: DoubleArray): Int = v.indexOfFirst { !it.isNaN() }

    fun sma(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val s = firstValid(v)
        if (s < 0 || v.size - s < p) return out
        var sum = 0.0
        for (i in s until v.size) {
            sum += v[i]
            if (i - s >= p) sum -= v[i - p]
            if (i - s >= p - 1) out[i] = sum / p
        }
        return out
    }

    /** EMA seeded with the SMA of the first p valid values; k = 2/(p+1). */
    fun ema(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val s = firstValid(v)
        if (s < 0 || v.size - s < p) return out
        var sum = 0.0
        for (i in s until s + p) sum += v[i]
        var prev = sum / p
        out[s + p - 1] = prev
        val k = 2.0 / (p + 1)
        for (i in s + p until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's RSI. 100 when there are no losses (50 if no movement at all). */
    fun rsi(close: DoubleArray, p: Int = 14): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size <= p) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..p) {
            val d = close[i] - close[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgG = gain / p
        var avgL = loss / p
        out[p] = rsiValue(avgG, avgL)
        for (i in p + 1 until close.size) {
            val d = close[i] - close[i - 1]
            avgG = (avgG * (p - 1) + max(d, 0.0)) / p
            avgL = (avgL * (p - 1) + max(-d, 0.0)) / p
            out[i] = rsiValue(avgG, avgL)
        }
        return out
    }

    private fun rsiValue(avgG: Double, avgL: Double): Double = when {
        avgL == 0.0 && avgG == 0.0 -> 50.0
        avgL == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + avgG / avgL)
    }

    class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(close, fast)
        val s = ema(close, slow)
        val line = DoubleArray(close.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val signal = ema(line, sig)
        val hist = DoubleArray(close.size) { if (line[it].isNaN() || signal[it].isNaN()) Double.NaN else line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    class Stoch(val k: DoubleArray, val d: DoubleArray)

    /** Slow stochastic (14,3,3): %K = SMA3(raw %K), %D = SMA3(%K). */
    fun stochastic(high: DoubleArray, low: DoubleArray, close: DoubleArray,
                   kPeriod: Int = 14, kSmooth: Int = 3, dPeriod: Int = 3): Stoch {
        val raw = DoubleArray(close.size) { Double.NaN }
        for (i in kPeriod - 1 until close.size) {
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (j in i - kPeriod + 1..i) {
                hh = max(hh, high[j]); ll = minOf(ll, low[j])
            }
            raw[i] = if (hh == ll) 50.0 else (close[i] - ll) / (hh - ll) * 100.0
        }
        val k = sma(raw, kSmooth)
        return Stoch(k, sma(k, dPeriod))
    }

    class Bands(val upper: DoubleArray, val middle: DoubleArray, val lower: DoubleArray)

    /** Bollinger Bands with population standard deviation. */
    fun bollinger(close: DoubleArray, p: Int = 20, mult: Double = 2.0): Bands {
        val mid = sma(close, p)
        val up = DoubleArray(close.size) { Double.NaN }
        val lo = DoubleArray(close.size) { Double.NaN }
        for (i in p - 1 until close.size) {
            val m = mid[i]
            var sq = 0.0
            for (j in i - p + 1..i) sq += (close[j] - m) * (close[j] - m)
            val sd = sqrt(sq / p)
            up[i] = m + mult * sd
            lo[i] = m - mult * sd
        }
        return Bands(up, mid, lo)
    }

    /** Wilder ATR, seeded with the SMA of the first p true ranges. */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, p: Int = 14): DoubleArray {
        val out = DoubleArray(close.size) { Double.NaN }
        if (close.size < p) return out
        val tr = DoubleArray(close.size) {
            if (it == 0) high[0] - low[0]
            else maxOf(high[it] - low[it], abs(high[it] - close[it - 1]), abs(low[it] - close[it - 1]))
        }
        var prev = 0.0
        for (i in 0 until p) prev += tr[i]
        prev /= p
        out[p - 1] = prev
        for (i in p until close.size) {
            prev = (prev * (p - 1) + tr[i]) / p
            out[i] = prev
        }
        return out
    }
}

/** Values of every indicator at one candle index. NaN = not enough data. */
data class Snapshot(
    val close: Double = Double.NaN,
    val ema9: Double = Double.NaN, val ema21: Double = Double.NaN, val ema50: Double = Double.NaN,
    val macd: Double = Double.NaN, val macdSignal: Double = Double.NaN,
    val macdHist: Double = Double.NaN, val prevMacdHist: Double = Double.NaN,
    val rsi: Double = Double.NaN,
    val stochK: Double = Double.NaN, val stochD: Double = Double.NaN,
    val bbUpper: Double = Double.NaN, val bbMiddle: Double = Double.NaN, val bbLower: Double = Double.NaN,
    val atr: Double = Double.NaN
)

/** All indicator series for a candle list. */
class IndicatorSet(val candles: List<Candle>) {
    val close = DoubleArray(candles.size) { candles[it].close }
    val high = DoubleArray(candles.size) { candles[it].high }
    val low = DoubleArray(candles.size) { candles[it].low }
    val ema9 = Indicators.ema(close, 9)
    val ema21 = Indicators.ema(close, 21)
    val ema50 = Indicators.ema(close, 50)
    val rsi14 = Indicators.rsi(close, 14)
    val macd = Indicators.macd(close)
    val stoch = Indicators.stochastic(high, low, close)
    val bb = Indicators.bollinger(close)
    val atr14 = Indicators.atr(high, low, close)

    fun snapshot(i: Int): Snapshot = Snapshot(
        close = close[i],
        ema9 = ema9[i], ema21 = ema21[i], ema50 = ema50[i],
        macd = macd.line[i], macdSignal = macd.signal[i], macdHist = macd.hist[i],
        prevMacdHist = if (i > 0) macd.hist[i - 1] else Double.NaN,
        rsi = rsi14[i],
        stochK = stoch.k[i], stochD = stoch.d[i],
        bbUpper = bb.upper[i], bbMiddle = bb.middle[i], bbLower = bb.lower[i],
        atr = atr14[i]
    )
}

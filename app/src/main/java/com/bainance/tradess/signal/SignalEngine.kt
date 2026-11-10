package com.bainance.tradess.signal

import com.bainance.tradess.data.Candle
import com.bainance.tradess.data.Timeframe
import com.bainance.tradess.indicators.IndicatorSet
import com.bainance.tradess.indicators.Snapshot

enum class Direction { UP, DOWN, NEUTRAL }

enum class Bias(val label: String) {
    BUY_BIAS("BUY BIAS"), SELL_BIAS("SELL BIAS"), WAIT("WAIT")
}

data class Contribution(val indicator: String, val points: Int, val reason: String)

data class Signal(
    val direction: Direction,
    val bias: Bias,
    val score: Int,
    val strength: String,          // label only; NOT a probability
    val contributions: List<Contribution>,
    val conflicting: Boolean,
    val enoughData: Boolean,
    val candleCloseTime: Long,
    val provisional: Boolean
)

/**
 * Transparent, rule-based scoring. Each indicator adds bullish (+) or bearish (-) points:
 *   EMA trend  : +-2 (9>21>50 stack) / +-1 (9 vs 21)
 *   MACD       : +-1 (histogram sign) and +-1 more on a fresh crossover
 *   RSI        : +1 oversold(<30) or 55..70 ; -1 overbought(>70) or 30..45
 *   Stochastic : +-1 (%K vs %D), reason notes overbought/oversold zones
 *   Bollinger  : +-1 (above/below middle band); outside the band = mean-reversion caution
 * Total in [-7, +7]. UP if score >= +3, DOWN if score <= -3, otherwise NEUTRAL.
 * If bullish points >= 3 AND bearish points >= 3 the indicators conflict -> NEUTRAL/WAIT.
 */
object SignalEngine {
    const val THRESHOLD = 3
    const val MAX_SCORE = 7
    const val CONFLICT_MIN = 3

    fun evaluate(s: Snapshot, candleCloseTime: Long, provisional: Boolean): Signal {
        val required = listOf(s.close, s.ema9, s.ema21, s.ema50, s.macd, s.macdSignal, s.macdHist,
            s.rsi, s.stochK, s.stochD, s.bbUpper, s.bbMiddle, s.bbLower)
        if (required.any { it.isNaN() }) {
            return Signal(Direction.NEUTRAL, Bias.WAIT, 0, "Not enough data", emptyList(),
                false, false, candleCloseTime, provisional)
        }
        val c = ArrayList<Contribution>()

        // EMA trend
        c += when {
            s.ema9 > s.ema21 && s.ema21 > s.ema50 -> Contribution("EMA", 2, "EMA9 > EMA21 > EMA50 (bullish stack)")
            s.ema9 < s.ema21 && s.ema21 < s.ema50 -> Contribution("EMA", -2, "EMA9 < EMA21 < EMA50 (bearish stack)")
            s.ema9 > s.ema21 -> Contribution("EMA", 1, "EMA9 > EMA21")
            s.ema9 < s.ema21 -> Contribution("EMA", -1, "EMA9 < EMA21")
            else -> Contribution("EMA", 0, "EMA9 = EMA21")
        }

        // MACD
        var m = 0
        val reasons = ArrayList<String>()
        if (s.macdHist > 0) { m += 1; reasons += "MACD above signal" }
        else if (s.macdHist < 0) { m -= 1; reasons += "MACD below signal" }
        if (!s.prevMacdHist.isNaN()) {
            if (s.prevMacdHist <= 0 && s.macdHist > 0) { m += 1; reasons += "MACD bullish crossover" }
            if (s.prevMacdHist >= 0 && s.macdHist < 0) { m -= 1; reasons += "MACD bearish crossover" }
        }
        c += Contribution("MACD", m, reasons.joinToString("; ").ifEmpty { "MACD flat" })

        // RSI
        c += when {
            s.rsi > 70 -> Contribution("RSI", -1, "RSI %.1f overbought; caution".format(s.rsi))
            s.rsi < 30 -> Contribution("RSI", 1, "RSI %.1f oversold; bounce possible".format(s.rsi))
            s.rsi >= 55 -> Contribution("RSI", 1, "RSI %.1f bullish momentum".format(s.rsi))
            s.rsi <= 45 -> Contribution("RSI", -1, "RSI %.1f bearish momentum".format(s.rsi))
            else -> Contribution("RSI", 0, "RSI %.1f neutral".format(s.rsi))
        }

        // Stochastic
        val zone = when { s.stochK > 80 -> " (overbought zone)"; s.stochK < 20 -> " (oversold zone)"; else -> "" }
        c += when {
            s.stochK > s.stochD -> Contribution("Stoch", 1, "Stoch %K > %D$zone")
            s.stochK < s.stochD -> Contribution("Stoch", -1, "Stoch %K < %D$zone")
            else -> Contribution("Stoch", 0, "Stoch %K = %D")
        }

        // Bollinger
        c += when {
            s.close > s.bbUpper -> Contribution("BB", -1, "Close above upper band; stretched")
            s.close < s.bbLower -> Contribution("BB", 1, "Close below lower band; stretched")
            s.close > s.bbMiddle -> Contribution("BB", 1, "Close above middle band")
            s.close < s.bbMiddle -> Contribution("BB", -1, "Close below middle band")
            else -> Contribution("BB", 0, "Close on middle band")
        }

        val score = c.sumOf { it.points }
        val bull = c.filter { it.points > 0 }.sumOf { it.points }
        val bear = -c.filter { it.points < 0 }.sumOf { it.points }
        val conflict = bull >= CONFLICT_MIN && bear >= CONFLICT_MIN
        val direction = when {
            conflict -> Direction.NEUTRAL
            score >= THRESHOLD -> Direction.UP
            score <= -THRESHOLD -> Direction.DOWN
            else -> Direction.NEUTRAL
        }
        val bias = when (direction) {
            Direction.UP -> Bias.BUY_BIAS
            Direction.DOWN -> Bias.SELL_BIAS
            Direction.NEUTRAL -> Bias.WAIT
        }
        val strength = when {
            direction == Direction.NEUTRAL -> if (conflict) "Conflicting" else "Low"
            kotlin.math.abs(score) >= 5 -> "High score"
            else -> "Moderate score"
        }
        return Signal(direction, bias, score, strength, c, conflict, true, candleCloseTime, provisional)
    }
}

/** Analysis of one timeframe: latest indicator values + closed-candle and provisional signals. */
class TfAnalysis(
    val timeframe: Timeframe,
    val series: IndicatorSet?,
    val latest: Snapshot?,
    val closed: Signal?,
    val provisional: Signal?
)

object Analyzer {
    fun analyze(tf: Timeframe, candles: List<Candle>, nowMs: Long): TfAnalysis {
        if (candles.isEmpty()) return TfAnalysis(tf, null, null, null, null)
        val ind = IndicatorSet(candles)
        val n = candles.size
        val lastClosed = candles[n - 1].closeTime <= nowMs
        val closedIdx = if (lastClosed) n - 1 else n - 2
        val closed = if (closedIdx >= 0)
            SignalEngine.evaluate(ind.snapshot(closedIdx), candles[closedIdx].closeTime, false) else null
        val provisional = if (!lastClosed)
            SignalEngine.evaluate(ind.snapshot(n - 1), candles[n - 1].closeTime, true) else null
        return TfAnalysis(tf, ind, ind.snapshot(n - 1), closed, provisional)
    }
}

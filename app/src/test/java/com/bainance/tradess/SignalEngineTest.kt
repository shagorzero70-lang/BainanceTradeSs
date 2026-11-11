package com.bainance.tradess

import com.bainance.tradess.indicators.Snapshot
import com.bainance.tradess.signal.Bias
import com.bainance.tradess.signal.Direction
import com.bainance.tradess.signal.SignalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineTest {
    private val bull = Snapshot(
        close = 105.0, ema9 = 104.0, ema21 = 102.0, ema50 = 100.0,
        macd = 1.0, macdSignal = 0.5, macdHist = 0.5, prevMacdHist = -0.1,
        rsi = 60.0, stochK = 70.0, stochD = 60.0,
        bbUpper = 110.0, bbMiddle = 100.0, bbLower = 90.0
    )

    @Test fun strongBullishIsUp() {
        val s = SignalEngine.evaluate(bull, 1L, false)
        assertEquals(Direction.UP, s.direction); assertEquals(Bias.BUY_BIAS, s.bias)
        assertEquals(7, s.score)                       // EMA 2 + MACD 2 + RSI 1 + Stoch 1 + BB 1
        assertTrue(s.contributions.any { it.reason.contains("bullish crossover") })
    }

    @Test fun strongBearishIsDown() {
        val b = bull.copy(close = 95.0, ema9 = 96.0, ema21 = 98.0, ema50 = 100.0, macd = -1.0,
            macdSignal = -0.5, macdHist = -0.5, prevMacdHist = 0.1, rsi = 40.0, stochK = 30.0, stochD = 40.0)
        val s = SignalEngine.evaluate(b, 1L, false)
        assertEquals(Direction.DOWN, s.direction); assertEquals(Bias.SELL_BIAS, s.bias); assertEquals(-7, s.score)
    }

    @Test fun conflictingIndicatorsAreNeutralWait() {
        // bullish: EMA +2, MACD +2 (=4) ; bearish: RSI -1, Stoch -1, BB -1 (=3)
        val c = bull.copy(rsi = 40.0, stochK = 30.0, stochD = 40.0, close = 95.0)
        val s = SignalEngine.evaluate(c, 1L, false)
        assertTrue(s.conflicting)
        assertEquals(Direction.NEUTRAL, s.direction); assertEquals(Bias.WAIT, s.bias)
    }

    @Test fun belowThresholdIsNeutral() {
        val c = bull.copy(ema9 = 101.0, ema21 = 102.0, ema50 = 100.0, macdHist = -0.2, prevMacdHist = -0.3,
            rsi = 50.0, stochK = 50.0, stochD = 50.0, close = 100.0)
        val s = SignalEngine.evaluate(c, 1L, false)
        assertTrue(Math.abs(s.score) < SignalEngine.THRESHOLD)
        assertEquals(Direction.NEUTRAL, s.direction)
    }

    @Test fun missingDataMeansNotEnoughData() {
        val s = SignalEngine.evaluate(bull.copy(ema50 = Double.NaN), 1L, true)
        assertFalse(s.enoughData); assertEquals(Bias.WAIT, s.bias); assertTrue(s.provisional)
        assertEquals(0, s.contributions.size)
    }

    @Test fun overboughtRsiIsBearishCaution() {
        val s = SignalEngine.evaluate(bull.copy(rsi = 75.0), 1L, false)
        assertTrue(s.contributions.first { it.indicator == "RSI" }.points == 0)
    }
}

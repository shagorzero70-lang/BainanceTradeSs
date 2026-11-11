package com.bainance.tradess

import com.bainance.tradess.backtest.BacktestConfig
import com.bainance.tradess.backtest.Backtester
import com.bainance.tradess.data.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktesterTest {

    private fun candles(count: Int): List<Candle> =
        (0 until count).map { i ->
            val price = 100.0 + i
            Candle(
                openTime = i * 60_000L,
                open = price,
                high = price + 1.0,
                low = price - 1.0,
                close = price,
                volume = 1000.0,
                closeTime = (i + 1) * 60_000L - 1
            )
        }

    @Test
    fun insufficientCandlesReturnsMessage() {
        val cfg = BacktestConfig(warmup = 10)
        val result = Backtester.run(candles(20), cfg) { _, _ -> false }

        assertTrue(result.trades.isEmpty())
        assertTrue(result.message!!.contains("Not enough data"))
    }

    @Test
    fun noSignalReturnsNoTradesMessage() {
        val cfg = BacktestConfig(warmup = 10)
        val result = Backtester.run(candles(40), cfg) { _, _ -> false }

        assertTrue(result.trades.isEmpty())
        assertTrue(result.message!!.contains("No trades"))
    }

    @Test
    fun entryAndExitUseNextCandleOpen() {
        val data = candles(40)
        val cfg = BacktestConfig(
            feePct = 0.0,
            slippagePct = 0.0,
            warmup = 10
        )

        val result = Backtester.run(data, cfg) { _, i -> i == 10 }

        assertEquals(1, result.trades.size)
        val trade = result.trades.single()

        assertEquals(data[11].openTime, trade.entryTime)
        assertEquals(data[12].openTime, trade.exitTime)
        assertEquals(data[11].open, trade.entryPrice, 0.000001)
        assertEquals(data[12].open, trade.exitPrice, 0.000001)
    }

    @Test
    fun feesAndSlippageReduceLongReturn() {
        val data = candles(40)
        val cfg = BacktestConfig(
            feePct = 0.10,
            slippagePct = 0.05,
            warmup = 10
        )

        val result = Backtester.run(data, cfg) { _, i -> i == 10 }

        val trade = result.trades.single()
        val expected = (
            (trade.exitPrice / trade.entryPrice) *
                (1 - cfg.feePct / 100.0) *
                (1 - cfg.feePct / 100.0) - 1.0
            ) * 100.0
        val grossReturn = (trade.exitPrice / trade.entryPrice - 1.0) * 100.0

        assertEquals(expected, trade.returnPct, 0.000001)
        assertTrue(trade.returnPct < grossReturn)
    }

    @Test
    fun buyHoldReturnIsCalculated() {
        val data = candles(40)
        val cfg = BacktestConfig(
            feePct = 0.0,
            slippagePct = 0.0,
            warmup = 10
        )

        val result = Backtester.run(data, cfg) { _, _ -> false }
        val expected =
            (data.last().close / data[cfg.warmup + 1].open - 1.0) * 100.0

        assertEquals(expected, result.buyHoldPct, 0.000001)
    }

    @Test fun maxDrawdownIncludesIntratradeLow() {
        val data = candles(40).toMutableList()
        val c = data[12]
        data[12] = Candle(
            openTime = c.openTime,
            open = c.open,
            high = c.high,
            low = 50.0,
            close = c.close,
            volume = c.volume,
            closeTime = c.closeTime
        )
        val cfg = BacktestConfig(feePct = 0.0, slippagePct = 0.0, warmup = 10)
        val result = Backtester.run(data, cfg) { _, i -> i in 10..12 }
        assertEquals(1, result.trades.size)
        assertTrue(result.trades.single().returnPct > 0)
        assertTrue(result.maxDrawdownPct > 10.0)
    }

}

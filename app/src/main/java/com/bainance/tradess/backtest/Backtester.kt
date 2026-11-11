package com.bainance.tradess.backtest

import com.bainance.tradess.data.Candle
import com.bainance.tradess.indicators.IndicatorSet
import com.bainance.tradess.signal.Direction
import com.bainance.tradess.signal.SignalEngine

data class BacktestConfig(
    val feePct: Double = 0.10,        // per side, percent
    val slippagePct: Double = 0.05,   // per side, percent
    val warmup: Int = 60              // candles skipped so indicators are valid
)

data class Trade(
    val entryTime: Long, val exitTime: Long,
    val entryPrice: Double, val exitPrice: Double,
    val returnPct: Double
)

data class BacktestResult(
    val trades: List<Trade>,
    val winRatePct: Double,
    val avgReturnPct: Double,
    val totalReturnPct: Double,
    val maxDrawdownPct: Double,
    val buyHoldPct: Double,
    val candlesUsed: Int,
    val config: BacktestConfig,
    val message: String?
) {
    val samples: Int get() = trades.size
}

/**
 * Long-only historical simulation.
 *  - Signal is computed from candle i's CLOSE using only data up to i (indicators are causal).
 *  - Orders fill at the OPEN of candle i+1 (next-candle execution), adjusted by slippage.
 *  - Fee and slippage are charged on both entry and exit.
 *  - Enter when signal == UP; exit at the next open once signal is no longer UP.
 *  - A position still open at the end is closed at the last candle's close.
 * Max drawdown is measured on the equity curve of closed trades.
 * Only closed candles must be passed in.
 */
object Backtester {
    fun run(
        candles: List<Candle>,
        cfg: BacktestConfig = BacktestConfig(),
        wantsLong: (IndicatorSet, Int) -> Boolean = { ind, i ->
            val sig = SignalEngine.evaluate(ind.snapshot(i), ind.candles[i].closeTime, false)
            sig.enoughData && sig.direction == Direction.UP
        }
    ): BacktestResult {
        val n = candles.size
        if (n < cfg.warmup + 20) {
            return empty(cfg, n, "Not enough data: need at least ${cfg.warmup + 20} closed candles, got $n.")
        }
        val ind = IndicatorSet(candles)
        val fee = cfg.feePct / 100.0
        val slip = cfg.slippagePct / 100.0
        val trades = ArrayList<Trade>()
        var inPos = false
        var entryPrice = 0.0
        var entryTime = 0L
        var equity = 1.0
        var peak = 1.0
        var maxDd = 0.0

        fun updateDrawdown(high: Double, low: Double) {
            val costFactor = (1 - fee) * (1 - fee)
            val highEquity = equity * (high * (1 - slip) / entryPrice) * costFactor
            val lowEquity = equity * (low * (1 - slip) / entryPrice) * costFactor
            if (highEquity > peak) peak = highEquity
            if (peak > 0.0) {
                maxDd = maxOf(maxDd, (peak - lowEquity) / peak)
            }
        }

        fun close(exitRaw: Double, time: Long) {
            val exit = exitRaw * (1 - slip)
            val ret = (exit / entryPrice) * (1 - fee) * (1 - fee) - 1.0
            trades += Trade(entryTime, time, entryPrice, exit, ret * 100.0)
            equity *= 1 + ret
            if (equity > peak) peak = equity
            if (peak > 0.0) maxDd = maxOf(maxDd, (peak - equity) / peak)
            inPos = false
        }

        for (i in cfg.warmup until n - 1) {
            if (inPos) updateDrawdown(candles[i].high, candles[i].low)
            val long = wantsLong(ind, i)
            val next = candles[i + 1]
            if (!inPos && long) {
                inPos = true
                entryPrice = next.open * (1 + slip)
                entryTime = next.openTime
            } else if (inPos && !long) {
                close(next.open, next.openTime)
            }
        }
        if (inPos) {
            updateDrawdown(candles[n - 1].high, candles[n - 1].low)
            close(candles[n - 1].close, candles[n - 1].closeTime)
        }

        val buyHoldEntry = candles[cfg.warmup + 1].open * (1 + slip)
        val buyHoldExit = candles[n - 1].close * (1 - slip)
        val buyHold = (
            buyHoldExit / buyHoldEntry * (1 - fee) * (1 - fee) - 1.0
        ) * 100.0
        if (trades.isEmpty()) {
            return BacktestResult(trades, 0.0, 0.0, 0.0, 0.0, buyHold, n, cfg,
                "No trades were generated in this period.")
        }
        val wins = trades.count { it.returnPct > 0 }
        return BacktestResult(
            trades = trades,
            winRatePct = wins * 100.0 / trades.size,
            avgReturnPct = trades.sumOf { it.returnPct } / trades.size,
            totalReturnPct = (equity - 1.0) * 100.0,
            maxDrawdownPct = maxDd * 100.0,
            buyHoldPct = buyHold,
            candlesUsed = n,
            config = cfg,
            message = if (trades.size < 10) "Small sample (${trades.size} trades): statistics are unreliable." else null
        )
    }

    private fun empty(cfg: BacktestConfig, n: Int, msg: String) =
        BacktestResult(emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, n, cfg, msg)
}

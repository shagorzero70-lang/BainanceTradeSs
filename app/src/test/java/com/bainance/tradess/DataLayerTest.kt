package com.bainance.tradess

import com.bainance.tradess.backtest.BacktestConfig
import com.bainance.tradess.backtest.Backtester
import com.bainance.tradess.data.Backoff
import com.bainance.tradess.data.BinanceException
import com.bainance.tradess.data.BinanceParser
import com.bainance.tradess.data.Candle
import com.bainance.tradess.data.Staleness
import com.bainance.tradess.data.Timeframe
import com.bainance.tradess.signal.Analyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DataLayerTest {
    private val klineJson = """[[1499040000000,"0.01634790","0.80000000","0.01575800","0.01577100","148976.11","1499644799999","2434.19",308,"1756.87","28.46","0"]]"""

    @Test fun parsesKlines() {
        val c = BinanceParser.parseKlines(klineJson).single()
        assertEquals(1499040000000L, c.openTime); assertEquals(0.8, c.high, 1e-9); assertEquals(0.015771, c.close, 1e-9)
    }

    @Test fun emptyKlinesIsEmptyList() = assertTrue(BinanceParser.parseKlines("[]").isEmpty())

    @Test fun apiErrorBodyThrowsApi() {
        try { BinanceParser.parseKlines("""{"code":-1121,"msg":"Invalid symbol."}"""); fail() }
        catch (e: BinanceException.Api) { assertEquals(-1121, e.code) }
    }

    @Test fun malformedJsonThrowsParse() {
        try { BinanceParser.parseKlines("<html>"); fail() } catch (e: BinanceException.Parse) { }
        try { BinanceParser.parseKlines("""[[1,"x"]]"""); fail() } catch (e: BinanceException.Parse) { }
    }

    @Test fun rejectsInvalidCandle() {
        try { BinanceParser.parseKlines("""[[1,"1","0.5","2","1","1",2]]"""); fail() } catch (e: BinanceException.Parse) { }
    }

    @Test fun parsesExchangeInfoTradingOnly() {
        val body = """{"symbols":[
          {"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDT","isSpotTradingAllowed":true},
          {"symbol":"OLDUSDT","status":"BREAK","baseAsset":"OLD","quoteAsset":"USDT","isSpotTradingAllowed":true}]}"""
        val r = BinanceParser.parseExchangeInfo(body)
        assertEquals(listOf("BTCUSDT"), r.map { it.symbol }); assertEquals("USDT", r[0].quote)
    }

    @Test fun parsesTickersSkippingBadRows() {
        val body = """[{"symbol":"BTCUSDT","lastPrice":"60000.1","priceChangePercent":"1.5","quoteVolume":"1000"},{"symbol":"BAD"}]"""
        val t = BinanceParser.parseTickers(body)
        assertEquals(1, t.size); assertEquals(1.5, t[0].changePct, 1e-9)
    }

    @Test fun parsesStreamKline() {
        val msg = """{"e":"kline","E":1,"s":"BTCUSDT","k":{"t":1000,"T":1999,"s":"BTCUSDT","i":"1m","o":"1","c":"2","h":"3","l":"0.5","v":"10","x":true}}"""
        val k = BinanceParser.parseStreamKline(msg)!!
        assertTrue(k.closed); assertEquals("1m", k.interval); assertEquals(2.0, k.candle.close, 1e-9)
        assertNull(BinanceParser.parseStreamKline("""{"result":null,"id":1}"""))
        assertNull(BinanceParser.parseStreamKline("garbage"))
    }

    @Test fun timeframeMapping() {
        assertEquals(listOf("1m", "3m", "5m", "15m", "30m", "1h"), Timeframe.values().map { it.api })
        assertEquals(180_000L, Timeframe.M3.millis); assertEquals(3_600_000L, Timeframe.H1.millis)
        assertEquals(Timeframe.M15, Timeframe.fromApi("15m")); assertNull(Timeframe.fromApi("2h"))
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(500L, Backoff.delayMs(0)); assertEquals(1000L, Backoff.delayMs(1))
        assertEquals(4000L, Backoff.delayMs(3)); assertEquals(30_000L, Backoff.delayMs(10)); assertEquals(30_000L, Backoff.delayMs(500))
    }

    @Test fun staleness() {
        assertTrue(Staleness.isStale(null, 1000L))
        assertFalse(Staleness.isStale(1000L, 60_000L)); assertTrue(Staleness.isStale(1000L, 200_000L))
    }

    private fun candles(n: Int, f: (Int) -> Double) = (0 until n).map {
        val p = f(it)
        Candle(it * 60_000L, p, p + 1, p - 1, p + 0.2, 10.0, it * 60_000L + 59_999L)
    }

    @Test fun provisionalVsClosedSignal() {
        val cs = candles(120) { 100 + it * 0.5 }
        val now = cs.last().closeTime - 1_000L                  // last candle still forming
        val a = Analyzer.analyze(Timeframe.M1, cs, now)
        assertNotNull(a.provisional); assertTrue(a.provisional!!.provisional)
        assertFalse(a.closed!!.provisional); assertEquals(cs[cs.size - 2].closeTime, a.closed!!.candleCloseTime)
        val b = Analyzer.analyze(Timeframe.M1, cs, cs.last().closeTime + 1)
        assertNull(b.provisional)
    }

    @Test fun backtestTooLittleDataIsReported() {
        val r = Backtester.run(candles(30) { 100.0 })
        assertEquals(0, r.samples); assertNotNull(r.message); assertTrue(r.message!!.contains("Not enough data"))
    }

    @Test fun backtestFillsAtNextOpenWithCosts() {
        val cs = candles(150) { 100 + it * 0.3 }
        val cfg = BacktestConfig(feePct = 0.1, slippagePct = 0.05, warmup = 60)
        // forced rule: long on candles 70..79 only; decision at close of i, fill at open of i+1
        val r = Backtester.run(cs, cfg) { _, i -> i in 70..79 }
        assertEquals(1, r.samples)
        val t = r.trades[0]
        assertEquals(cs[71].open * 1.0005, t.entryPrice, 1e-9)       // next candle open + slippage
        assertEquals(cs[81].open * 0.9995, t.exitPrice, 1e-9)        // signal off at close of 80 -> fill at open of 81
        val expected = (t.exitPrice / t.entryPrice) * 0.999 * 0.999 - 1
        assertEquals(expected * 100, t.returnPct, 1e-9)
        assertEquals(cs[71].openTime, t.entryTime)
    }

    @Test fun backtestMetricsOnLossAndWin() {
        val cs = candles(200) { if (it < 100) 100 + it * 0.5 else 150 - (it - 100) * 0.5 }
        val r = Backtester.run(cs, BacktestConfig(0.0, 0.0, 60)) { _, i -> i in 70..79 || i in 110..119 }
        assertEquals(2, r.samples)
        assertEquals(50.0, r.winRatePct, 1e-9)
        assertTrue(r.maxDrawdownPct > 0)
    }
}

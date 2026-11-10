package com.bainance.tradess.data

enum class Timeframe(val api: String, val millis: Long) {
    M1("1m", 60_000L),
    M3("3m", 180_000L),
    M5("5m", 300_000L),
    M15("15m", 900_000L),
    M30("30m", 1_800_000L),
    H1("1h", 3_600_000L);

    val label: String get() = api

    companion object {
        fun fromApi(value: String): Timeframe? = values().firstOrNull { it.api == value }
    }
}

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

data class SymbolInfo(val symbol: String, val base: String, val quote: String)

data class Ticker(val symbol: String, val lastPrice: Double, val changePct: Double, val quoteVolume: Double)

data class MarketRow(
    val symbol: String,
    val base: String,
    val quote: String,
    val price: Double,
    val changePct: Double,
    val quoteVolume: Double
)

/** A kline pushed by the WebSocket stream. [closed] is Binance's "x" flag. */
data class StreamKline(val interval: String, val candle: Candle, val closed: Boolean)

sealed class BinanceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Api(val code: Int, val apiMessage: String) : BinanceException("Binance error $code: $apiMessage")
    class Http(val status: Int) : BinanceException("HTTP $status")
    class RateLimit(val retryAfterSec: Long?) : BinanceException("Rate limited")
    class Network(cause: Throwable?) : BinanceException("Network error", cause)
    class Parse(message: String, cause: Throwable? = null) : BinanceException(message, cause)
}

object Backoff {
    /** Exponential backoff: base * 2^attempt, capped. attempt starts at 0. */
    fun delayMs(attempt: Int, baseMs: Long = 500L, maxMs: Long = 30_000L): Long {
        val shift = attempt.coerceIn(0, 20)
        return minOf(maxMs, baseMs shl shift)
    }
}

object Staleness {
    const val DEFAULT_THRESHOLD_MS = 120_000L
    fun isStale(lastUpdatedMs: Long?, nowMs: Long, thresholdMs: Long = DEFAULT_THRESHOLD_MS): Boolean =
        lastUpdatedMs == null || nowMs - lastUpdatedMs > thresholdMs
}

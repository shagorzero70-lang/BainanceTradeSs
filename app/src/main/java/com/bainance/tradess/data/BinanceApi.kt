package com.bainance.tradess.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Public Binance Spot market-data REST client. No API key, no signed endpoints.
 * Hosts are tried in order: api.binance.com, then data-api.binance.vision (market data only).
 */
class BinanceApi(
    val client: OkHttpClient = defaultClient(),
    private val hosts: List<String> = DEFAULT_HOSTS,
    private val maxRetries: Int = 2
) {
    suspend fun exchangeInfo(): List<SymbolInfo> =
        BinanceParser.parseExchangeInfo(get("/api/v3/exchangeInfo?permissions=SPOT"))

    suspend fun tickers24h(): List<Ticker> =
        BinanceParser.parseTickers(get("/api/v3/ticker/24hr"))

    suspend fun klines(symbol: String, tf: Timeframe, limit: Int = 500): List<Candle> =
        BinanceParser.parseKlines(
            get("/api/v3/klines?symbol=${symbol.uppercase()}&interval=${tf.api}&limit=${limit.coerceIn(1, 1000)}")
        )

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        var last: BinanceException? = null
        for (host in hosts) {
            try {
                return@withContext getWithRetry(host + path)
            } catch (e: BinanceException.RateLimit) {
                throw e                      // never hammer another host when limited
            } catch (e: BinanceException.Api) {
                throw e                      // request itself is wrong (e.g. invalid symbol)
            } catch (e: BinanceException) {
                last = e                     // network / region / 5xx -> try next host
            }
        }
        throw last ?: BinanceException.Network(null)
    }

    private suspend fun getWithRetry(url: String): String {
        var attempt = 0
        while (true) {
            try {
                return execute(url)
            } catch (e: BinanceException.Network) {
                if (attempt >= maxRetries) throw e
            } catch (e: BinanceException.Http) {
                if (e.status < 500 || attempt >= maxRetries) throw e
            }
            delay(Backoff.delayMs(attempt))
            attempt++
        }
    }

    private suspend fun execute(url: String): String {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw BinanceException.Network(e)
        }
        response.use { r ->
            val body = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw BinanceException.Network(e)
            }
            when {
                r.isSuccessful -> return body
                r.code == 429 || r.code == 418 ->
                    throw BinanceException.RateLimit(r.header("Retry-After")?.toLongOrNull())
                else -> {
                    BinanceParser.tryApiError(body)?.let { if (r.code in 400..499 && r.code != 451) throw it }
                    throw BinanceException.Http(r.code)
                }
            }
        }
    }

    companion object {
        val DEFAULT_HOSTS = listOf("https://api.binance.com", "https://data-api.binance.vision")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/** Cancelling the coroutine cancels the underlying HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

package com.bainance.tradess.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed class StreamEvent {
    object Connecting : StreamEvent()
    object Connected : StreamEvent()
    data class Reconnecting(val attempt: Int, val inMs: Long) : StreamEvent()
    data class Kline(val value: StreamKline) : StreamEvent()
}

/**
 * Single-symbol kline WebSocket with automatic reconnect (exponential backoff).
 * Cancelling the collector closes the socket. Binance drops connections after 24h; we reconnect.
 */
class KlineStream(private val client: OkHttpClient) {
    private val hosts = listOf("wss://stream.binance.com:9443", "wss://data-stream.binance.vision")

    fun klines(symbol: String, tf: Timeframe): Flow<StreamEvent> = callbackFlow {
        val streamName = "${symbol.lowercase()}@kline_${tf.api}"
        val job = launch {
            var attempt = 0
            var hostIdx = 0
            while (isActive) {
                trySend(StreamEvent.Connecting)
                val closed = CompletableDeferred<Unit>()
                val request = Request.Builder().url("${hosts[hostIdx % hosts.size]}/ws/$streamName").build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        attempt = 0
                        trySend(StreamEvent.Connected)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        BinanceParser.parseStreamKline(text)?.let { trySend(StreamEvent.Kline(it)) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        closed.complete(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(Unit)
                    }
                })
                try {
                    closed.await()
                } finally {
                    ws.cancel()
                }
                val wait = Backoff.delayMs(attempt)
                attempt++
                hostIdx++                      // alternate hosts on repeated failures
                trySend(StreamEvent.Reconnecting(attempt, wait))
                delay(wait)
            }
        }
        awaitClose { job.cancel() }
    }
}

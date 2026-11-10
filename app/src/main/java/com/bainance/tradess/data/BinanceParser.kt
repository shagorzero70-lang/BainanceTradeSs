package com.bainance.tradess.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Pure JSON parsing + validation (no Android dependencies, unit-testable). */
object BinanceParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): JsonElement = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw BinanceException.Parse("Invalid JSON", e)
    }

    /** Returns an Api exception if the body looks like {"code":-1121,"msg":"..."}; otherwise null. */
    fun tryApiError(body: String): BinanceException.Api? = try {
        val el = json.parseToJsonElement(body)
        if (el is JsonObject && el.containsKey("code") && el.containsKey("msg")) {
            BinanceException.Api(el["code"]!!.jsonPrimitive.int, el["msg"]!!.jsonPrimitive.content)
        } else null
    } catch (e: Exception) {
        null
    }

    private fun JsonElement.num(field: String): Double {
        val v = this.jsonPrimitive.content.toDouble()
        if (!v.isFinite()) throw BinanceException.Parse("Non-finite number in $field")
        return v
    }

    private fun checkArray(body: String): JsonArray {
        val el = parse(body)
        if (el is JsonObject) {
            tryApiError(body)?.let { throw it }
            throw BinanceException.Parse("Expected JSON array")
        }
        return el as? JsonArray ?: throw BinanceException.Parse("Expected JSON array")
    }

    private fun validate(c: Candle): Candle {
        if (c.open <= 0 || c.high <= 0 || c.low <= 0 || c.close <= 0 || c.volume < 0)
            throw BinanceException.Parse("Non-positive candle values")
        if (c.high < c.low) throw BinanceException.Parse("Candle high < low")
        if (c.closeTime < c.openTime) throw BinanceException.Parse("Candle close before open")
        return c
    }

    /** /api/v3/klines -> list of candles (empty array => empty list). */
    fun parseKlines(body: String): List<Candle> {
        val arr = checkArray(body)
        return try {
            arr.map { row ->
                val r = row.jsonArray
                if (r.size < 7) throw BinanceException.Parse("Kline row too short")
                validate(
                    Candle(
                        openTime = r[0].jsonPrimitive.long,
                        open = r[1].num("open"),
                        high = r[2].num("high"),
                        low = r[3].num("low"),
                        close = r[4].num("close"),
                        volume = r[5].num("volume"),
                        closeTime = r[6].jsonPrimitive.long
                    )
                )
            }.sortedBy { it.openTime }
        } catch (e: BinanceException) {
            throw e
        } catch (e: Exception) {
            throw BinanceException.Parse("Malformed kline data", e)
        }
    }

    /** /api/v3/exchangeInfo -> TRADING symbols only. */
    fun parseExchangeInfo(body: String): List<SymbolInfo> {
        val el = parse(body)
        tryApiError(body)?.let { throw it }
        val symbols = (el as? JsonObject)?.get("symbols") as? JsonArray
            ?: throw BinanceException.Parse("exchangeInfo has no symbols")
        return try {
            symbols.map { it.jsonObject }
                .filter { it["status"]?.jsonPrimitive?.content == "TRADING" }
                .filter { (it["isSpotTradingAllowed"] as? JsonPrimitive)?.booleanOrNull != false }
                .map {
                    SymbolInfo(
                        it["symbol"]!!.jsonPrimitive.content,
                        it["baseAsset"]!!.jsonPrimitive.content,
                        it["quoteAsset"]!!.jsonPrimitive.content
                    )
                }
        } catch (e: Exception) {
            throw BinanceException.Parse("Malformed exchangeInfo", e)
        }
    }

    /** /api/v3/ticker/24hr (all symbols) -> tickers. Rows with invalid numbers are skipped. */
    fun parseTickers(body: String): List<Ticker> {
        val arr = checkArray(body)
        return arr.mapNotNull { e ->
            try {
                val o = e.jsonObject
                Ticker(
                    o["symbol"]!!.jsonPrimitive.content,
                    o["lastPrice"]!!.num("lastPrice"),
                    o["priceChangePercent"]!!.num("priceChangePercent"),
                    o["quoteVolume"]!!.num("quoteVolume")
                )
            } catch (ex: Exception) {
                null
            }
        }
    }

    /** WebSocket payload {"e":"kline","k":{...}} -> StreamKline, or null for other messages. */
    fun parseStreamKline(text: String): StreamKline? = try {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["e"]?.jsonPrimitive?.content != "kline") null
        else {
            val k = root["k"]!!.jsonObject
            StreamKline(
                interval = k["i"]!!.jsonPrimitive.content,
                candle = validate(
                    Candle(
                        openTime = k["t"]!!.jsonPrimitive.long,
                        open = k["o"]!!.num("o"),
                        high = k["h"]!!.num("h"),
                        low = k["l"]!!.num("l"),
                        close = k["c"]!!.num("c"),
                        volume = k["v"]!!.num("v"),
                        closeTime = k["T"]!!.jsonPrimitive.long
                    )
                ),
                closed = k["x"]!!.jsonPrimitive.booleanOrNull ?: false
            )
        }
    } catch (e: Exception) {
        null
    }
}

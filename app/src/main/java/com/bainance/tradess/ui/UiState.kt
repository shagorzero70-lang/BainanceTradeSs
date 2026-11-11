package com.bainance.tradess.ui

import com.bainance.tradess.backtest.BacktestResult
import com.bainance.tradess.data.Candle
import com.bainance.tradess.data.MarketRow
import com.bainance.tradess.data.Timeframe
import com.bainance.tradess.signal.TfAnalysis

enum class Screen { LIST, DASHBOARD, BACKTEST, INFO, EXTRAS }
enum class SortMode(val label: String) {
    VOLUME("Volume"), CHANGE_DESC("24h ▲"), CHANGE_ASC("24h ▼"), NAME("A–Z")
}
enum class ConnStatus(val label: String) {
    IDLE("Paused"), CONNECTING("Connecting…"), LIVE("Live (WebSocket)"),
    RECONNECTING("Reconnecting…"), OFFLINE("Offline")
}

data class BacktestUi(
    val loading: Boolean = false,
    val result: BacktestResult? = null,
    val error: String? = null
)

data class UiState(
    val screen: Screen = Screen.LIST,
    // market list
    val rows: List<MarketRow> = emptyList(),
    val listLoading: Boolean = false,
    val listError: String? = null,
    val listUpdated: Long? = null,
    val query: String = "",
    val quote: String = "USDT",
    val sort: SortMode = SortMode.VOLUME,
    val favoritesOnly: Boolean = false,
    val favorites: Set<String> = emptySet(),
    // dashboard
    val selected: String? = null,
    val timeframe: Timeframe = Timeframe.M5,
    val candles: Map<Timeframe, List<Candle>> = emptyMap(),
    val analysis: Map<Timeframe, TfAnalysis> = emptyMap(),
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val conn: ConnStatus = ConnStatus.IDLE,
    val lastUpdated: Long? = null,
    // backtest
    val backtest: BacktestUi = BacktestUi()
) {
    val selectedRow: MarketRow? get() = rows.firstOrNull { it.symbol == selected }

    val quoteOptions: List<String>
        get() = listOf("USDT", "USDC", "FDUSD", "BTC", "ETH", "BNB").filter { q -> rows.any { it.quote == q } }
            .ifEmpty { listOf("USDT") }

    val visibleRows: List<MarketRow>
        get() {
            val q = query.trim().uppercase()
            val filtered = rows.filter {
                it.quote == quote &&
                    (q.isEmpty() || it.symbol.contains(q) || it.base.contains(q)) &&
                    (!favoritesOnly || it.symbol in favorites)
            }
            return when (sort) {
                SortMode.VOLUME -> filtered.sortedByDescending { it.quoteVolume }
                SortMode.CHANGE_DESC -> filtered.sortedByDescending { it.changePct }
                SortMode.CHANGE_ASC -> filtered.sortedBy { it.changePct }
                SortMode.NAME -> filtered.sortedBy { it.symbol }
            }
        }
}

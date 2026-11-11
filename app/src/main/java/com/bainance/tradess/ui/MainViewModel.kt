package com.bainance.tradess.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bainance.tradess.backtest.Backtester
import com.bainance.tradess.data.BinanceApi
import com.bainance.tradess.data.BinanceException
import com.bainance.tradess.data.Candle
import com.bainance.tradess.data.FavoritesStore
import com.bainance.tradess.data.KlineStream
import com.bainance.tradess.data.MarketRow
import com.bainance.tradess.data.StreamEvent
import com.bainance.tradess.data.SymbolInfo
import com.bainance.tradess.data.Timeframe
import com.bainance.tradess.signal.Analyzer
import com.bainance.tradess.signal.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val api = BinanceApi()
    private val stream = KlineStream(api.client)
    private val favStore = FavoritesStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var symbols: List<SymbolInfo> = emptyList()
    private var marketJob: Job? = null
    private var detailJob: Job? = null
    private var streamJob: Job? = null
    private var backtestJob: Job? = null
    private val maxCandles = 1000

    init {
        viewModelScope.launch { favStore.favorites.collect { f -> _state.update { it.copy(favorites = f) } } }
    }

    // ---------- lifecycle ----------
    /** Called from Activity ON_START / ON_STOP so sockets and polling never run in background. */
    fun setForeground(fg: Boolean) {
        if (fg) {
            startMarketLoop()
            if (_state.value.selected != null) startDetail()
        } else {
            marketJob?.cancel(); detailJob?.cancel(); streamJob?.cancel()
            _state.update { it.copy(conn = ConnStatus.IDLE) }
        }
    }

    // ---------- navigation / input ----------
    fun back() {
        _state.update {
            when (it.screen) {
                Screen.BACKTEST -> it.copy(screen = Screen.DASHBOARD)
                Screen.DASHBOARD -> { detailJob?.cancel(); streamJob?.cancel(); it.copy(screen = Screen.LIST, conn = ConnStatus.IDLE) }
                Screen.INFO -> it.copy(screen = Screen.LIST)
                Screen.EXTRAS -> it.copy(screen = Screen.LIST)
                Screen.LIST -> it
            }
        }
    }
    fun openInfo() = _state.update { it.copy(screen = Screen.INFO) }
    fun openExtras() = _state.update { it.copy(screen = Screen.EXTRAS) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setQuote(q: String) = _state.update { it.copy(quote = q) }
    fun setSort(s: SortMode) = _state.update { it.copy(sort = s) }
    fun toggleFavoritesOnly() = _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun toggleFavorite(symbol: String) { viewModelScope.launch { favStore.toggle(symbol) } }

    fun select(symbol: String) {
        _state.update {
            it.copy(screen = Screen.DASHBOARD, selected = symbol, candles = emptyMap(), analysis = emptyMap(),
                detailError = null, lastUpdated = null, backtest = BacktestUi())
        }
        startDetail()
    }

    fun selectTimeframe(tf: Timeframe) {
        if (tf == _state.value.timeframe) return
        _state.update { it.copy(timeframe = tf) }
        startStream()
    }

    fun refresh() {
        startMarketLoop()
        if (_state.value.selected != null) startDetail()
    }

    // ---------- market list ----------
    private fun startMarketLoop() {
        marketJob?.cancel()
        marketJob = viewModelScope.launch {
            while (isActive) {
                var wait = 60_000L           // ticker/24hr (all symbols) costs weight 80; once a minute is safe
                _state.update { it.copy(listLoading = it.rows.isEmpty(), listError = null) }
                try {
                    if (symbols.isEmpty()) symbols = api.exchangeInfo()
                    val tickers = api.tickers24h().associateBy { it.symbol }
                    val rows = symbols.mapNotNull { s ->
                        tickers[s.symbol]?.let {
                            MarketRow(s.symbol, s.base, s.quote, it.lastPrice, it.changePct, it.quoteVolume)
                        }
                    }
                    _state.update { it.copy(rows = rows, listLoading = false, listUpdated = System.currentTimeMillis()) }
                } catch (e: BinanceException) {
                    _state.update { it.copy(listLoading = false, listError = describe(e)) }
                    if (e is BinanceException.RateLimit) wait = maxOf(wait, (e.retryAfterSec ?: 60L) * 1000L)
                }
                delay(wait)
            }
        }
    }

    // ---------- dashboard ----------
    private fun startDetail() {
        val sym = _state.value.selected ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _state.update { it.copy(detailLoading = it.candles.isEmpty(), detailError = null) }
            loadTimeframes(sym, Timeframe.values().toList())
            _state.update { it.copy(detailLoading = false) }
            startStream()
            while (isActive) {
                delay(45_000)
                // WebSocket keeps the selected timeframe fresh; poll the others via REST.
                val others = if (_state.value.conn == ConnStatus.LIVE)
                    Timeframe.values().filter { it != _state.value.timeframe } else Timeframe.values().toList()
                loadTimeframes(sym, others)
            }
        }
    }

    private suspend fun loadTimeframes(sym: String, tfs: List<Timeframe>) {
        val results = coroutineScope {
            tfs.map { tf ->
                async {
                    try { tf to Result.success(api.klines(sym, tf, 500)) }
                    catch (e: BinanceException) { tf to Result.failure<List<Candle>>(e) }
                }
            }.awaitAll()
        }
        var firstError: BinanceException? = null
        val now = System.currentTimeMillis()
        for ((tf, r) in results) {
            val list = r.getOrNull()
            if (list != null) {
                val a = withContext(Dispatchers.Default) { Analyzer.analyze(tf, list, now) }
                _state.update { it.copy(candles = it.candles + (tf to list), analysis = it.analysis + (tf to a), lastUpdated = now) }
            } else {
                firstError = firstError ?: (r.exceptionOrNull() as? BinanceException)
            }
        }
        _state.update {
            it.copy(detailError = if (firstError != null && it.candles.isEmpty()) describe(firstError) else if (firstError != null) "Some data is stale: " + describe(firstError) else null,
                conn = if (firstError is BinanceException.Network && it.conn != ConnStatus.LIVE) ConnStatus.OFFLINE else it.conn)
        }
    }

    private fun startStream() {
        streamJob?.cancel()
        val sym = _state.value.selected ?: return
        val tf = _state.value.timeframe
        streamJob = viewModelScope.launch {
            stream.klines(sym, tf).collect { ev ->
                when (ev) {
                    StreamEvent.Connecting -> _state.update { it.copy(conn = ConnStatus.CONNECTING) }
                    StreamEvent.Connected -> _state.update { it.copy(conn = ConnStatus.LIVE) }
                    is StreamEvent.Reconnecting -> _state.update { it.copy(conn = ConnStatus.RECONNECTING) }
                    is StreamEvent.Kline -> applyKline(sym, tf, ev.value.candle)
                }
            }
        }
    }

    private suspend fun applyKline(sym: String, tf: Timeframe, c: Candle) {
        val cur = _state.value.candles[tf] ?: return          // wait for REST history first
        val merged = when {
            cur.isEmpty() -> return
            cur.last().openTime == c.openTime -> cur.dropLast(1) + c
            c.openTime > cur.last().openTime -> (cur + c).takeLast(maxCandles)
            else -> return                                       // stale/out-of-order message
        }
        val now = System.currentTimeMillis()
        val a = withContext(Dispatchers.Default) { Analyzer.analyze(tf, merged, now) }
        val oldSignal = _state.value.analysis[tf]?.closed
        val newSignal = a.closed
        if (oldSignal != null && newSignal != null && newSignal.enoughData &&
            newSignal.direction != Direction.NEUTRAL && newSignal.direction != oldSignal.direction
        ) postSignalNotification(sym, tf.label, newSignal.direction.name)
        _state.update {
            if (it.timeframe != tf) it
            else it.copy(candles = it.candles + (tf to merged), analysis = it.analysis + (tf to a), lastUpdated = now, detailError = null)
        }
    }

    // ---------- backtest ----------
    fun openBacktest() {
        _state.update { it.copy(screen = Screen.BACKTEST) }
        runBacktest()
    }

    fun runBacktest() {
        val sym = _state.value.selected ?: return
        val tf = _state.value.timeframe
        backtestJob?.cancel()
        backtestJob = viewModelScope.launch {
            _state.update { it.copy(backtest = BacktestUi(loading = true)) }
            try {
                val candles = api.klines(sym, tf, 1000)
                val now = System.currentTimeMillis()
                val closedOnly = candles.filter { it.closeTime <= now }
                val result = withContext(Dispatchers.Default) { Backtester.run(closedOnly) }
                _state.update { it.copy(backtest = BacktestUi(result = result)) }
            } catch (e: BinanceException) {
                _state.update { it.copy(backtest = BacktestUi(error = describe(e))) }
            }
        }
    }

    private fun postSignalNotification(symbol: String, timeframe: String, direction: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val channelId = "bainance_signal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Signal changes", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$symbol · $timeframe signal changed")
            .setContentText("Closed-candle signal: $direction. Check the app before making any decision.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(app).notify((symbol + timeframe).hashCode(), notification)
    }

    private fun describe(e: BinanceException): String = when (e) {
        is BinanceException.RateLimit -> "Binance rate limit reached (HTTP 429/418)." +
            (e.retryAfterSec?.let { " Retry in ${it}s." } ?: " Please wait a minute.")
        is BinanceException.Network -> "No connection to Binance. Check your internet (offline?)."
        is BinanceException.Http ->
            if (e.status == 451 || e.status == 403) "Binance is not available in your region/network (HTTP ${e.status})."
            else "Binance server error (HTTP ${e.status}). Try again shortly."
        is BinanceException.Api -> "Binance rejected the request: ${e.apiMessage}"
        is BinanceException.Parse -> "Unexpected data from Binance: ${e.message}"
    }
}

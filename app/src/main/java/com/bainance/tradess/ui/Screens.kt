@file:OptIn(ExperimentalMaterial3Api::class)

package com.bainance.tradess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainance.tradess.data.Staleness
import com.bainance.tradess.data.Timeframe
import com.bainance.tradess.indicators.Snapshot
import com.bainance.tradess.signal.TfAnalysis

@Composable
fun AppRoot(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val themePrefs = remember {
        appContext.getSharedPreferences("bainance_prefs", android.content.Context.MODE_PRIVATE)
    }
    var themeMode by remember {
        mutableStateOf(
            runCatching {
                AppThemeMode.valueOf(themePrefs.getString("theme_mode", "DARK") ?: "DARK")
            }.getOrDefault(AppThemeMode.DARK)
        )
    }
    androidx.compose.runtime.LaunchedEffect(themeMode) {
        themePrefs.edit().putString("theme_mode", themeMode.name).apply()
    }

    BackHandler(enabled = s.screen != Screen.LIST) { vm.back() }

    AppTheme(mode = themeMode) {
        Box(Modifier.fillMaxSize().background(Bg).navigationBarsPadding()) {
            when (s.screen) {
                Screen.LIST -> ListScreen(s, vm, themeMode) { themeMode = it }
                Screen.DASHBOARD -> DashboardScreen(s, vm)
                Screen.BACKTEST -> BacktestScreen(s, vm)
                Screen.INFO -> InfoScreen(vm)
                Screen.EXTRAS -> ExtrasScreen(vm)
            }
        }
    }
}

// ------------------------------------------------------------------ list
@Composable
fun ListScreen(s: UiState, vm: MainViewModel, themeMode: AppThemeMode, onThemeChange: (AppThemeMode) -> Unit) {
    val rows = s.visibleRows
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Bainance Trade Ss", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Amber)
            Row {
                TextButton(onClick = vm::openExtras) { Text("News / Tools") }
                TextButton(onClick = vm::openInfo) { Text("About") }
            }
        }
        Disclaimer()
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AppThemeMode.values().forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onThemeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }
        OutlinedTextField(
            value = s.query, onValueChange = vm::setQuery, singleLine = true,
            label = { Text("Search coin (খুঁজুন) e.g. BTC") }, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.quoteOptions.forEach { q -> FilterChip(selected = s.quote == q, onClick = { vm.setQuote(q) }, label = { Text(q) }) }
            FilterChip(selected = s.favoritesOnly, onClick = vm::toggleFavoritesOnly, label = { Text("★ Watchlist") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SortMode.values().forEach { m -> FilterChip(selected = s.sort == m, onClick = { vm.setSort(m) }, label = { Text(m.label) }) }
        }
        s.listError?.let { Banner(it, onRetry = vm::refresh) }
        if (s.listUpdated != null) {
            val stale = Staleness.isStale(s.listUpdated, System.currentTimeMillis(), 180_000L)
            Text("Updated ${fmtTime(s.listUpdated)}" + if (stale) " · data may be stale" else "", color = if (stale) Amber else Muted, fontSize = 11.sp)
        }
        when {
            s.listLoading && s.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            s.rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if (s.listError == null) "No market data yet" else "No data (see error above)", color = Muted)
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No coins match your filters", color = Muted) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(rows, key = { it.symbol }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(r.symbol) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (r.symbol in s.favorites) "★" else "☆", color = Amber, fontSize = 20.sp,
                            modifier = Modifier.clickable { vm.toggleFavorite(r.symbol) }.padding(end = 10.dp, start = 2.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${r.base}/${r.quote}", fontWeight = FontWeight.SemiBold)
                            Text("Vol ${fmtVol(r.quoteVolume)}", color = Muted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtPrice(r.price))
                            Text("%+.2f%%".format(r.changePct), color = if (r.changePct >= 0) Green else Red, fontSize = 12.sp)
                        }
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Showing ${rows.size} of ${s.filteredRowCount} coins", color = Muted, fontSize = 12.sp)
                        if (s.hasMoreRows) {
                            Button(onClick = vm::loadMore) {
                                Text("Load More")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dashboard
@Composable
fun DashboardScreen(s: UiState, vm: MainViewModel) {
    val sym = s.selected ?: return
    val row = s.selectedRow
    val a: TfAnalysis? = s.analysis[s.timeframe]
    val series = a?.series
    val stale = Staleness.isStale(s.lastUpdated, System.currentTimeMillis())
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::back) { Text("← Back") }
                Column(Modifier.weight(1f)) {
                    Text(row?.let { "${it.base}/${it.quote}" } ?: sym, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    row?.let { Text("${fmtPrice(it.price)}  ${"%+.2f%%".format(it.changePct)} (24h)", color = if (it.changePct >= 0) Green else Red, fontSize = 13.sp) }
                }
                Text(if (sym in s.favorites) "★" else "☆", color = Amber, fontSize = 24.sp,
                    modifier = Modifier.clickable { vm.toggleFavorite(sym) }.padding(8.dp))
            }
        }
        item {
            val connColor = when (s.conn) { ConnStatus.LIVE -> Green; ConnStatus.OFFLINE -> Red; else -> Amber }
            Text("Market: Binance Spot (24/7) · ● ${s.conn.label} · Updated ${fmtTime(s.lastUpdated)}" + if (stale && s.lastUpdated != null) " · STALE" else "",
                color = connColor, fontSize = 12.sp)
        }
        item { Disclaimer() }
        s.detailError?.let { item { Banner(it, onRetry = vm::refresh) } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Timeframe.values().forEach { tf ->
                    FilterChip(selected = s.timeframe == tf, onClick = { vm.selectTimeframe(tf) }, label = { Text(tf.label) })
                }
            }
        }
        if (s.detailLoading) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
        } else if (a == null || series == null) {
            item { Text(if (s.detailError == null) "No candle data for this timeframe." else "No data to display.", color = Muted) }
        } else {
            item {
                Column(Modifier.background(Card, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    Text("EMA9 (gold) · EMA21 (blue) · EMA50 (purple) · volume bars", color = Muted, fontSize = 10.sp)
                    CandleChart(series)
                }
            }
            item { SignalCard(sym, s.timeframe.label, "Closed-candle signal", a.closed, s.lastUpdated) }
            if (a.provisional != null) item { SignalCard(sym, s.timeframe.label, "Provisional (live candle)", a.provisional, s.lastUpdated) }
            item { IndicatorCard(a.latest) }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("All timeframes (closed candles)", color = Muted, fontSize = 12.sp)
                Timeframe.values().forEach { tf ->
                    val sig = s.analysis[tf]?.closed
                    Row(Modifier.fillMaxWidth().clickable { vm.selectTimeframe(tf) }.padding(vertical = 3.dp)) {
                        Text(tf.label, Modifier.width(48.dp), fontWeight = FontWeight.SemiBold)
                        if (sig == null) Text("loading / no data", color = Muted, fontSize = 13.sp)
                        else if (!sig.enoughData) Text("Not enough data", color = Muted, fontSize = 13.sp)
                        else Text("${sig.direction.name} · ${sig.bias.label} · ${"%+d".format(sig.score)}", color = dirColor(sig.direction), fontSize = 13.sp)
                    }
                }
            }
        }
        item { Button(onClick = vm::openBacktest, modifier = Modifier.fillMaxWidth()) { Text("Backtest ${s.timeframe.label} rules") } }
        item { Spacer(Modifier.padding(16.dp)) }
    }
}

@Composable
fun IndicatorCard(s: Snapshot?) {
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Indicators (latest candle)", color = Muted, fontSize = 12.sp)
        if (s == null) { Text("Not enough data", color = Muted); return }
        fun p(v: Double) = fmtPrice(v)
        listOf(
            "EMA 9 / 21 / 50" to "${p(s.ema9)} / ${p(s.ema21)} / ${p(s.ema50)}",
            "RSI 14" to fmtNum(s.rsi, 1),
            "MACD / Signal / Hist" to "${fmtNum(s.macd, 6)} / ${fmtNum(s.macdSignal, 6)} / ${fmtNum(s.macdHist, 6)}",
            "Stoch %K / %D" to "${fmtNum(s.stochK, 1)} / ${fmtNum(s.stochD, 1)}",
            "Bollinger U / M / L" to "${p(s.bbUpper)} / ${p(s.bbMiddle)} / ${p(s.bbLower)}",
            "ATR 14" to p(s.atr)
        ).forEach { (k, v) ->
            Column { Text(k, color = Muted, fontSize = 11.sp); Text(v, fontSize = 13.sp) }
        }
    }
}

// ------------------------------------------------------------------ backtest
@Composable
fun BacktestScreen(s: UiState, vm: MainViewModel) {
    val b = s.backtest
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::back) { Text("← Back") }
                Text("Backtest · ${s.selected} · ${s.timeframe.label}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        item { Banner("Historical simulation only — not a guarantee of future performance.", Amber) }
        when {
            b.loading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
            b.error != null -> item { Banner(b.error, onRetry = vm::runBacktest) }
            b.result != null -> {
                val r = b.result
                item {
                    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Candles used: ${r.candlesUsed} (closed only)", color = Muted, fontSize = 12.sp)
                        Text("Samples (trades): ${r.samples}")
                        Text("Win rate: ${"%.1f".format(r.winRatePct)}%")
                        Text("Average return / trade: ${"%+.3f".format(r.avgReturnPct)}%")
                        Text("Total return (compounded): ${"%+.2f".format(r.totalReturnPct)}%")
                        Text("Max drawdown: ${"%.2f".format(r.maxDrawdownPct)}%")
                        Text("Buy & hold (same period): ${"%+.2f".format(r.buyHoldPct)}%", color = Muted)
                        Text("Assumptions: long-only · enter on UP, exit when not UP · fill at NEXT candle open · fee ${r.config.feePct}%/side · slippage ${r.config.slippagePct}%/side", color = Muted, fontSize = 11.sp)
                        r.message?.let { Text(it, color = Amber, fontSize = 12.sp) }
                    }
                }
                items(r.trades.takeLast(30).reversed()) { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtDateTime(t.entryTime), color = Muted, fontSize = 12.sp)
                        Text("%+.2f%%".format(t.returnPct), color = if (t.returnPct >= 0) Green else Red, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Button(onClick = vm::runBacktest, modifier = Modifier.fillMaxWidth()) { Text("Re-run") } }
    }
}

// ------------------------------------------------------------------ info
@Composable
fun InfoScreen(vm: MainViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = vm::back) { Text("← Back") } }
        item { Disclaimer() }
        item { Section("Financial-risk disclaimer", "Crypto trading is highly risky and you can lose all your money. Signals are produced by simple indicator rules; they are not predictions, not advice and not guaranteed. Backtests show past simulation only.\nক্রিপ্টো ট্রেডিং অত্যন্ত ঝুঁকিপূর্ণ। এই অ্যাপের সিগন্যাল নিশ্চিত নয়।") }
        item { Section("Privacy", "No account, no API key, no secret, no seed phrase or password is requested or stored. The app only reads public Binance Spot market data. Order placement and withdrawals are not implemented. Only your watchlist symbols are saved locally on this device. The only permission used is INTERNET.") }
        item { Section("Data source", "Binance public Spot REST (api.binance.com, fallback data-api.binance.vision) and WebSocket kline streams (stream.binance.com:9443). If Binance is blocked in your region or rate-limits you, the app shows an error instead of fake data.") }
        item { Section("Signal formula", "EMA stack ±2/±1, MACD ±1 (+±1 crossover), RSI ±1, Stochastic ±1, Bollinger ±1. Score ≥ +3 → UP / BUY BIAS, ≤ −3 → DOWN / SELL BIAS, otherwise NEUTRAL / WAIT. If bullish and bearish points are both ≥ 3 → conflicting → WAIT. The score is a tally, not a probability.") }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = Amber)
        Text(body, fontSize = 13.sp, color = Muted)
    }
}

# Bainance Trade Ss

Native Android app (Kotlin + Jetpack Compose) that shows Binance **Spot** public market data, candlestick charts,
technical indicators and a transparent rule-based UP/DOWN – BUY BIAS/SELL BIAS/WAIT view, plus a backtester.

**Analysis tool only.** Demo / Not financial advice. No orders, no withdrawals, no API keys, no credentials.
Package: `com.bainance.tradess`

> **Honest status:** this project was written without access to Android Studio, the Android SDK, Gradle or the
> network, so it has **NOT been compiled, run or unit-tested yet**. Indicator test expectations were cross-checked
> with an independent Python calculation, but you must run the build and tests yourself (steps below) and see the
> checklist at the end. REST endpoints were written from API knowledge; the WebSocket base endpoints and kline payload
> were checked against the current Binance docs. If Binance changes anything, adjust `BinanceApi.kt` / `BinanceParser.kt`.

## Requirements
- Android Studio Koala (2024.1.1) or newer · JDK 17 (bundled with Android Studio) · Android SDK Platform 34
- Gradle 8.7 (downloaded automatically by Android Studio from `gradle/wrapper/gradle-wrapper.properties`)
- Device/emulator with Android 8.0+ (API 26+) and internet

## Build & run
1. Android Studio → **Open** → select the `BainanceTradeSs` folder → wait for Gradle sync (needs internet once).
2. Select a device/emulator → **Run ▶**.

## Debug APK
Android Studio: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
Command line (Linux/macOS; use `gradlew.bat` on Windows). This folder has no `gradlew` script/jar (binary files could
not be generated here). Create them once, with Gradle 8.7 installed (`brew install gradle` / sdkman), then build:
```
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```
(Or simply run `gradle assembleDebug` with Gradle 8.7+ and JDK 17; set `ANDROID_HOME` or create `local.properties`
with `sdk.dir=/path/to/Android/Sdk`.)

APK location: `app/build/outputs/apk/debug/app-debug.apk`
Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy to the phone and allow "install unknown apps".

## Tests
```
./gradlew testDebugUnitTest
```
Report: `app/build/reports/tests/testDebugUnitTest/index.html`. Covered: indicators (EMA/SMA/RSI/MACD/Stoch/BB/ATR,
NaN for insufficient data, no look-ahead), signal scoring (up/down/conflict/threshold/not-enough-data), JSON parsing
(klines, empty, error body, malformed, exchangeInfo, tickers, stream kline), timeframe mapping, backoff schedule,
staleness, provisional-vs-closed signal, backtest fills/fees/metrics.
Not unit-tested (verify manually on a device): live WebSocket reconnect (toggle airplane mode while on the dashboard,
status goes Reconnecting → Live), HTTP cancellation (switch coins quickly), rate-limit banner.

## Architecture
```
MainActivity ─ lifecycle ON_START/ON_STOP ─▶ MainViewModel (StateFlow<UiState>)
   data/      BinanceApi (REST, retry+backoff, host fallback, cancellable) · BinanceParser (pure, validated)
              KlineStream (WebSocket flow, auto-reconnect) · FavoritesStore (DataStore) · Models
   indicators/ Indicators (pure math) · IndicatorSet/Snapshot
   signal/    SignalEngine (scoring) · Analyzer (closed vs provisional, per timeframe)
   backtest/  Backtester (long-only, next-open fills, fees, slippage)
   ui/        Compose screens: List, Dashboard (chart/signals/indicators), Backtest, Info; CandleChart (Canvas)
```
Data flow: `exchangeInfo` + `ticker/24hr` (every 60 s) → coin list. Selecting a coin → `klines` (500 candles) for all 6
timeframes in parallel → indicators → signals. WebSocket `<symbol>@kline_<tf>` updates the selected timeframe live;
other timeframes are re-polled via REST every 45 s. Everything stops when the app leaves the foreground.

## Endpoints (public, no key)
- REST: `https://api.binance.com`, fallback `https://data-api.binance.vision`
  - `GET /api/v3/exchangeInfo?permissions=SPOT` · `GET /api/v3/ticker/24hr` · `GET /api/v3/klines?symbol=&interval=&limit=`
- WebSocket: `wss://stream.binance.com:9443/ws/<symbol>@kline_<interval>` (fallback `wss://data-stream.binance.vision`)
- Intervals: `1m 3m 5m 15m 30m 1h`

## Rate limits
Binance weights requests per IP (≈6000/min on REST at time of writing; `ticker/24hr` for all symbols is heavy, so it is
called once per minute; `klines` is light). HTTP 429/418 → app shows a rate-limit message and backs off using
`Retry-After`; it never retries on another host when limited. WebSocket: ≤300 connection attempts / 5 min / IP – the app
uses one stream with exponential backoff (0.5 s → 30 s cap). Streams are dropped by Binance after 24 h; the app reconnects.
Always re-check current limits in the official Binance Spot API docs.

## Signal formula (UI shows score, threshold, each reason)
| Indicator | Points |
|---|---|
| EMA 9/21/50 | +2 if 9>21>50, −2 if reverse; else +1/−1 by EMA9 vs EMA21 |
| MACD 12/26/9 | ±1 by histogram sign, ±1 more on a fresh crossover |
| RSI 14 | +1 if <30 (oversold) or 55–70; −1 if >70 (overbought, caution) or 30–45 |
| Stoch 14,3,3 | +1 if %K>%D, −1 if %K<%D (zone noted in the reason) |
| Bollinger 20,2 | +1 above middle, −1 below; outside bands flips to −1 (above upper) / +1 (below lower) |

Score ∈ [−7, +7]. **≥ +3 → UP / BUY BIAS · ≤ −3 → DOWN / SELL BIAS · otherwise NEUTRAL / WAIT.**
If bullish points ≥ 3 and bearish points ≥ 3 → "Conflicting" → NEUTRAL / WAIT. The score is a tally, **not a probability**.
"Closed-candle" signal uses the last closed candle; "Provisional" uses the still-forming candle and is labelled as such
(closed/forming is decided with the device clock; keep it set to automatic time).

## Backtest
Long-only; signal at the close of candle *i* (using only data ≤ *i*), fill at the **open of candle *i+1***; fee 0.10 %/side,
slippage 0.05 %/side; enter on UP, exit at next open when no longer UP; open trade closed at last close. Reports samples,
win rate, avg return, compounded return, max drawdown (on closed-trade equity), buy-and-hold reference. Uses up to 1000
closed candles (≈16 h of 1m, ≈41 days of 1h). Small samples are flagged. Past simulation ≠ future results.

## Limitations
- Chart is a simple custom Canvas chart (no zoom/pan); no third-party chart library, so no extra licences.
- Only Binance.com public endpoints: if blocked in your region (HTTP 451/403) the app shows an error – no fake data.
- Rule-based indicators lag and fail in ranging/news-driven markets; the model is a teaching/analysis aid.
- Dependencies (Apache-2.0): AndroidX/Compose, OkHttp, kotlinx.coroutines/serialization.

## Troubleshooting
- *SDK location not found* → create `local.properties` with `sdk.dir=...` or open via Android Studio.
- *Unsupported class file major version / JDK* → use JDK 17 (Android Studio → Settings → Gradle → Gradle JDK).
- *Could not resolve dependencies* → internet/proxy; Google & Maven Central must be reachable.
- *Compose compiler version mismatch* → Kotlin 1.9.24 requires compose compiler 1.5.14 (already set).
- *App shows "not available in your region"* → use a network where Binance is reachable (do not bypass legal restrictions).
- *Empty list / error banner* → tap Retry; check device clock and connectivity.

## Final checklist (tick after you run it)
- [ ] Gradle sync + `assembleDebug` compiles
- [ ] `testDebugUnitTest` passes
- [ ] App launches, coin list loads (USDT pairs)
- [ ] Dashboard shows chart, status "Live (WebSocket)", updating time
- [ ] Indicators show values (or "Not enough data"); signals show reasons
- [ ] Airplane-mode toggle → Reconnecting → Live again
- [ ] Backtest screen returns results with assumptions

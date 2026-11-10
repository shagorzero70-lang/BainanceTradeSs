package com.bainance.tradess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Green = Color(0xFF26A69A)
val Red = Color(0xFFEF5350)
val Amber = Color(0xFFF0B90B)
val Bg = Color(0xFF0B0E11)
val Card = Color(0xFF181C22)
val Muted = Color(0xFF9AA4B2)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber, onPrimary = Color.Black, background = Bg, surface = Card,
            onSurface = Color(0xFFEAECEF), onBackground = Color(0xFFEAECEF)
        )
    ) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), content = { content() })
    }
}

fun fmtPrice(v: Double): String = when {
    v.isNaN() -> "Not enough data"
    v >= 1000 -> "%,.2f".format(v)
    v >= 1 -> "%.4f".format(v)
    else -> "%.8f".format(v)
}

fun fmtNum(v: Double, d: Int = 2): String = if (v.isNaN()) "Not enough data" else "%.${d}f".format(v)

fun fmtVol(v: Double): String = when {
    v >= 1e9 -> "%.2fB".format(v / 1e9)
    v >= 1e6 -> "%.2fM".format(v / 1e6)
    v >= 1e3 -> "%.1fK".format(v / 1e3)
    else -> "%.0f".format(v)
}

fun fmtTime(ms: Long?): String =
    if (ms == null) "—" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

fun fmtDateTime(ms: Long): String = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(ms))

package com.bainance.tradess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppThemeMode(val label: String) {
    DARK("Dark"), LIGHT("Light"), BLUE("Blue"), GREEN("Green")
}

private data class Palette(
    val green: Color, val red: Color, val amber: Color,
    val bg: Color, val card: Color, val muted: Color,
    val text: Color, val light: Boolean
)

private val palettes = mapOf(
    AppThemeMode.DARK to Palette(Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFFF0B90B), Color(0xFF0B0E11), Color(0xFF181C22), Color(0xFF9AA4B2), Color(0xFFEAECEF), false),
    AppThemeMode.LIGHT to Palette(Color(0xFF00897B), Color(0xFFD32F2F), Color(0xFFB77900), Color(0xFFF5F7FA), Color.White, Color(0xFF59636F), Color(0xFF17202A), true),
    AppThemeMode.BLUE to Palette(Color(0xFF29B6F6), Color(0xFFEF5350), Color(0xFF64B5F6), Color(0xFF071827), Color(0xFF102B40), Color(0xFF9BB8CC), Color(0xFFEAF6FF), false),
    AppThemeMode.GREEN to Palette(Color(0xFF66BB6A), Color(0xFFEF5350), Color(0xFFAED581), Color(0xFF0B1A12), Color(0xFF14291D), Color(0xFFA5B9AA), Color(0xFFEAF5EC), false)
)

private object ActivePalette {
    var value by mutableStateOf(palettes.getValue(AppThemeMode.DARK))
}

val Green get() = ActivePalette.value.green
val Red get() = ActivePalette.value.red
val Amber get() = ActivePalette.value.amber
val Bg get() = ActivePalette.value.bg
val Card get() = ActivePalette.value.card
val Muted get() = ActivePalette.value.muted

@Composable
fun AppTheme(
    mode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val p = palettes.getValue(mode)
    ActivePalette.value = p

    val scheme = if (p.light) {
        lightColorScheme(
            primary = p.amber, onPrimary = Color.White,
            background = p.bg, surface = p.card,
            onSurface = p.text, onBackground = p.text
        )
    } else {
        darkColorScheme(
            primary = p.amber, onPrimary = Color.Black,
            background = p.bg, surface = p.card,
            onSurface = p.text, onBackground = p.text
        )
    }

    MaterialTheme(colorScheme = scheme) {
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

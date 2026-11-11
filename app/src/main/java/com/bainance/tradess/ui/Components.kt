@file:OptIn(ExperimentalMaterial3Api::class)

package com.bainance.tradess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainance.tradess.signal.Direction
import com.bainance.tradess.signal.Signal
import com.bainance.tradess.signal.SignalEngine
import kotlin.math.roundToInt

@Composable
fun Disclaimer() {
    Column(
        Modifier.fillMaxWidth().background(Color(0x33F0B90B), RoundedCornerShape(8.dp)).padding(10.dp)
    ) {
        Text("Demo / Not financial advice", fontWeight = FontWeight.Bold, color = Amber, fontSize = 13.sp)
        Text(
            "ডেমো / আর্থিক পরামর্শ নয়। এটি বিশ্লেষণ-সহায়ক টুল, নিশ্চিত ভবিষ্যদ্বাণী নয়। কোনো অর্ডার execute হয় না।",
            color = Muted, fontSize = 12.sp
        )
    }
}

@Composable
fun Banner(text: String, color: Color = Red, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}

fun dirColor(d: Direction) = when (d) { Direction.UP -> Green; Direction.DOWN -> Red; Direction.NEUTRAL -> Muted }

@Composable
fun SignalCard(symbol: String, tf: String, title: String, signal: Signal?, updated: Long?) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("$title · $symbol · $tf", color = Muted, fontSize = 12.sp)
        if (signal == null) {
            Text("No signal yet", color = Muted)
        } else if (!signal.enoughData) {
            Text("Not enough data", color = Muted, fontWeight = FontWeight.Bold)
        } else {
            val col = dirColor(signal.direction)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(signal.direction.name, color = col, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(signal.bias.label, color = col, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (signal.provisional) Text("PROVISIONAL – candle not closed yet", color = Amber, fontSize = 12.sp)
            Text(
                "Score ${"%+d".format(signal.score)} / ±${SignalEngine.MAX_SCORE} (threshold ±${SignalEngine.THRESHOLD}) · ${signal.strength}",
                fontSize = 13.sp
            )
            val upIndex = (((signal.score + SignalEngine.MAX_SCORE).toDouble() /
                (SignalEngine.MAX_SCORE * 2)) * 100.0).roundToInt().coerceIn(0, 100)
            Text(
                "UP score index: $upIndex%  ·  DOWN score index: ${100 - upIndex}% (not probability)",
                color = Muted, fontSize = 12.sp
            )
            signal.contributions.forEach {
                val c = if (it.points > 0) Green else if (it.points < 0) Red else Muted
                Text("${"%+d".format(it.points)}  ${it.reason}", color = c, fontSize = 12.sp)
            }
            if (signal.conflicting) Text("Conflicting indicators → WAIT", color = Amber, fontSize = 12.sp)
            Text(
                (if (signal.provisional) "Candle closes: " else "Candle closed: ") + fmtDateTime(signal.candleCloseTime) +
                    " · Updated: " + fmtTime(updated),
                color = Muted, fontSize = 11.sp
            )
            Text("Rule-based score, not a probability or a guarantee.", color = Muted, fontSize = 11.sp)
        }
    }
}

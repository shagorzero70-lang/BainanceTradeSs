package com.bainance.tradess.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class NewsArticle(val title: String, val link: String, val date: String)

private val newsHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

@Composable
fun ExtrasScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var dhakaNow by remember { mutableStateOf(ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))) }
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it in 0.1f..1.0f } ?: 0.5f
        )
    }
    var articles by remember { mutableStateOf<List<NewsArticle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var newsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            dhakaNow = ZonedDateTime.now(ZoneId.of("Asia/Dhaka"))
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        newsError = null
        try {
            articles = withContext(Dispatchers.IO) {
                val q = URLEncoder.encode("ক্রিপ্টো OR বিটকয়েন OR Binance", "UTF-8")
                val url = "https://news.google.com/rss/search?q=$q&hl=bn&gl=BD&ceid=BD:bn"
                val request = Request.Builder().url(url).header("User-Agent", "BainanceTradeSs/1.0").build()
                newsHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("News server HTTP ${response.code}")
                    parseNews(response.body?.string().orEmpty())
                }
            }
            if (articles.isEmpty()) newsError = "এখন কোনো সংবাদ পাওয়া যায়নি। পরে আবার চেষ্টা করুন।"
        } catch (e: Exception) {
            newsError = "সংবাদ লোড হয়নি। ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।"
        } finally {
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = vm::back) { Text("← Back") }
        Text("News & Tools", color = Amber, fontSize = 21.sp)
        Disclaimer()

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("বাংলাদেশ সময়", color = Amber, fontSize = 15.sp)
            Text(
                dhakaNow.format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH)),
                fontSize = 19.sp
            )
            Text("Asia/Dhaka · স্বয়ংক্রিয়ভাবে আপডেট হয়", color = Muted, fontSize = 11.sp)
        }

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("Screen brightness", color = Amber, fontSize = 15.sp)
            Text("${(brightness * 100).toInt()}%", color = Muted)
            Slider(
                value = brightness,
                onValueChange = { value ->
                    brightness = value
                    activity?.window?.let { window ->
                        val attrs = window.attributes
                        attrs.screenBrightness = value
                        window.attributes = attrs
                    }
                },
                valueRange = 0.1f..1.0f
            )
            Text("এই সেটিং শুধু অ্যাপ খোলা থাকা অবস্থায় কাজ করে।", color = Muted, fontSize = 11.sp)
        }

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("Notifications", color = Amber, fontSize = 15.sp)
            Text("নোটিফিকেশন অনুমতি পরীক্ষা ও একটি test notification পাঠান।", color = Muted, fontSize = 12.sp)
            Button(onClick = {
                val granted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted && Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    postTestNotification(context)
                }
            }) { Text("Enable / Test notification") }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Text("ফোনের App notification settings-এ অনুমতি বন্ধ থাকতে পারে।", color = Amber, fontSize = 11.sp)
            }
        }

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("Binance new listings", color = Amber, fontSize = 15.sp)
            Text("নতুন কয়েন তালিকাভুক্তির অফিসিয়াল ঘোষণা Binance-এর সাইটে দেখুন।", color = Muted, fontSize = 12.sp)
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.binance.com/en/support/announcement/new-cryptocurrency-listing"
                )))
            }) { Text("Open official Binance listings") }
        }

        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("বাংলা ক্রিপ্টো সংবাদ", color = Amber, fontSize = 15.sp)
            if (loading) Text("সংবাদ লোড হচ্ছে…", color = Muted)
            newsError?.let { Text(it, color = Amber, fontSize = 12.sp) }
            articles.take(12).forEach { article ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.link)))
                    }.padding(vertical = 7.dp)
                ) {
                    Text(article.title, fontSize = 13.sp)
                    if (article.date.isNotBlank()) Text(article.date, color = Muted, fontSize = 10.sp)
                }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://news.google.com/search?q=crypto&hl=bn&gl=BD&ceid=BD:bn"
                )))
            }) { Text("Open Bengali crypto news") }
        }
    }
}

private fun parseNews(xml: String): List<NewsArticle> {
    if (xml.isBlank()) return emptyList()
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(StringReader(xml))
    val output = mutableListOf<NewsArticle>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && output.size < 20) {
        if (event == XmlPullParser.START_TAG && parser.name == "item") {
            var title = ""
            var link = ""
            var date = ""
            var inner = parser.next()
            while (!(inner == XmlPullParser.END_TAG && parser.name == "item") &&
                inner != XmlPullParser.END_DOCUMENT
            ) {
                if (inner == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "title" -> title = parser.nextText()
                        "link" -> link = parser.nextText()
                        "pubDate" -> date = parser.nextText()
                        else -> parser.next()
                    }
                } else {
                    parser.next()
                }
                inner = parser.eventType
            }
            if (title.isNotBlank() && link.startsWith("http")) {
                output += NewsArticle(title, link, date)
            }
        }
        event = parser.next()
    }
    return output
}

private fun postTestNotification(context: Context) {
    val channelId = "bainance_general"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Bainance Trade Ss", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Bainance Trade Ss")
        .setContentText("নোটিফিকেশন ঠিকভাবে কাজ করছে। এটি একটি পরীক্ষা।")
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(1001, notification)
    }
}

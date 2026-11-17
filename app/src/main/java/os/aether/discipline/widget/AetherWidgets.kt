package os.aether.discipline.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import os.aether.discipline.AetherApp
import java.time.LocalDate

private suspend fun lines(context: Context): Triple<String, String, String> {
    val repo = (context.applicationContext as AetherApp).repo
    val today = repo.today()
    val board = runCatching { repo.refreshCrypto() }.getOrNull()
    val quote = repo.quoteAt(LocalDate.now().dayOfYear)
    val prices = board?.quotes?.take(2)?.joinToString("  ") { "${it.pair} ${it.price}" }.orEmpty()
    val sentence = quote?.let { "「${it.quoteText}」 ${it.bookTitle}" } ?: ""
    return Triple("连续 ${today.streak} 天", prices.ifBlank { "行情更新中" }, sentence)
}

class ComboWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (streak, prices, quote) = lines(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xF2FFFFFF))).padding(12.dp)) {
                Text(streak, style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(Color(0xFF111827))))
                Text(prices, style = TextStyle(color = ColorProvider(Color(0xFF007AFF))))
                Text(quote, style = TextStyle(color = ColorProvider(Color(0xFF6B7280))), maxLines = 4)
            }
        }
    }
}

class CryptoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val board = runCatching { (context.applicationContext as AetherApp).repo.refreshCrypto() }.getOrNull()
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xF2FFFFFF))).padding(12.dp)) {
                Text("恐慌贪婪 ${board?.fearGreed ?: "—"}", style = TextStyle(fontWeight = FontWeight.Bold))
                board?.quotes?.forEach { Text("${it.pair} ${it.price}  ${"%.2f".format(it.changePct)}%") }
            }
        }
    }
}

class QuoteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val quote = (context.applicationContext as AetherApp).repo.quoteAt(LocalDate.now().dayOfYear)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xF2FFFFFF))).padding(12.dp)) {
                Text(quote?.quoteText ?: "", style = TextStyle(fontWeight = FontWeight.Bold), maxLines = 5)
                Text("${quote?.bookTitle.orEmpty()} · ${quote?.author.orEmpty()}", style = TextStyle(color = ColorProvider(Color(0xFF6B7280))))
            }
        }
    }
}

class ComboWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ComboWidget()
}

class CryptoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = CryptoWidget()
}

class QuoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuoteWidget()
}

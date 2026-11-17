package os.aether.discipline.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotQuote(val pair: String, val price: String, val changePct: Float)

data class CryptoBoard(
    val quotes: List<SpotQuote>,
    val fearGreed: String,
    val headlines: List<String>,
)

object CryptoMarket {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val pairs = listOf("BTC_USDT", "ETH_USDT", "SOL_USDT")

    suspend fun load(): CryptoBoard = withContext(Dispatchers.IO) {
        coroutineScope {
            val spots = async { tickers() }
            val fear = async { fearGreed() }
            val news = async { headlines() }
            CryptoBoard(spots.await(), fear.await(), news.await())
        }
    }

    private fun tickers(): List<SpotQuote> {
        val body = get("https://api.gateio.ws/api/v4/spot/tickers") ?: return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val wanted = pairs.toSet()
        return buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val pair = row.optString("currency_pair")
                if (pair !in wanted) continue
                add(SpotQuote(pair.removeSuffix("_USDT"), row.optString("last"), row.optString("change_percentage").toFloatOrNull() ?: 0f))
            }
        }.sortedBy { pairs.indexOf(it.pair + "_USDT") }
    }

    private fun fearGreed(): String {
        val body = get("https://api.alternative.me/fng/?limit=1") ?: return "—"
        val row = runCatching { JSONObject(body).getJSONArray("data").getJSONObject(0) }.getOrNull() ?: return "—"
        return "${row.optString("value")} ${row.optString("value_classification")}"
    }

    private fun headlines(): List<String> {
        val sources = listOf(
            "PANews" to "https://www.panewslab.com/rss.xml",
            "The Block" to "https://www.theblock.co/rss.xml",
            "Cointelegraph" to "https://cointelegraph.com/rss",
        )
        return sources.flatMap { (name, url) ->
            val xml = get(url).orEmpty()
            Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>").findAll(xml)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !it.equals(name, true) && it.length > 8 }
                .take(1)
                .map { "$name：$it" }
                .toList()
        }.take(3)
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "AetherOS/2.1").build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()
}

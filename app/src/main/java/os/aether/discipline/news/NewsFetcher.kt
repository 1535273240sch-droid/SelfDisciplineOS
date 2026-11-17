package os.aether.discipline.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import os.aether.discipline.content.NewsSource
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class Headline(val source: String, val title: String)

object NewsFetcher {
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(sources: List<NewsSource>): Pair<List<Headline>, Int> = coroutineScope {
        val jobs = sources.map { source ->
            async(Dispatchers.IO) {
                runCatching { read(source) }.getOrDefault(emptyList())
            }
        }
        val batches = jobs.awaitAll()
        val headlines = batches.flatten().take(40)
        val failed = batches.count { it.isEmpty() }
        headlines to failed
    }

    private fun read(source: NewsSource): List<Headline> {
        val builder = Request.Builder().url(source.url).header("User-Agent", "AetherOS/2.0")
        if (source.referer != null) builder.header("Referer", source.referer)
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val titles = when (source.kind) {
                "RSS" -> rssTitles(body)
                "SINA" -> listOf(source.name + " " + sinaQuote(body).take(80)).filter { it.length > source.name.length + 1 }
                else -> jsonTitles(source, body)
            }
            return titles.take(3).map { Headline(source.name, it.trim()) }.filter { it.title.isNotBlank() }
        }
    }

    private fun rssTitles(xml: String): List<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val titles = mutableListOf<String>()
        var event = parser.eventType
        var inItem = false
        while (event != XmlPullParser.END_DOCUMENT && titles.size < 4) {
            val tag = parser.name.orEmpty()
            if (event == XmlPullParser.START_TAG && (tag.equals("item", true) || tag.equals("entry", true))) {
                inItem = true
            }
            if (event == XmlPullParser.END_TAG && (tag.equals("item", true) || tag.equals("entry", true))) {
                inItem = false
            }
            if (inItem && event == XmlPullParser.START_TAG && tag.equals("title", true)) {
                val text = parser.nextText().trim()
                if (text.isNotBlank()) titles += text
            }
            event = parser.next()
        }
        return titles
    }

    private fun sinaQuote(body: String): String {
        val match = Regex("\"([^\"]+)\"").find(body) ?: return ""
        return match.groupValues[1].split(",").take(4).joinToString(" ")
    }

    private fun jsonTitles(source: NewsSource, body: String): List<String> {
        val trimmed = body.trim()
        if (source.id == "fng") {
            val value = runCatching { JSONObject(trimmed).getJSONArray("data").getJSONObject(0) }.getOrNull()
            val score = value?.optString("value").orEmpty()
            val label = value?.optString("value_classification").orEmpty()
            return listOf("恐慌贪婪指数 $score $label").filter { score.isNotBlank() }
        }
        if (source.id == "gate") {
            val last = runCatching { JSONArray(trimmed).getJSONObject(0).optString("last") }.getOrNull().orEmpty()
            return listOf("BTC/USDT $last").filter { last.isNotBlank() }
        }
        if (source.id == "frankfurter" || source.id == "erapi") {
            val rates = runCatching { JSONObject(trimmed).optJSONObject("rates") }.getOrNull()
            val cny = rates?.optDouble("CNY") ?: return emptyList()
            return listOf("USD/CNY ${"%.4f".format(cny)}")
        }
        if (source.id == "goldapi") {
            val price = runCatching { JSONObject(trimmed).optDouble("price") }.getOrNull()
            return if (price != null && price > 0) listOf("XAU $price") else emptyList()
        }
        if (trimmed.startsWith("[")) return collectStrings(JSONArray(trimmed), 3)
        if (trimmed.startsWith("{")) return collectStrings(JSONObject(trimmed), 3)
        return emptyList()
    }

    private fun collectStrings(node: Any?, limit: Int): List<String> {
        val out = mutableListOf<String>()
        fun walk(value: Any?) {
            if (out.size >= limit || value == null) return
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "title" || key == "content" || key == "content_text") {
                        val text = child?.toString()?.trim().orEmpty()
                        if (text.length in 8..180) out += text
                    } else walk(child)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        if (out.size >= limit) return
                        walk(value.opt(i))
                    }
                }
            }
        }
        walk(node)
        return out
    }
}

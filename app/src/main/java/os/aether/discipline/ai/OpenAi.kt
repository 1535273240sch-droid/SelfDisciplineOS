package os.aether.discipline.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(val content: String, val tools: List<ToolCall>, val error: String? = null)

data class StreamResult(val text: String, val error: String? = null)

interface OpenAiHttp {
    @GET
    suspend fun get(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<ResponseBody>

    @POST
    suspend fun post(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: okhttp3.RequestBody,
    ): Response<ResponseBody>
}

class OpenAiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://api.openai.com/")
        .client(http)
        .build()
        .create(OpenAiHttp::class.java)

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val response = api.get(modelsUrl(baseUrl), bearer(apiKey))
        val text = response.body()?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                if (text.isBlank()) "HTTP ${response.code()}" else "HTTP ${response.code()}: ${text.take(300)}",
            )
        }
        val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }.distinct().sorted()
    }

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        temperature: Double = 0.6,
    ): ChatResult {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("messages", messages)
            .put("stream", false)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        val response = api.post(
            joinUrl(baseUrl, "/chat/completions"),
            bearer(apiKey),
            payload.toString().toRequestBody(JSON),
        )
        val text = response.body()?.string().orEmpty()
        if (!response.isSuccessful) {
            return ChatResult(
                "",
                emptyList(),
                if (text.isBlank()) "HTTP ${response.code()}" else "HTTP ${response.code()}: ${text.take(400)}",
            )
        }
        return parseChat(text)
    }

    suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
    ): StreamResult = suspendCancellableCoroutine { cont ->
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.7)
            .put("messages", messages)
            .put("stream", true)
        val request = Request.Builder()
            .url(joinUrl(baseUrl, "/chat/completions"))
            .header("Authorization", bearer(apiKey))
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val acc = StringBuilder()
        val source = EventSources.createFactory(http).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") return
                val piece = runCatching {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")?.optString("content").orEmpty()
                }.getOrDefault("")
                if (piece.isNotEmpty()) acc.append(piece)
            }

            override fun onClosed(eventSource: EventSource) {
                if (cont.isActive) cont.resume(StreamResult(acc.toString()))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                if (!cont.isActive) return
                val err = "HTTP ${response?.code ?: ""} ${t?.message ?: "网络中断"}".trim()
                if (acc.isEmpty()) {
                    cont.resume(StreamResult("", err))
                } else {
                    cont.resume(StreamResult(acc.toString(), "回复中断：$err"))
                }
            }
        })
        cont.invokeOnCancellation { source.cancel() }
    }

    suspend fun transcribe(baseUrl: String, apiKey: String, path: String, model: String, file: File): String =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            if (!path.contains("tools/asr")) multipart.addFormDataPart("model", model)
            val request = Request.Builder()
                .url(joinUrl(baseUrl, path.ifBlank { "/audio/transcriptions" }))
                .header("Authorization", bearer(apiKey))
                .post(multipart.build())
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(text.ifBlank { "HTTP ${response.code}" })
                val json = runCatching { JSONObject(text) }.getOrNull()
                json?.optString("text").orEmpty().ifBlank { text }
            }
        }

    suspend fun vision(baseUrl: String, apiKey: String, model: String, prompt: String, jpeg: ByteArray): String {
        val data = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$data"),
                ),
            )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return chat(baseUrl, apiKey, model, messages, temperature = 0.2).content
    }

    suspend fun tts(baseUrl: String, apiKey: String, path: String, model: String, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("model", model.ifBlank { "tts-1" }).put("input", text).put("voice", "alloy")
            val request = Request.Builder()
                .url(joinUrl(baseUrl, path.ifBlank { "/audio/speech" }))
                .header("Authorization", bearer(apiKey))
                .post(payload.toString().toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) error(String(bytes).ifBlank { "HTTP ${response.code}" })
                bytes
            }
        }

    private fun parseChat(text: String): ChatResult {
        val root = runCatching { JSONObject(text) }.getOrElse { return ChatResult("", emptyList(), text) }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return ChatResult("", emptyList(), text)
        val tools = message.optJSONArray("tool_calls")
        val calls = buildList {
            if (tools != null) {
                for (i in 0 until tools.length()) {
                    val item = tools.optJSONObject(i) ?: continue
                    val fn = item.optJSONObject("function") ?: continue
                    add(
                        ToolCall(
                            id = item.optString("id"),
                            name = fn.optString("name"),
                            arguments = fn.optString("arguments"),
                        ),
                    )
                }
            }
        }
        return ChatResult(message.optString("content"), calls)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun bearer(key: String) = "Bearer $key"

        fun joinUrl(base: String, path: String): String {
            if (path.startsWith("http")) return path
            val b = base.trim().trimEnd('/')
            val p = if (path.startsWith("/")) path else "/$path"
            return b + p
        }

        fun modelsUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return if (b.endsWith("/v1")) "$b/models" else "$b/v1/models"
        }
    }
}

fun toolSchema(): JSONArray {
    fun fn(name: String, description: String, props: JSONObject, required: JSONArray) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required)),
        )
    fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    return JSONArray()
        .put(fn("record_weight", "记录体重", JSONObject().put("weight_kg", num("公斤")).put("is_fasting", bool("是否空腹")), JSONArray().put("weight_kg")))
        .put(fn("record_sleep", "记录入睡", JSONObject().put("sleep_time", str("HH:mm")).put("wake_time", str("HH:mm，可空")), JSONArray().put("sleep_time")))
        .put(fn("record_diet", "记录一餐", JSONObject().put("meal_type", str("BREAKFAST LUNCH DINNER SNACK")).put("items", str("吃了什么")).put("calories", num("千卡")).put("protein_g", num("蛋白")).put("carbs_g", num("碳水")).put("fat_g", num("脂肪")), JSONArray().put("meal_type").put("items")))
        .put(fn("record_calisthenics", "记录囚徒健身", JSONObject().put("category", str("PUSHUP SQUAT PULLUP LEG_RAISE BRIDGE HANDSTAND")).put("step", num("第几式")).put("sets", num("组")).put("reps", num("每组次数")), JSONArray().put("category").put("sets").put("reps")))
        .put(fn("render_analytics_widget", "在对话里画表和趋势图", JSONObject().put("days", num("天数，默认14")), JSONArray()))
        .put(fn("remember", "写入长期记忆", JSONObject().put("key", str("键")).put("value", str("值")), JSONArray().put("key").put("value")))
        .put(fn("bump_learning_module", "给学习模块增加进度。必须调用这个工具，不要自己猜数字或口算进度。", JSONObject().put("module_id", str("driving/english/crypto/kline 或自定义 id")).put("amount", num("增加的数量，默认 1")), JSONArray().put("module_id")))
        .put(fn("set_hard_module", "把某个学习模块设为当前硬目标。必须调用这个工具，不要自己臆测模块 id。", JSONObject().put("module_id", str("模块 id")), JSONArray().put("module_id")))
        .put(fn("add_learning_module", "新建学习模块。必须调用这个工具，不要自己编造目标数字或进度。", JSONObject().put("title", str("模块名称")).put("target_value", num("目标值")).put("unit", str("单位，如 次/页/天")).put("template_type", str("模板类型，取值 DAILY_QUOTA / COUNTDOWN_SPRINT / CRYPTO_TRACKER")), JSONArray().put("title").put("target_value")))
}

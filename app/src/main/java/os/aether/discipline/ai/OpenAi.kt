package os.aether.discipline.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import os.aether.discipline.domain.audioEndpoint
import os.aether.discipline.domain.chatCompletionsUrl
import os.aether.discipline.domain.describeTransportFailure
import os.aether.discipline.domain.extractApiMessage
import os.aether.discipline.domain.modelsUrl
import os.aether.discipline.domain.parseModelPage
import os.aether.discipline.domain.readableApiError
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatResult(val content: String, val tools: List<ToolCall>, val error: String? = null)

data class StreamResult(val text: String, val error: String? = null)

internal data class ModelCatalog(val baseUrl: String, val models: List<String>)

private class ModelEndpointMismatch(message: String) : IllegalStateException(message)

class OpenAiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val listHttp = http.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    internal suspend fun listModels(bases: List<String>, apiKey: String): ModelCatalog {
        require(apiKey.isNotBlank()) { "先填写 API Key" }
        for (base in bases.dropLast(1)) {
            try {
                return ModelCatalog(base, fetchModelPages(base, apiKey))
            } catch (_: ModelEndpointMismatch) {
                // A gateway may expose /models directly or under /v1 on the same origin.
            }
        }
        val base = bases.last()
        return ModelCatalog(base, fetchModelPages(base, apiKey))
    }

    private suspend fun fetchModelPages(baseUrl: String, apiKey: String): List<String> {
        val endpoint = modelsUrl(baseUrl).toHttpUrl()
        val models = linkedSetOf<String>()
        val cursors = hashSetOf<String>()
        var after: String? = null
        do {
            val cursor = after
            val url = if (cursor == null) endpoint else endpoint.newBuilder().addQueryParameter("after", cursor).build()
            val request = Request.Builder().url(url)
                .header("Authorization", bearer(apiKey))
                .header("Accept", "application/json")
                .get().build()
            val (code, text) = execute(listHttp, request) { it.code to it.body?.string().orEmpty() }
            if (code !in 200..299) {
                val detail = apiError(code, text, apiKey)
                if (cursor == null && (code == 404 || code == 405)) throw ModelEndpointMismatch(detail)
                error(detail)
            }
            val page = parseModelPage(text) ?: run {
                val detail = "模型列表未返回 OpenAI 格式的 data 数组，可以手填模型 ID 后测试。"
                if (cursor == null) throw ModelEndpointMismatch(detail)
                error(detail)
            }
            models.addAll(page.ids)
            after = page.nextCursor
            check(after == null || cursors.add(after)) { "服务商返回了重复的分页游标，模型列表未完整拉取。" }
        } while (after != null)
        return models.sorted()
    }

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        temperature: Double? = 0.6,
    ): ChatResult {
        if (apiKey.isBlank()) return ChatResult("", emptyList(), "先填写 API Key")
        if (model.isBlank()) return ChatResult("", emptyList(), "先填写或选择模型名")
        val url = chatCompletionsUrl(baseUrl)
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
        if (temperature != null) payload.put("temperature", temperature)
        if (tools != null && tools.length() > 0) payload.put("tools", tools)
        suspend fun send(): Pair<Int, String> {
            val request = Request.Builder().url(url)
                .header("Authorization", bearer(apiKey))
                .post(payload.toString().toRequestBody(JSON)).build()
            return execute(http, request) { it.code to it.body?.string().orEmpty() }
        }
        try {
            val first = send()
            val (code, text) = if (temperature != null && first.first in setOf(400, 422) &&
                first.second.contains("temperature", ignoreCase = true)
            ) {
                payload.remove("temperature")
                send()
            } else first
            if (code !in 200..299) return ChatResult("", emptyList(), apiError(code, text, apiKey))
            return parseChat(text, apiKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ChatResult("", emptyList(), describeTransportFailure(error).replace(apiKey, "••••"))
        }
    }

    suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
    ): StreamResult = suspendCancellableCoroutine { cont ->
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
        val request = Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
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
                val err = if (response != null) {
                    apiError(response.code, runCatching { response.body?.string() }.getOrNull().orEmpty(), apiKey)
                } else describeTransportFailure(t ?: IOException("网络中断")).replace(apiKey, "••••")
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
        transcribeAudio(baseUrl, apiKey, path, model, file.name, file.asRequestBody(WAV))

    internal suspend fun transcribeAudio(
        baseUrl: String, apiKey: String, path: String, model: String, filename: String, audio: okhttp3.RequestBody,
    ): String {
        val url = audioEndpoint(baseUrl, path.ifBlank { "/audio/transcriptions" })
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", filename, audio)
        if (!url.toHttpUrl().encodedPath.endsWith("/tools/asr")) multipart.addFormDataPart("model", model)
        val request = Request.Builder().url(url).header("Authorization", bearer(apiKey)).post(multipart.build()).build()
        return execute(http, request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, text, apiKey))
            val json = runCatching { JSONObject(text) }.getOrNull()
            when {
                json?.has("error") == true -> error(extractApiMessage(text.replace(apiKey, "••••")))
                json?.opt("text") is String -> json.getString("text")
                json?.optJSONObject("data")?.opt("text") is String -> json.getJSONObject("data").getString("text")
                response.body?.contentType()?.subtype == "plain" -> text
                else -> error("语音接口没有返回可识别的转写结果。")
            }
        }
    }

    suspend fun vision(baseUrl: String, apiKey: String, model: String, prompt: String, jpeg: ByteArray): String {
        val data = Base64.getEncoder().encodeToString(jpeg)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$data"),
                ),
            )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val result = chat(baseUrl, apiKey, model, messages, temperature = 0.2)
        if (result.error != null) error(result.error)
        return result.content
    }

    suspend fun tts(baseUrl: String, apiKey: String, path: String, model: String, text: String): ByteArray {
        val payload = JSONObject().put("model", model.ifBlank { "tts-1" }).put("input", text).put("voice", "alloy")
        val request = Request.Builder()
            .url(audioEndpoint(baseUrl, path.ifBlank { "/audio/speech" }))
            .header("Authorization", bearer(apiKey))
            .post(payload.toString().toRequestBody(JSON)).build()
        return execute(http, request) { response ->
            val body = response.body
            val bytes = body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) error(apiError(response.code, String(bytes), apiKey))
            val type = body?.contentType()
            val validAudio = type?.type == "audio" ||
                (type?.subtype == "octet-stream" && looksLikeAudio(bytes))
            check(validAudio && bytes.size > 32) { "云端音色接口没有返回可播放的音频。" }
            bytes
        }
    }

    private fun looksLikeAudio(bytes: ByteArray): Boolean {
        val start = bytes.take(4).toByteArray().toString(Charsets.US_ASCII)
        return start.startsWith("ID3") || start == "RIFF" || start == "OggS" || start == "fLaC" ||
            (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0)
    }

    private fun parseChat(text: String, key: String): ChatResult {
        val invalid = ChatResult("", emptyList(), "模型接口没有返回有效的对话回复。")
        val root = runCatching { JSONObject(text) }.getOrElse { return invalid }
        if (root.has("error")) return ChatResult("", emptyList(), extractApiMessage(text.replace(key, "••••")))
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return invalid
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
        val content = message.opt("content") as? String ?: ""
        return if (content.isBlank() && calls.isEmpty()) invalid else ChatResult(content, calls)
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(read) }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private fun apiError(code: Int, text: String, key: String) =
        readableApiError(code, if (key.isBlank()) text else text.replace(key, "••••"))

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        internal val WAV = "audio/wav".toMediaType()

        fun bearer(key: String) = "Bearer $key"
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
        .put(fn("propose_daily_plan", "起草今天的安排。不要声称已经写入今日计划，用户点采纳后才会生效。睡眠不足就下调训练和题量。", JSONObject().put("items", str("每行一条，覆盖训练、硬指标、饮食、入睡")).put("note", str("一句提醒")), JSONArray().put("items")))
}

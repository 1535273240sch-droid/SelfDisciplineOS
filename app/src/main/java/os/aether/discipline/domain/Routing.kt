package os.aether.discipline.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * OpenAI-compatible URL joining.
 *
 * A base of `https://api.deepseek.com/v1` and a base of `https://api.deepseek.com`
 * must both land on `/v1/models` and `/v1/chat/completions`. Bases that already
 * end in `/v4` (Zhipu) keep that version. Pasting a full `/chat/completions`
 * or `/models` URL is stripped back to the API root.
 */
fun normalizeApiBase(raw: String): String {
    var base = raw.trim().trim('"', '\'').trim()
    if (base.isEmpty()) return ""
    if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "https://$base"
    base = base.replaceBefore("://", base.substringBefore("://").lowercase())
    base = base.trimEnd('/')
    val suffixes = listOf(
        "/chat/completions",
        "/completions",
        "/embeddings",
        "/models",
        "/audio/transcriptions",
        "/audio/speech",
    )
    var guard = 0
    while (guard++ < 4) {
        val suffix = suffixes.firstOrNull { base.endsWith(it) } ?: break
        base = base.removeSuffix(suffix).trimEnd('/')
    }
    return base
}

private val API_VERSION = Regex("""/v\d+$""")
private val VERSIONED_PATH = Regex("""^/v\d+(/|$)""")

fun baseHasApiVersion(base: String): Boolean = API_VERSION.containsMatchIn(normalizeApiBase(base))

fun endpointUrl(base: String, path: String): String {
    val given = path.trim()
    if (given.startsWith("http://") || given.startsWith("https://")) return given
    val root = normalizeApiBase(base)
    if (root.isEmpty()) return given
    var relative = if (given.startsWith("/")) given else "/$given"
    if (baseHasApiVersion(root) && (relative.startsWith("/v1/") || relative == "/v1")) {
        relative = relative.removePrefix("/v1").ifBlank { "/" }
        if (!relative.startsWith("/")) relative = "/$relative"
    }
    if (!baseHasApiVersion(root) && !VERSIONED_PATH.containsMatchIn(relative)) {
        relative = "/v1$relative"
    }
    return root + relative
}

fun modelsUrl(base: String): String = endpointUrl(base, "/models")

fun chatCompletionsUrl(base: String): String = endpointUrl(base, "/chat/completions")

fun requireProviderBase(raw: String): String {
    val clean = raw.trim().trim('"', '\'').trim()
    val url = normalizeApiBase(clean).toHttpUrlOrNull()
    require(clean.isNotBlank() && (!clean.contains("://") || clean.startsWith("https://", true)) &&
        clean.none { it.isWhitespace() || it == '\\' } && url != null && url.isHttps && url.hasNoCredentials() &&
        url.query == null && url.fragment == null) { "请填写有效的 HTTPS Base URL，密钥填在 API Key 一栏。" }
    return url.toString().trimEnd('/')
}

fun sameApiOrigin(first: String, second: String): Boolean {
    val a = first.toHttpUrlOrNull() ?: return false
    val b = second.toHttpUrlOrNull() ?: return false
    return a.scheme == b.scheme && a.host == b.host && a.port == b.port
}

fun audioEndpoint(base: String, path: String): String {
    val url = endpointUrl(base, path).toHttpUrlOrNull()
    require(url != null && url.hasNoCredentials() && url.fragment == null && sameApiOrigin(base, url.toString())) {
        "音频路径须与 Base URL 使用相同域名、端口和协议。"
    }
    return url.toString()
}

private fun HttpUrl.hasNoCredentials() = encodedUsername.isEmpty() && encodedPassword.isEmpty()

fun extractApiMessage(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    val match = Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""").find(trimmed)
    val raw = match?.groupValues?.getOrNull(1)
    if (!raw.isNullOrBlank()) {
        return raw
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .take(240)
    }
    return trimmed.replace('\n', ' ').take(240)
}

fun readableApiError(code: Int, body: String): String {
    val detail = extractApiMessage(body)
    val why = when (code) {
        401, 403 -> "密钥无效或没有权限"
        404 -> "接口地址不对。Base URL 若已包含 /v1，不要再叠一层"
        408 -> "连接超时"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务商暂时不可用"
        else -> "请求失败"
    }
    return if (detail.isBlank()) "$why（HTTP $code）" else "$why（HTTP $code）：$detail"
}

fun describeTransportFailure(error: Throwable): String {
    val msg = (error.message ?: error.javaClass.simpleName).trim()
    return when {
        msg.contains("Cleartext", true) || msg.contains("CLEARTEXT", true) ->
            "这个地址是 http 明文，系统拦截了。请改成 https 的 Base URL。"
        msg.contains("UnknownHost", true) || msg.contains("unable to resolve host", true) ->
            "域名解析失败。检查 Base URL 和网络。"
        msg.contains("timeout", true) || msg.contains("timed out", true) ->
            "连接超时。确认这台手机能访问该 Base URL。"
        msg.contains("Expected URL scheme", true) || msg.contains("Malformed", true) ->
            "Base URL 格式不对。需要以 https:// 开头，例如 https://api.deepseek.com/v1。"
        msg.contains("SSL", true) || msg.contains("Certificate", true) || msg.contains("CertPath", true) ->
            "证书校验失败：$msg".take(240)
        else -> msg.ifBlank { "网络异常" }.take(240)
    }
}

private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

internal data class ModelPage(val ids: List<String>, val nextCursor: String?)

internal fun parseModelPage(body: String): ModelPage {
    val root = runCatching { jsonParser.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val data = root?.get("data") as? JsonArray
        ?: error("模型列表接口未返回有效的 data 数组，可改用手填模型后测试。")
    val ids = data.mapNotNull { element ->
        val id = (element as? JsonObject)?.get("id") as? JsonPrimitive
        id?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    require(data.isEmpty() || ids.isNotEmpty()) { "模型列表里没有有效的模型 ID。" }
    val next = if ((root?.get("has_more") as? JsonPrimitive)?.booleanOrNull == true) {
        (root["last_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            ?: ids.lastOrNull() ?: error("模型列表标记还有下一页，但缺少分页游标。")
    } else null
    return ModelPage(ids, next)
}

@Serializable
data class DailyPlan(
    val date: String = "",
    val items: List<String> = emptyList(),
    val note: String = "",
    val source: String = "local",
)

private val planJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

fun planToJson(plan: DailyPlan): String = planJson.encodeToString(DailyPlan.serializer(), plan)

fun planFromJson(raw: String): DailyPlan? {
    if (raw.isBlank()) return null
    val plan = runCatching { planJson.decodeFromString(DailyPlan.serializer(), raw) }.getOrNull() ?: return null
    val items = plan.items.map { it.trim() }.filter { it.isNotEmpty() }
    if (items.isEmpty()) return null
    return plan.copy(items = items.take(8), note = plan.note.trim().take(200))
}

fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            if (escape) escape = false
            else if (char == '\\') escape = true
            else if (char == '"') inString = false
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}

private val timeLine = Regex("""^\d{1,2}[:：]\d{2}\b""")

fun parseDailyPlan(text: String, date: String): DailyPlan? {
    val json = extractJsonObject(text)
    if (json != null) {
        planFromJson(json)?.let { decoded ->
            return decoded.copy(
                date = date.ifBlank { decoded.date },
                source = "ai",
            )
        }
        val objectJson = runCatching { jsonParser.parseToJsonElement(json).jsonObject }.getOrNull()
        val itemsText = (objectJson?.get("items") as? JsonPrimitive)?.contentOrNull
        if (!itemsText.isNullOrBlank()) {
            val items = itemsText.lines().map { cleanPlanLine(it) }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) {
                val note = (objectJson["note"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return DailyPlan(date, items.take(8), note.trim().take(200), "ai")
            }
        }
    }
    return scheduleFromLines(text, date)
}

private fun cleanPlanLine(raw: String): String =
    raw.trim().trimStart('-', '•', '*', '·', ' ').trim()

private fun scheduleFromLines(text: String, date: String): DailyPlan? {
    val lines = text.lines().map { cleanPlanLine(it) }.filter { it.isNotEmpty() }
    val timed = lines.filter { timeLine.containsMatchIn(it) }
    if (timed.size < 3) return null
    val note = lines.lastOrNull { line -> !timeLine.containsMatchIn(line) && line.length in 4..80 }.orEmpty()
    return DailyPlan(date, timed.take(8), note, "ai")
}

data class PlanContext(
    val date: String,
    val sleepLate: Boolean,
    val hardTitle: String,
    val hardTarget: Int,
    val hardUnit: String,
    val calorieLimit: Int,
    val englishTarget: Int,
    val lockHHmm: String,
    val trainingName: String,
)

fun localFallbackPlan(ctx: PlanContext): DailyPlan {
    val target = ctx.hardTarget.coerceAtLeast(1)
    val scaled = if (ctx.sleepLate) (target * 4 / 5).coerceAtLeast(1) else target
    val hardName = ctx.hardTitle.ifBlank { "硬指标" }
    val unit = ctx.hardUnit.ifBlank { "" }
    val hard = buildString {
        append(hardName)
        append(" ")
        append(scaled)
        if (unit.isNotBlank()) {
            append(" ")
            append(unit)
        }
        if (ctx.sleepLate && scaled < target) {
            append("（先下调单次任务量，今日达标仍需 ")
            append(target)
            append(unit)
            append("）")
        }
    }.trim()
    val train = if (ctx.sleepLate) {
        "睡眠不足，训练改为 20 分钟散步"
    } else {
        "训练：${ctx.trainingName.ifBlank { "按今日体能完成一组" }}"
    }
    val diet = if (ctx.calorieLimit > 0) "饮食上限 ${ctx.calorieLimit} kcal" else "饮食随手记一笔，先不设上限"
    val lock = ctx.lockHHmm.ifBlank { "00:00" }
    val note = if (ctx.sleepLate) "强度已经按睡眠下调。" else "这是按本地目标排的底稿。"
    return DailyPlan(
        date = ctx.date,
        items = listOf(train, hard, "英语 ${ctx.englishTarget.coerceAtLeast(1)} 句", diet, "$lock 前提醒入睡"),
        note = note,
        source = "local",
    )
}

fun greetingForHour(hour: Int): String = when (hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

val MODEL_SCENES = listOf("companion", "fast", "transcribe", "vision", "briefing", "tts")

fun sceneLabel(scene: String): String = when (scene) {
    "companion" -> "搭档对话"
    "fast" -> "快速记录"
    "transcribe" -> "语音转写"
    "vision" -> "拍照识别"
    "briefing" -> "早报总结"
    "tts" -> "云端音色"
    else -> scene
}

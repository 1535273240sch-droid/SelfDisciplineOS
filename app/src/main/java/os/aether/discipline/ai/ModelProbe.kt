package os.aether.discipline.ai

import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

// These requests use generated test data and exercise the same endpoints as the corresponding features.
internal suspend fun OpenAiClient.verifyModel(
    scene: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    audioPath: String,
) {
    when (scene) {
        "companion", "briefing" -> {
            val response = chat(baseUrl, apiKey, model, probeMessage("只回复 OK。"), temperature = null)
            check(response.error == null && response.content.isNotBlank()) {
                response.error ?: "模型没有返回可用的文本回复。"
            }
        }
        "fast" -> {
            val tool = JSONObject().put("type", "function").put(
                "function", JSONObject()
                    .put("name", "verify_recording")
                    .put("description", "Confirm that the model can return a tool call.")
                    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())),
            )
            val response = chat(
                baseUrl, apiKey, model, probeMessage("请调用 verify_recording 工具确认，参数为空对象。"),
                JSONArray().put(tool), temperature = null,
            )
            check(response.error == null && response.tools.any { it.name == "verify_recording" }) {
                response.error ?: "这个模型没有返回工具调用，请为快速记录选择支持工具调用的模型。"
            }
        }
        "vision" -> {
            val image = Base64.getDecoder().decode(PROBE_JPEG)
            check(vision(baseUrl, apiKey, model, "图片是什么颜色？只回答颜色。", image).isNotBlank()) {
                "视觉模型没有返回识别结果。"
            }
        }
        "transcribe" -> {
            transcribeAudio(baseUrl, apiKey, audioPath, model, "aether-probe.wav", silentWav().toRequestBody(OpenAiClient.WAV))
        }
        "tts" -> {
            tts(baseUrl, apiKey, audioPath, model, "测试。")
        }
        else -> error("未知模型场景")
    }
}

private fun probeMessage(text: String) = JSONArray().put(JSONObject().put("role", "user").put("content", text))

private fun silentWav(): ByteArray {
    val rate = 16_000
    val samples = rate * 2
    return ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + samples)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(rate)
        putInt(rate * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(samples)
    }.array()
}

private const val PROBE_JPEG =
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozv/wAARCACAAIADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFgEBAQEAAAAAAAAAAAAAAAAAAAUG/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AtANCjAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/9k="

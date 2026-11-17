package os.aether.discipline.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.aether.discipline.ai.toolSchema
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.domain.SYSTEM_PROMPT
import os.aether.discipline.domain.parseUtterance
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun VoiceLayer(repo: AetherRepository, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val colors = LocalAether.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by repo.messages.collectAsStateWithLifecycle(emptyList())
    var typing by remember { mutableStateOf("") }
    var showKeys by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var warn by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(false) }
    val submitted = remember { AtomicBoolean(false) }
    val scale by animateFloatAsState(if (holding) 1.08f else 1f, aetherSpring(), label = "mic")
    val recorder = remember {
        WavHold(File(context.cacheDir, "voice/hold.wav")) { value ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { level = value }
        }
    }
    val tts = remember { TextToSpeech(context, null) }
    DisposableEffect(Unit) { onDispose { tts.shutdown(); recorder.stop() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val accepted = repo.loadThresholds().let { repo.db.thresholds().get("tts_sample_accepted")?.value == "true" }
        sample = !accepted
        val zh = tts.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
        val en = tts.isLanguageAvailable(Locale.US)
        if (zh < TextToSpeech.LANG_AVAILABLE || en < TextToSpeech.LANG_AVAILABLE) sample = true
    }
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        delay(25_000)
        warn = true
        delay(5_000)
        if (holding) {
            holding = false
            if (submitted.compareAndSet(false, true)) {
                val file = recorder.stop()
                scope.launch { submit(repo, tts, file, null) { typing = it } }
            }
        }
    }
    Column(modifier.fillMaxWidth().background(colors.surface).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Quiet("搭档")
            Text("关闭", color = colors.accent, modifier = Modifier.padding(8.dp).clip(CircleShape).background(colors.surface).padding(8.dp).pointerInput(Unit) { detectTapGestures { onClose() } })
        }
        if (sample) {
            GlassCard {
                Body("昨晚的睡眠过了红线。今天把一百道题做完，链就不能断。The gate closes at noon, so we should leave now.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    AccentButton("听一下") {
                        tts.language = Locale.SIMPLIFIED_CHINESE
                        tts.speak("昨晚的睡眠过了红线。今天把一百道题做完。", TextToSpeech.QUEUE_FLUSH, null, "sample-zh")
                        tts.language = Locale.US
                        tts.speak("The gate closes at noon.", TextToSpeech.QUEUE_ADD, null, "sample-en")
                    }
                    AccentButton("就是这个音色") { scope.launch { repo.saveThreshold("tts_source", "LOCAL"); repo.saveThreshold("tts_sample_accepted", "true"); sample = false } }
                    AccentButton("换成云端") { scope.launch { repo.saveThreshold("tts_source", "CLOUD"); repo.saveThreshold("tts_sample_accepted", "true"); sample = false } }
                }
                Quiet("选云端时，回复文本会再发给语音服务一次。")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.takeLast(40)) { message ->
                GlassCard {
                    Quiet(if (message.role == "USER") "你" else "搭档")
                    Body(message.textContent)
                    if (message.widgetPayloadJson != null) AnalyticsBlock(message.widgetPayloadJson)
                }
            }
            if (typing.isNotBlank()) item { Quiet(typing) }
        }
        if (warn) Quiet("快到 30 秒了")
        if (showKeys) {
            OutlinedTextField(
                draft,
                { draft = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                placeholder = { Text("一句话") },
            )
            AccentButton("发送") {
                val text = draft
                draft = ""
                showKeys = false
                scope.launch { submit(repo, tts, null, text) { typing = it } }
            }
        }
        Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(64.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.85f + level.coerceIn(0f, 1f) * 0.15f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                mic.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                holding = true
                                warn = false
                                submitted.set(false)
                                recorder.start()
                                tryAwaitRelease()
                                holding = false
                                if (submitted.compareAndSet(false, true)) {
                                    val file = recorder.stop()
                                    submit(repo, tts, file, null) { typing = it }
                                }
                            }
                        })
                    },
            )
            Text("键盘", modifier = Modifier.align(Alignment.CenterEnd).pointerInput(Unit) { detectTapGestures { showKeys = !showKeys } }, color = colors.textSecondary)
        }
    }
}

private suspend fun submit(
    repo: AetherRepository,
    tts: TextToSpeech,
    audio: File?,
    typed: String?,
    onStatus: (String) -> Unit,
) {
    onStatus("在听")
    val heard = typed ?: audio?.takeIf { it.exists() && it.length() > 1000 }?.let { file ->
        val bound = repo.bound("transcribe")
        if (bound == null) "" else runCatching { repo.ai.transcribe(bound.provider.baseUrl, bound.key, bound.path, bound.model, file) }.getOrDefault("")
    }.orEmpty()
    if (heard.isBlank()) {
        onStatus(if (typed == null) "没听清。点右边键盘。" else "")
        return
    }
    repo.saveMessage("USER", heard)
    val local = parseUtterance(heard, LocalTime.now().hour)
    val fast = repo.bound("fast") ?: repo.bound("companion")
    val toolNames = mutableSetOf<String>()
    var widget: String? = null
    if (fast != null) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "只提取能入库的事实并调用工具。不要闲聊，不要编造。"))
            .put(JSONObject().put("role", "user").put("content", heard))
        val result = repo.ai.chat(fast.provider.baseUrl, fast.key, fast.model, messages, toolSchema(), 0.1)
        result.tools.forEach { call ->
            toolNames += call.name
            val out = repo.executeTool(call.name, call.arguments)
            if (call.name == "render_analytics_widget") widget = out
        }
    }
    if ("record_weight" !in toolNames) local.weight?.let { repo.recordWeight(it.kg, it.fasting, if (fast == null) "VOICE_LOCAL_FALLBACK" else "VOICE") }
    if ("record_sleep" !in toolNames) local.sleep?.let { repo.recordSleep(it.sleepHHmm, it.wakeHHmm) }
    if ("record_diet" !in toolNames) local.diet?.let { repo.recordDiet(it.mealType, it.description, 0, 0f, 0f, 0f, needsBackfill = true) }
    if ("record_calisthenics" !in toolNames) local.calisthenics?.let { repo.recordCalisthenics(it.category, it.step, it.sets, it.reps) }
    val companion = repo.bound("companion")
    val reply = if (companion == null) {
        if (local.weight == null && local.sleep == null && local.diet == null && local.calisthenics == null) {
            "先在设置里绑一个搭档模型。这句话里没有听出体重、作息或饮食。"
        } else {
            "本地已记，AI 恢复后补全。"
        }
    } else {
        val snapshot = repo.snapshot()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT + "\n\n" + snapshot))
            .put(JSONObject().put("role", "user").put("content", heard))
        onStatus("在想")
        val streamed = runCatching { repo.ai.streamChat(companion.provider.baseUrl, companion.key, companion.model, messages) }.getOrDefault("")
        streamed.ifBlank { repo.ai.chat(companion.provider.baseUrl, companion.key, companion.model, messages).content }
            .ifBlank { "我听见了，但模型没有把话说完。" }
    }
    val wantChart = heard.contains("对比") || heard.contains("趋势") || heard.contains("曲线") || heard.contains("表格")
    val payload = widget ?: if (wantChart) repo.analyticsPayload(14) else null
    repo.saveMessage("ASSISTANT", reply, if (payload != null) "WEIGHT_SLEEP_CHART" else null, payload)
    onStatus("")
    val source = repo.db.thresholds().get("tts_source")?.value
    if (source == "CLOUD") {
        val mouth = repo.bound("tts")
        if (mouth != null) {
            val bytes = runCatching { repo.ai.tts(mouth.provider.baseUrl, mouth.key, mouth.path, mouth.model, reply) }.getOrNull()
            if (bytes != null && bytes.size > 32) {
                val file = File(repo.db.openHelper.writableDatabase.path).parentFile?.let { File(it, "reply.mp3") } ?: return
                file.writeBytes(bytes)
                MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start() }
                return
            }
        }
    }
    tts.language = if (reply.any { it.code > 127 }) Locale.SIMPLIFIED_CHINESE else Locale.US
    tts.setSpeechRate(1f)
    tts.speak(reply.take(300), TextToSpeech.QUEUE_FLUSH, null, "reply")
}

private class WavHold(private val file: File, private val onLevel: (Float) -> Unit) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    fun start() {
        file.parentFile?.mkdirs()
        running.set(true)
        thread = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val audio = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            }.getOrNull()
            if (audio != null) {
                runCatching { audio.startRecording() }
                val pcm = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(min)
                while (running.get()) {
                    val n = audio.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        pcm.write(buffer, 0, n)
                        var sum = 0.0
                        for (i in 0 until n step 2) {
                            if (i + 1 >= n) break
                            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)
                            sum += sample * sample
                        }
                        onLevel((kotlin.math.sqrt(sum / (n / 2)) / 8000.0).toFloat().coerceIn(0f, 1f))
                    }
                }
                runCatching { audio.stop() }
                runCatching { audio.release() }
                runCatching { writeWav(file, pcm.toByteArray(), rate) }
            } else {
                running.set(false)
            }
        }.also { it.start() }
    }

    fun stop(): File {
        running.set(false)
        thread?.join(1500)
        return file
    }
}

private fun writeWav(file: File, pcm: ByteArray, rate: Int) {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(rate)
    header.putInt(rate * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(pcm.size)
    file.outputStream().use { it.write(header.array()); it.write(pcm) }
}

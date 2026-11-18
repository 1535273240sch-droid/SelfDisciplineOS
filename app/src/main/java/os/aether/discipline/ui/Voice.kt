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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.aether.discipline.ai.ChatResult
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
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val haze = LocalHaze.current
    val sheetGlass = if (haze != null) {
        Modifier.hazeEffect(
            haze,
            HazeStyle(
                backgroundColor = colors.surfaceStrong,
                tints = listOf(HazeTint(colors.surfaceStrong.copy(alpha = 0.92f))),
                blurRadius = 30.dp,
                noiseFactor = 0.06f,
            ),
        )
    } else {
        Modifier.background(colors.surfaceStrong)
    }
    Column(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(sheetShape)
            .then(sheetGlass)
            .border(0.8.dp, Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom)), sheetShape)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.textSecondary.copy(alpha = 0.35f)),
            )
        }
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

private const val BRAIN_RULES = """
你现在就是这个 App 的操作系统级大脑，可以直接读写本机数据，不需要用户再去别的页面手动操作。
可用工具（写数据必须走工具，禁止在回复里假装已经记录）：
- record_weight / record_sleep / record_diet / record_calisthenics：体重、入睡、饮食、囚徒健身
- bump_learning_module(module_id, amount)：累加学习模块进度，module_id 用 driving / english / kline / crypto 或自定义 id
- set_hard_module(module_id)：切换当天防断链硬指标
- add_learning_module(title, target_value, unit, template_type)：新建阶段模块
- render_analytics_widget：在对话里画趋势图
- remember：写入长期记忆
规则：
1. 一句话里有多件事就并行调用多个工具；字段不确定就先问一句，不要猜。
2. 工具返回失败时如实说明原因，绝不能声称已记录。
3. 写完后用一两句话确认写入值，并结合下方状态指出今天还差什么。
4. 锁屏、权限、删除数据这类破坏性操作只给建议，不要代替用户执行。
5. 用户问“我今天怎么样”时，直接引用下方状态里的真实数字，不要编。
"""

private suspend fun submit(
    repo: AetherRepository,
    tts: TextToSpeech,
    audio: File?,
    typed: String?,
    onStatus: (String) -> Unit,
) {
    onStatus("在听")
    val heard: String = if (typed != null) {
        typed
    } else {
        val bound = repo.bound("transcribe") ?: run {
            onStatus("还没绑定语音转写模型：设置 → AI 路由中心")
            return
        }
        val file = audio?.takeIf { it.exists() && it.length() > 1000 } ?: run {
            onStatus("没听清。点右边键盘。")
            return
        }
        runCatching { repo.ai.transcribe(bound.provider.baseUrl, bound.key, bound.path, bound.model, file) }
            .getOrElse {
                onStatus("转写失败：" + (it.message ?: "网络异常").take(80))
                return
            }
    }
    if (heard.isBlank()) {
        onStatus("没听清。点右边键盘。")
        return
    }
    repo.saveMessage("USER", heard)
    val local = parseUtterance(heard, LocalTime.now().hour)
    val fast = repo.bound("fast") ?: repo.bound("companion")
    val toolNames = mutableSetOf<String>()
    val toolNotes = mutableListOf<String>()
    var widget: String? = null
    var fastError: String? = null

    if (fast != null) {
        val extract = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "只提取能入库的事实并调用工具。不要闲聊，不要编造；字段不确定就不要调用。"))
            .put(JSONObject().put("role", "user").put("content", heard))
        val result = runCatching { repo.ai.chat(fast.provider.baseUrl, fast.key, fast.model, extract, toolSchema(), 0.1) }
            .getOrElse { ChatResult("", emptyList(), it.message ?: "网络异常") }
        fastError = result.error
        result.tools.forEach { call ->
            val out = repo.executeTool(call.name, call.arguments)
            if (out.ok) {
                toolNames += call.name
                toolNotes += out.message
                if (call.name == "render_analytics_widget") widget = out.payload
            } else {
                toolNotes += out.message
            }
        }
    }
    if ("record_weight" !in toolNames) local.weight?.let { repo.recordWeight(it.kg, it.fasting, if (fast == null) "VOICE_LOCAL_FALLBACK" else "VOICE") }
    if ("record_sleep" !in toolNames) local.sleep?.let { repo.recordSleep(it.sleepHHmm, it.wakeHHmm) }
    if ("record_diet" !in toolNames) local.diet?.let { repo.recordDiet(it.mealType, it.description, 0, 0f, 0f, 0f, needsBackfill = true) }
    if ("record_calisthenics" !in toolNames) local.calisthenics?.let { repo.recordCalisthenics(it.category, it.step, it.sets, it.reps) }

    val companion = repo.bound("companion")
    var reply = ""
    if (companion == null) {
        reply = when {
            toolNotes.isNotEmpty() -> "已写入：" + toolNotes.take(3).joinToString("；") + "。先在设置里绑一个搭档模型，我就能接着聊。"
            fastError != null -> "记录请求失败：" + fastError.orEmpty().take(120)
            local.weight == null && local.sleep == null && local.diet == null && local.calisthenics == null ->
                "先在设置里绑一个搭档模型。这句话里没有听出体重、作息或饮食。"
            else -> "本地已记，AI 恢复后补全。"
        }
    } else {
        onStatus("在想")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT + "\n\n" + BRAIN_RULES + "\n\n" + repo.snapshot()))
            .put(JSONObject().put("role", "user").put("content", heard))
        if (toolNotes.isNotEmpty()) {
            messages.put(
                JSONObject().put("role", "system")
                    .put("content", "刚才已执行的本地写入：" + toolNotes.take(4).joinToString("；") + "。不要重复写入，失败项如实说明。"),
            )
        }
        var lastError: String? = null
        var rounds = 0
        while (rounds < 3) {
            rounds++
            val res = runCatching {
                repo.ai.chat(companion.provider.baseUrl, companion.key, companion.model, messages, toolSchema(), 0.7)
            }.getOrElse { ChatResult("", emptyList(), it.message ?: "网络异常") }
            lastError = res.error
            if (res.error != null) break
            if (res.tools.isEmpty()) {
                if (res.content.isNotBlank()) reply = res.content.trim()
                break
            }
            val assistant = JSONObject().put("role", "assistant").put("content", res.content)
            val calls = JSONArray()
            res.tools.forEach { call ->
                calls.put(
                    JSONObject().put("id", call.id).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)),
                )
            }
            assistant.put("tool_calls", calls)
            messages.put(assistant)
            res.tools.forEach { call ->
                val out = repo.executeTool(call.name, call.arguments)
                if (out.ok) {
                    toolNames += call.name
                    toolNotes += out.message
                    if (call.name == "render_analytics_widget") widget = out.payload
                }
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", call.id)
                        .put("content", if (out.ok) out.message else "执行失败：" + out.message),
                )
            }
        }
        if (reply.isBlank()) {
            val err = lastError
            if (err == null) {
                reply = if (toolNotes.isEmpty()) "我听见了，但模型没有给出回复。"
                else "已写入：" + toolNotes.take(3).joinToString("；") + "。"
            } else if (err.contains("401") || err.contains("403")) {
                reply = "API Key 无效或已过期（" + err.take(40) + "）。设置 → AI 路由中心重新保存 Key。"
            } else if (err.contains("429")) {
                reply = "请求太频繁或额度不足（429）。过一会儿再试，或换一个渠道。"
            } else if (err.contains("tool", true) || err.contains("function", true)) {
                val plain = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT + "\n\n" + repo.snapshot()))
                    .put(JSONObject().put("role", "user").put("content", heard))
                val streamed = runCatching {
                    repo.ai.streamChat(companion.provider.baseUrl, companion.key, companion.model, plain)
                }.getOrNull()
                val streamedText = streamed?.text.orEmpty()
                val streamedError = streamed?.error
                reply = when {
                    streamedText.isNotBlank() -> streamedText.trim()
                    streamedError != null -> "这个模型不支持工具调用，普通对话也失败了：" + streamedError.take(120)
                    else -> "这个模型不支持工具调用，已按普通对话重试但没有结果。"
                }
            } else {
                reply = "模型调用失败：" + err.take(160)
            }
        }
    }
    if (toolNotes.isNotEmpty() && !reply.contains("已写入") && !reply.contains("已记录")) {
        reply = reply.trimEnd() + "\n\n已写入：" + toolNotes.take(3).joinToString("；")
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
                val dbPath = repo.db.openHelper.writableDatabase.path ?: return
                val file = File(dbPath).parentFile?.let { File(it, "reply.mp3") } ?: return
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

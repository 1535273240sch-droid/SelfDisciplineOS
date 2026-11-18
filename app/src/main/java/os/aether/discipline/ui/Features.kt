package os.aether.discipline.ui

import android.Manifest
import android.content.Intent
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import os.aether.discipline.AetherApp
import os.aether.discipline.content.Phrase
import os.aether.discipline.content.categoryLabel
import os.aether.discipline.content.stepOf
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.SceneBinding
import os.aether.discipline.data.db.DrivingQuestion
import os.aether.discipline.domain.SyllabusGaps
import os.aether.discipline.domain.mealTypeFor
import os.aether.discipline.domain.paceLabel
import os.aether.discipline.domain.similarity
import os.aether.discipline.domain.wordDiff
import os.aether.discipline.system.AlarmScheduler
import os.aether.discipline.system.DeviceOwnerCtl
import os.aether.discipline.system.LocationService
import os.aether.discipline.system.Root
import os.aether.discipline.system.accessibilityOn
import os.aether.discipline.system.exactAlarmAllowed
import os.aether.discipline.system.openAllFiles
import os.aether.discipline.system.openBattery
import os.aether.discipline.system.openExactAlarm
import os.aether.discipline.system.openOverlay
import os.aether.discipline.system.openUsage
import os.aether.discipline.system.usageAllowed
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

@Composable
fun DrivingScreen(repo: AetherRepository) {
    val scope = rememberCoroutineScope()
    var subject by remember { mutableIntStateOf(1) }
    var mock by remember { mutableStateOf(false) }
    var questions by remember { mutableStateOf<List<DrivingQuestion>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf("") }
    var correct by remember { mutableIntStateOf(0) }
    var explain by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(45 * 60) }
    var note by remember { mutableStateOf("") }
    var session by remember { mutableIntStateOf(0) }
    var sessionMock by remember { mutableStateOf(false) }
    var sessionSubject by remember { mutableIntStateOf(1) }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        if (session == 0 || !sessionMock) return@LaunchedEffect
        left = if (sessionSubject == 4) 30 * 60 else 45 * 60
        while (left > 0 && !done) {
            delay(1000)
            left -= 1
        }
        if (left == 0) done = true
    }
    LaunchedEffect(done) {
        if (done && sessionMock && questions.isNotEmpty()) repo.finishMock(sessionSubject, questions.size, correct)
    }
    ScreenColumn {
        Quiet("驾考 · 内置题按公开的 163/172 号令口径，不是商业题库")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(if (subject == 1) "科一 ·" else "科一") { subject = 1 }
            AccentButton(if (subject == 4) "科四 ·" else "科四") { subject = 4 }
            AccentButton(if (mock) "模考中" else "模考") { mock = !mock; left = if (subject == 4) 30 * 60 else 45 * 60 }
        }
        AccentButton("开始") {
            scope.launch {
                questions = repo.paper(subject, mock)
                index = 0
                correct = 0
                done = false
                explain = ""
                picked = ""
                submitting = false
                sessionMock = mock
                sessionSubject = subject
                left = if (subject == 4) 30 * 60 else 45 * 60
                session += 1
                val target = repo.loadThresholds().dailyDriving
                note = if (repo.db.driving().count() < target) "题库少于今日题量，会按现有题目循环。" else ""
            }
        }
        if (sessionMock && questions.isNotEmpty() && !done) Quiet("剩余 ${left / 60}:${"%02d".format(left % 60)}")
        if (note.isNotBlank()) Quiet(note)
        val question = questions.getOrNull(index)
        if (question == null && questions.isNotEmpty() || done) {
            Body(if (questions.isEmpty()) "题库还是空的" else "这组 ${correct}/${questions.size}")
        }
        if (question != null && !done) {
            GlassCard {
                Body(question.content)
                optionsOf(question.optionsJson).forEach { option ->
                    val key = optionKey(option)
                    Text(option, color = if (picked == key) LocalAether.current.accent else LocalAether.current.textPrimary, modifier = Modifier.padding(vertical = 6.dp).clickable { picked = key })
                }
                AccentButton("确认") {
                    if (picked.isNotBlank() && !submitting) {
                        submitting = true
                        scope.launch {
                            try {
                                val ok = repo.answer(question, setOf(picked), if (sessionMock) "MOCK" else "DAILY")
                                if (ok) correct += 1 else explain = question.officialExplanation
                                picked = ""
                                if (index + 1 >= questions.size) done = true else index += 1
                            } finally {
                                submitting = false
                            }
                        }
                    }
                }
                if (explain.isNotBlank()) {
                    Quiet(explain)
                    AccentButton("AI 拆解") {
                        scope.launch {
                            val bound = repo.bound("companion")
                            explain = if (bound == null) question.officialExplanation else {
                                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "只解释这道已有题目，不要出新题。用大白话讲扣分逻辑，再给一句顺口溜。\n${question.content}\n${question.optionsJson}\n答案 ${question.correctAnswer}\n${question.officialExplanation}"))
                                repo.ai.chat(bound.provider.baseUrl, bound.key, bound.model, messages, temperature = 0.4).content.ifBlank { question.officialExplanation }
                            }
                        }
                    }
                }
            }
        }
        Quiet("大纲待补")
        SyllabusGaps.forEach { Quiet("· $it") }
        val context = LocalContext.current
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            scope.launch { note = "导入 ${repo.importQuestions(text)} 题" }
        }
        AccentButton("导入题库文件") { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }
}

@Composable
fun EnglishScreen(repo: AetherRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tts = remember { TextToSpeech(context, null) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var rate by remember { mutableStateOf(1f) }
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableStateOf("") }
    var target by remember { mutableIntStateOf(5) }
    var daily by remember { mutableStateOf(emptyList<Phrase>()) }
    LaunchedEffect(Unit) {
        val t = repo.loadThresholds()
        target = t.dailyEnglish
        daily = repo.dailyPhrases(count = target)
    }
    if (daily.isEmpty()) {
        ScreenColumn { Quiet("正在加载今日句子") }
        return
    }
    val phrase = daily[index % daily.size]
    ScreenColumn {
        Quiet(phrase.scene)
        Metric(phrase.english)
        Body(phrase.chinese)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("1.0x") { rate = 1f }
            AccentButton("0.75x") { rate = 0.75f }
            AccentButton("听") {
                tts.language = Locale.US
                tts.setSpeechRate(rate)
                tts.speak(phrase.english, TextToSpeech.QUEUE_FLUSH, null, phrase.id)
            }
        }
        AccentButton("先允许麦克风") { mic.launch(Manifest.permission.RECORD_AUDIO) }
        Quiet("按住底部语音键读这一句，再回来评分。评分只比较转写和原句。")
        AccentButton("用刚才的录音评分") {
            scope.launch {
                val bound = repo.bound("transcribe")
                val file = java.io.File(context.cacheDir, "voice/hold.wav")
                if (bound == null || !file.exists()) {
                    score = "先绑定转写，并在语音页按住读完这一句"
                    return@launch
                }
                val heard = runCatching { repo.ai.transcribe(bound.provider.baseUrl, bound.key, bound.path, bound.model, file) }.getOrDefault("")
                if (heard.isBlank()) {
                    score = "没听清，再读一次。"
                    return@launch
                }
                val value = similarity(phrase.english, heard)
                score = "$value  $heard\n${wordDiff(phrase.english, heard)}"
                repo.logEnglish(phrase.scene, phrase.english, value)
                if (index + 1 < daily.size) index += 1
            }
        }
        if (score.isNotBlank()) Quiet(score)
        Quiet("今日 ${minOf(index, target)}/$target")
    }
}

@Composable
fun WorkoutScreen() {
    val context = LocalContext.current
    val live by os.aether.discipline.system.WorkoutBus.state.collectAsStateWithLifecycle()
    var pendingType by remember { mutableStateOf("RUN") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startWorkout(context, pendingType)
    }
    ScreenColumn {
        Quiet(if (live.type == "WALK") "散步" else "跑步")
        Metric(if (live.meters <= 0f) "0.00" else "%.2f".format(live.meters / 1000f))
        Quiet("公里 · ${paceLabel(live.meters, live.seconds)} · ${live.seconds / 60}分 · 步频 ${live.cadence}")
        val colors = LocalAether.current
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            if (live.points.size < 2) return@Canvas
            val lats = live.points.map { it.first }
            val lngs = live.points.map { it.second }
            val minLat = lats.min(); val maxLat = lats.max(); val minLng = lngs.min(); val maxLng = lngs.max()
            fun x(lng: Double) = ((lng - minLng) / ((maxLng - minLng).takeIf { it > 0 } ?: 1.0) * size.width).toFloat()
            fun y(lat: Double) = ((maxLat - lat) / ((maxLat - minLat).takeIf { it > 0 } ?: 1.0) * size.height).toFloat()
            val path = Path()
            live.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.second), y(p.first)) else path.lineTo(x(p.second), y(p.first)) }
            drawPath(path, colors.accent, style = Stroke(4.dp.toPx()))
            drawCircle(colors.accent, 6f, Offset(x(live.points.last().second), y(live.points.last().first)))
        }
        if (live.message.isNotBlank()) Quiet(live.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("跑") {
                pendingType = "RUN"
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS))
            }
            AccentButton("走") {
                pendingType = "WALK"
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS))
            }
            AccentButton("结束") {
                context.startService(Intent(context, LocationService::class.java).putExtra("stop", true))
            }
        }
        Quiet("轨迹只留在手机上，不会放进 AI 的提示词。")
    }
}

private fun startWorkout(context: android.content.Context, type: String) {
    ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java).putExtra("type", type))
}

@Composable
fun CalisthenicsScreen(repo: AetherRepository) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<os.aether.discipline.data.db.CalisthenicsProgress>()) }
    var sets by remember { mutableStateOf("2") }
    var reps by remember { mutableStateOf("10") }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { rows = repo.db.calisthenics().all() }
    ScreenColumn {
        Quiet("囚徒健身 · 语音也可以直接说「第五式俯卧撑 2 组 20 个」")
        rows.forEach { row ->
            val gate = stepOf(row.moveCategory, row.currentStep)
            GlassCard {
                Body("${categoryLabel(row.moveCategory)} · 第 ${row.currentStep} 式 ${row.currentStepName}")
                Quiet("初级 ${gate.beginnerSets}×${gate.beginnerReps}  中级 ${gate.midSets}×${gate.midReps}  升级 ${gate.upSets}×${gate.upReps}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sets, { sets = it }, label = { Text("组") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(reps, { reps = it }, label = { Text("次") }, modifier = Modifier.weight(1f))
                }
                AccentButton("记下") {
                    scope.launch {
                        note = repo.recordCalisthenics(row.moveCategory, row.currentStep, sets.toIntOrNull() ?: 1, reps.toIntOrNull() ?: 0)
                        rows = repo.db.calisthenics().all()
                    }
                }
            }
        }
        if (note.isNotBlank()) Quiet(note)
    }
}

@Composable
fun ThresholdScreen(repo: AetherRepository) {
    val context = LocalContext.current
    val rows by repo.thresholdRows.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var pinNote by remember { mutableStateOf("") }
    var saveNote by remember { mutableStateOf("") }
    ScreenColumn {
        Quiet("改完立即影响仪表盘和判定")
        OutlinedTextField(pin, { if (it.length <= 6) pin = it.filter(Char::isDigit) }, label = { Text("6 位紧急密码") })
        AccentButton("保存密码") { scope.launch { pinNote = repo.setPin(pin) } }
        if (pinNote.isNotBlank()) Quiet(pinNote)
        rows.filter {
            it.key !in setOf("emergency_password_hash", "emergency_password_salt", "lock_strength", "last_settled_date") &&
                !it.key.startsWith("scene_")
        }
            .forEach { row ->
                var value by remember(row.key, row.updatedAt) { mutableStateOf(row.value) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(value, { value = it }, label = { Text(row.label + (row.unit?.let { u -> " ($u)" } ?: "")) }, modifier = Modifier.weight(1f))
                    AccentButton("保存") {
                        scope.launch {
                            val result = repo.saveThreshold(row.key, value)
                            saveNote = "${row.label}：$result"
                            if (result == "已保存") {
                                AlarmScheduler.schedule(context.applicationContext as AetherApp)
                            }
                        }
                    }
                }
            }
        if (saveNote.isNotBlank()) Quiet(saveNote)
    }
}

@Composable
fun BackfillScreen(repo: AetherRepository) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now().minusDays(1).toString()) }
    var weight by remember { mutableStateOf("") }
    var sleep by remember { mutableStateOf("") }
    var wake by remember { mutableStateOf("") }
    var food by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val clock = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
    fun pastDate(day: String): Boolean =
        runCatching { LocalDate.parse(day) }.getOrNull()?.isBefore(LocalDate.now()) == true
    ScreenColumn {
        Quiet("补录只进趋势与图表，不算当日达标，也不会把已经断掉的天数补回来。")
        OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
        Quiet("体重（空腹）")
        OutlinedTextField(weight, { weight = it }, label = { Text("kg") }, modifier = Modifier.fillMaxWidth())
        AccentButton("补录体重") {
            scope.launch {
                val day = date.trim()
                val kg = weight.toFloatOrNull()
                when {
                    !pastDate(day) -> note = "日期要早于今天"
                    kg == null || kg !in 30f..250f -> note = "体重要在 30–250kg"
                    else -> {
                        repo.recordWeight(kg, true, "BACKFILL", backfill = true, date = day)
                        note = "已补录 $day 体重"
                    }
                }
            }
        }
        Quiet("作息（昨晚）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sleep, { sleep = it }, label = { Text("入睡 HH:mm") }, modifier = Modifier.weight(1f))
            OutlinedTextField(wake, { wake = it }, label = { Text("起床 HH:mm") }, modifier = Modifier.weight(1f))
        }
        AccentButton("补录作息") {
            scope.launch {
                val day = date.trim()
                when {
                    !pastDate(day) -> note = "日期要早于今天"
                    !clock.matches(sleep.trim()) -> note = "入睡时间要写成 HH:mm"
                    wake.isNotBlank() && !clock.matches(wake.trim()) -> note = "起床时间要写成 HH:mm"
                    else -> {
                        repo.recordSleep(sleep.trim(), wake.trim().ifBlank { null }, sourceBackfill = true, date = day)
                        note = "已补录 $day 作息"
                    }
                }
            }
        }
        Quiet("饮食")
        OutlinedTextField(food, { food = it }, label = { Text("吃了什么") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(calories, { calories = it.filter(Char::isDigit) }, label = { Text("热量 kcal（可空）") }, modifier = Modifier.fillMaxWidth())
        AccentButton("补录饮食") {
            scope.launch {
                val day = date.trim()
                when {
                    !pastDate(day) -> note = "日期要早于今天"
                    food.isBlank() -> note = "先写吃了什么"
                    else -> {
                        repo.recordDiet(
                            mealTypeFor(LocalTime.now().hour),
                            food,
                            calories.toIntOrNull() ?: 0,
                            0f,
                            0f,
                            0f,
                            needsBackfill = calories.isBlank(),
                            backfill = true,
                            date = day,
                        )
                        note = "已补录 $day 饮食"
                    }
                }
            }
        }
        if (note.isNotBlank()) Quiet(note)
    }
}

@Composable
fun ProviderScreen(repo: AetherRepository) {
    val providers by repo.providers.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/audio/transcriptions") }
    var note by remember { mutableStateOf("") }
    val modelsByProvider = remember { mutableStateMapOf<Long, List<String>>() }
    val selectedByProvider = remember { mutableStateMapOf<Long, String>() }
    val errorByProvider = remember { mutableStateMapOf<Long, String>() }
    val keyEdits = remember { mutableStateMapOf<Long, String>() }
    val manualByProvider = remember { mutableStateMapOf<Long, String>() }
    var bindings by remember { mutableStateOf(emptyList<SceneBinding>()) }
    var bindTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var bindHint by remember { mutableStateOf("") }
    val scenes = remember {
        listOf(
            "companion" to "搭档",
            "fast" to "速记",
            "transcribe" to "转写",
            "vision" to "视觉",
            "briefing" to "早报",
            "tts" to "云端音色",
        )
    }
    val labelOf: (String) -> String = { scene -> scenes.firstOrNull { it.first == scene }?.second ?: scene }

    LaunchedEffect(Unit) { bindings = repo.sceneBindings() }

    ScreenColumn {
        Quiet("Key 只加密存在这台手机里，不会进源码。")
        OutlinedTextField(name, { name = it }, label = { Text("渠道名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key,
            { key = it },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(path, { path = it }, label = { Text("转写路径覆盖") }, modifier = Modifier.fillMaxWidth())
        AccentButton("添加") {
            scope.launch {
                if (name.isBlank() || base.isBlank() || key.isBlank()) return@launch
                val id = repo.addProvider(name, base, key, path)
                key = ""
                selectedByProvider.remove(id)
                modelsByProvider.remove(id)
                errorByProvider.remove(id)
                note = "已保存 ${name.trim()}，先拉取模型或手动填模型名"
            }
        }
        if (providers.isEmpty()) {
            Quiet("还没有渠道，先添加 Base URL 与 Key")
        }
        providers.forEach { provider ->
            GlassCard {
                LaunchedEffect(provider.id) {
                    if (modelsByProvider[provider.id] == null) {
                        modelsByProvider[provider.id] = repo.cachedModels(provider.id)
                    }
                }
                val models = modelsByProvider[provider.id].orEmpty()
                val selected = selectedByProvider[provider.id].orEmpty()
                Body(provider.providerName)
                Quiet(provider.baseUrl)
                Quiet("转写 ${provider.transcriptionPath}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("拉取模型") {
                        scope.launch {
                            errorByProvider.remove(provider.id)
                            val result = runCatching {
                                val secret = os.aether.discipline.ai.KeystoreCrypto.decrypt(provider.apiKeyEncrypted)
                                repo.ai.listModels(provider.baseUrl, secret)
                            }
                            result.onSuccess { list ->
                                modelsByProvider[provider.id] = list
                                repo.cacheModels(provider.id, list)
                                if (list.isNotEmpty()) {
                                    selectedByProvider[provider.id] = list.first()
                                    bindTarget = provider.id to list.first()
                                } else {
                                    selectedByProvider.remove(provider.id)
                                }
                                note = "拉到 ${list.size} 个模型"
                            }.onFailure { failure ->
                                errorByProvider[provider.id] = failure.message ?: "拉取模型失败"
                                note = "拉取模型失败"
                            }
                        }
                    }
                    AccentButton("删除") {
                        scope.launch {
                            repo.deleteProvider(provider.id)
                            modelsByProvider.remove(provider.id)
                            selectedByProvider.remove(provider.id)
                            errorByProvider.remove(provider.id)
                            keyEdits.remove(provider.id)
                            manualByProvider.remove(provider.id)
                            if (bindTarget?.first == provider.id) bindTarget = null
                            bindings = repo.sceneBindings()
                            bindHint = "已删除 ${provider.providerName}，相关场景绑定已清空"
                        }
                    }
                }
                val error = errorByProvider[provider.id].orEmpty()
                if (error.isNotBlank()) {
                    Text(error, color = colors.danger, fontSize = 11.sp, maxLines = 2)
                }
                if (models.isNotEmpty()) {
                    Quiet("点一个模型选中，最多显示 12 个" + if (models.size > 12) "（共 ${models.size} 个）" else "")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        models.take(12).chunked(3).forEach { chunk ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                chunk.forEach { model ->
                                    val on = model == selected
                                    val shape = RoundedCornerShape(50)
                                    Text(
                                        model,
                                        Modifier
                                            .weight(1f)
                                            .clip(shape)
                                            .background(if (on) colors.accentSoft else colors.surfaceStrong)
                                            .border(0.8.dp, if (on) colors.accent else colors.rimBottom, shape)
                                            .clickable {
                                                selectedByProvider[provider.id] = model
                                                bindTarget = provider.id to model
                                                bindHint = ""
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        color = if (on) colors.accent else colors.textPrimary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Quiet("还没有模型缓存，点“拉取模型”或手动填写。")
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        manualByProvider[provider.id].orEmpty(),
                        { manualByProvider[provider.id] = it },
                        label = { Text("或手动填写模型名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    AccentButton("使用") {
                        val typed = manualByProvider[provider.id].orEmpty().trim()
                        if (typed.isBlank()) {
                            errorByProvider[provider.id] = "先填写模型名"
                        } else {
                            selectedByProvider[provider.id] = typed
                            bindTarget = provider.id to typed
                            errorByProvider.remove(provider.id)
                            bindHint = ""
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        keyEdits[provider.id].orEmpty(),
                        { keyEdits[provider.id] = it },
                        label = { Text("更换 Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                    AccentButton("更换") {
                        scope.launch {
                            val newKey = keyEdits[provider.id].orEmpty().trim()
                            if (newKey.isBlank()) {
                                note = "新 Key 不能为空"
                            } else {
                                repo.replaceKey(provider.id, newKey)
                                keyEdits[provider.id] = ""
                                note = "已更换 ${provider.providerName} 的 Key"
                            }
                        }
                    }
                }
            }
        }
        GlassCard {
            Body("场景绑定")
            Quiet("已绑定 ${bindings.size} / ${scenes.size}")
            if (bindings.isEmpty()) {
                Quiet("还没有绑定场景。先选一个模型，再点下面的场景。")
            } else {
                bindings.forEach { binding ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Quiet(
                            "${labelOf(binding.scene)} · ${binding.providerName} / ${binding.model}",
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            scope.launch {
                                repo.unbind(binding.scene)
                                bindings = repo.sceneBindings()
                                bindHint = "已解绑：${labelOf(binding.scene)}"
                            }
                        }) { Text("解绑", color = colors.accent, fontSize = 12.sp) }
                    }
                }
            }
        }
        Quiet("点一个场景，绑定当前选中的模型")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            scenes.chunked(3).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEach { (scene, label) ->
                        val bound = bindings.any { it.scene == scene }
                        AccentButton(if (bound) "$label · 已绑" else label) {
                            val target = bindTarget
                            if (target == null) {
                                bindHint = "先选一个模型"
                            } else {
                                scope.launch {
                                    repo.bind(scene, target.first, target.second)
                                    bindings = repo.sceneBindings()
                                    bindHint = "已绑定：$label · ${target.second}"
                                }
                            }
                        }
                    }
                }
            }
        }
        if (bindHint.isNotBlank()) Quiet(bindHint)
        if (note.isNotBlank()) Quiet(note)
    }
}

@Composable
fun RootScreen(repo: AetherRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("") }
    ScreenColumn {
        val owner = DeviceOwnerCtl.isOwner(context)
        Body(if (owner) "当前强度：Device Owner 真拦" else "当前强度：降级，遮罩加记账")
        Quiet("su ${if (Root.available()) "可用" else "不可用"}")
        Quiet("精确闹钟 ${if (exactAlarmAllowed(context)) "已授权" else "未授权"}")
        Quiet("使用情况 ${if (usageAllowed(context)) "已授权" else "未授权"}")
        Quiet("无障碍 ${if (accessibilityOn(context)) "已启用" else "未启用"}")
        Quiet("悬浮窗 ${if (android.provider.Settings.canDrawOverlays(context)) "已授权" else "未授权"}")
        Quiet("全部文件 ${if (Environment.isExternalStorageManager()) "已授权" else "未授权"}")
        AccentButton("精确闹钟") { openExactAlarm(context) }
        AccentButton("使用情况") { openUsage(context) }
        AccentButton("悬浮窗") { openOverlay(context) }
        AccentButton("全部文件") { openAllFiles(context) }
        AccentButton("电池优化") { openBattery(context) }
        AccentButton("Root 修复：白名单 + 无障碍") {
            scope.launch {
                log = Root.exec("dumpsys deviceidle whitelist +${context.packageName}").second + "\n" +
                    Root.enableAccessibility(context).second
            }
        }
        AccentButton("尝试 Device Owner 路径 1") { scope.launch { log = DeviceOwnerCtl.tryPath1(context) } }
        OutlinedTextField(risk, { risk = it }, label = { Text("输入「确认风险」才允许路径 2") })
        AccentButton("路径 2：备份后写系统文件") {
            if (risk == "确认风险") scope.launch { log = DeviceOwnerCtl.tryPath2(context) } else log = "先输入确认风险"
        }
        AccentButton("重启使路径 2 生效") { if (risk == "确认风险") Root.exec("reboot") }
        AccentButton("解除设备管理员") { scope.launch { log = DeviceOwnerCtl.clear(context) } }
        AccentButton("立刻做一份快照") { scope.launch { log = repo.backupNow() } }
        if (log.isNotBlank()) Quiet(log.take(800))
        Quiet("路径 2 写错可能导致只能恢复出厂。备份在 Download/DSH-Go/backup。")
    }
}

private fun optionsOf(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    List(array.length()) { array.getString(it) }
}.getOrDefault(emptyList())

private fun optionKey(option: String): String = Regex("^([A-D])\\.").find(option)?.groupValues?.get(1) ?: option

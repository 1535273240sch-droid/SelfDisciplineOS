package os.aether.discipline.ui

import android.Manifest
import android.content.Intent
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import os.aether.discipline.data.db.DrivingQuestion
import os.aether.discipline.domain.SyllabusGaps
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
import java.util.Locale

@Composable
fun DrivingScreen(repo: AetherRepository) {
    val scope = rememberCoroutineScope()
    var subject by remember { mutableIntStateOf(1) }
    var mock by remember { mutableStateOf(false) }
    var questions by remember { mutableStateOf<List<DrivingQuestion>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var answered by remember { mutableStateOf<Boolean?>(null) }
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
        PageTitle("驾考练习")
        Quiet("驾考 · 内置题按公开的 163/172 号令口径，不是商业题库")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(if (subject == 1) "科一 ·" else "科一") { subject = 1 }
            AccentButton(if (subject == 4) "科四 ·" else "科四") { subject = 4 }
            AccentButton(if (mock) "模考中" else "模考") { mock = !mock; left = if (subject == 4) 30 * 60 else 45 * 60 }
        }
        AccentButton(if (questions.isNotEmpty() && !done) "重新开始" else "开始练习") {
            if (submitting) return@AccentButton
            submitting = true
            scope.launch {
                val selectedSubject = subject
                val selectedMode = mock
                try {
                    questions = repo.paper(selectedSubject, selectedMode)
                    index = 0
                    correct = 0
                    done = false
                    explain = ""
                    picked = emptySet()
                    answered = null
                    sessionMock = selectedMode
                    sessionSubject = selectedSubject
                    left = if (selectedSubject == 4) 30 * 60 else 45 * 60
                    session += 1
                    note = if (questions.isEmpty()) "本题库没有所选科目的题目。" else ""
                } finally {
                    submitting = false
                }
            }
        }
        if (sessionMock && questions.isNotEmpty() && !done) Quiet("剩余 ${left / 60}:${"%02d".format(left % 60)}")
        if (note.isNotBlank()) Quiet(note)
        val question = questions.getOrNull(index)
        if ((question == null && questions.isNotEmpty()) || done) {
            Body(if (questions.isEmpty()) "题库还是空的" else "这组 ${correct}/${questions.size}")
        }
        if (question != null && !done) {
            GlassCard {
                Quiet("第 ${index + 1} / ${questions.size} 题")
                Body(question.content)
                val multiple = question.questionType.contains("MULTI", ignoreCase = true) ||
                    question.questionType.contains("多选") || question.correctAnswer.count { it in 'A'..'D' } > 1
                optionsOf(question.optionsJson).forEach { option ->
                    val key = optionKey(option)
                    val colors = LocalAether.current
                    Text(
                        option, color = if (key in picked) colors.accent else colors.textPrimary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (key in picked) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = answered == null && !submitting) {
                                picked = if (!multiple) setOf(key) else if (key in picked) picked - key else picked + key
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
                val currentAnswer = answered
                if (currentAnswer == null) {
                    PrimaryButton("确认答案", enabled = picked.isNotEmpty() && !submitting) {
                        submitting = true
                        scope.launch {
                            try {
                                val ok = repo.answer(question, picked, if (sessionMock) "MOCK" else "DAILY")
                                if (ok) correct += 1
                                answered = ok
                                explain = question.officialExplanation
                            } finally {
                                submitting = false
                            }
                        }
                    }
                } else {
                    Quiet(if (currentAnswer) "回答正确" else "正确答案 ${question.correctAnswer}")
                    if (explain.isNotBlank()) Quiet(explain)
                    if (explain.isNotBlank()) AccentButton("AI 拆解") {
                        val explainedIndex = index
                        scope.launch {
                            val bound = repo.bound("companion")
                            val explanation = if (bound == null) question.officialExplanation else {
                                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "只解释这道已有题目，不要出新题。用大白话讲扣分逻辑，再给一句顺口溜。\n${question.content}\n${question.optionsJson}\n答案 ${question.correctAnswer}\n${question.officialExplanation}"))
                                repo.ai.chat(bound.provider.baseUrl, bound.key, bound.model, messages, temperature = 0.4).content.ifBlank { question.officialExplanation }
                            }
                            if (index == explainedIndex && answered != null) explain = explanation
                        }
                    }
                    PrimaryButton(if (index + 1 >= questions.size) "完成练习" else "下一题") {
                        answered = null
                        picked = emptySet()
                        explain = ""
                        if (index + 1 >= questions.size) done = true else index += 1
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
    val colors = LocalAether.current
    val tts = remember { TextToSpeech(context, null) }
    val recorder = remember { WavHold(java.io.File(context.cacheDir, "english/practice.wav")) {} }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var rate by remember { mutableStateOf(1f) }
    var note by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(emptyList<Phrase>()) }
    var completed by remember { mutableStateOf(emptySet<String>()) }
    DisposableEffect(Unit) {
        onDispose {
            tts.shutdown()
            recorder.stop().delete()
        }
    }
    LaunchedEffect(Unit) {
        daily = repo.dailyPhrases(count = repo.loadThresholds().dailyEnglish)
        completed = repo.db.english().forDate(AetherRepository.todayString()).map { it.sentence }.toSet()
    }
    val phrase = daily.firstOrNull { it.english !in completed }
    fun startRecording() {
        if (busy || recording || phrase == null) return
        note = ""
        recorder.start()
        recording = true
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else note = "未授权麦克风，可以手动确认跟读。"
    }
    fun saveResult(practice: Phrase, score: Int, message: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                repo.logEnglish(practice.scene, practice.english, score)
                note = message
                completed = completed + practice.english
            } finally {
                busy = false
            }
        }
    }
    fun finishRecording() {
        val practice = phrase ?: return
        if (!recording || busy) return
        recording = false
        busy = true
        scope.launch {
            try {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recorder.stop() }
                val bound = repo.bound("transcribe")
                if (bound == null) {
                    note = "语音评分需要绑定转写模型。你可以手动确认完成。"
                    return@launch
                }
                if (file.length() <= 1000L) {
                    note = "没有录到这一句，请重试。"
                    return@launch
                }
                val heard = repo.ai.transcribe(bound.provider.baseUrl, bound.key, bound.path, bound.model, file)
                if (heard.isBlank()) {
                    note = "没听清，请再读一次。"
                    return@launch
                }
                val score = similarity(practice.english, heard)
                repo.logEnglish(practice.scene, practice.english, score)
                note = "$score 分 · $heard\n${wordDiff(practice.english, heard)}"
                completed = completed + practice.english
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                note = "语音评分失败，可重试或手动确认完成。"
            } finally {
                busy = false
                recorder.stop().delete()
            }
        }
    }
    LaunchedEffect(recording) {
        if (recording) {
            delay(30_000)
            finishRecording()
        }
    }
    ScreenColumn {
        PageTitle("场景英语", "今日 ${daily.count { it.english in completed }} / ${daily.size} 句")
        if (note.isNotBlank()) GlassCard { Body(note) }
        when {
            daily.isEmpty() -> Quiet("正在读取今日练习…")
            phrase == null -> GlassCard { SectionTitle("今日跟读已完成") }
            else -> GlassCard {
                Quiet(phrase.scene)
                SectionTitle(phrase.english)
                Body(phrase.chinese)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton(if (rate == 1f) "1.0× ·" else "1.0×") { rate = 1f }
                    AccentButton(if (rate == 0.75f) "0.75× ·" else "0.75×") { rate = 0.75f }
                    AccentButton("听发音") {
                        if (!recording) {
                            tts.language = Locale.US
                            tts.setSpeechRate(rate)
                            tts.speak(phrase.english, TextToSpeech.QUEUE_FLUSH, null, phrase.id)
                        }
                    }
                }
                PrimaryButton(
                    if (recording) "结束录音并评分" else if (busy) "处理中…" else "录音评分",
                    enabled = !busy,
                ) {
                    if (recording) finishRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) startRecording()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (!recording && !busy) {
                    AccentButton("确认已跟读") { saveResult(phrase, 0, "已记录跟读") }
                }
            }
        }
    }
}


@Composable
fun WorkoutScreen(onStrength: () -> Unit) {
    val context = LocalContext.current
    val live by os.aether.discipline.system.WorkoutBus.state.collectAsStateWithLifecycle()
    var pendingType by remember { mutableStateOf("RUN") }
    var permissionNote by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) startWorkout(context, pendingType) else permissionNote = "需要精确定位权限才能记录轨迹。"
    }
    ScreenColumn {
        PageTitle("训练", "跑步、散步与力量训练")
        SectionTitle(if (live.type == "WALK") "散步" else "跑步")
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
        if (permissionNote.isNotBlank()) Quiet(permissionNote)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!live.active) AccentButton("开始跑步") {
                permissionNote = ""
                pendingType = "RUN"
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS))
            }
            if (!live.active) AccentButton("开始散步") {
                permissionNote = ""
                pendingType = "WALK"
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS))
            }
            if (live.active) AccentButton("结束并保存") {
                context.startService(Intent(context, LocationService::class.java).putExtra("stop", true))
            }
        }
        Quiet("轨迹保存在手机上。")
        PrimaryButton("打开囚徒健身", onClick = onStrength)
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
        PageTitle("囚徒健身", "输入组数和每组次数，保存后更新训练进度。")
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
        PageTitle("数据与阈值")
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
fun RootScreen(repo: AetherRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("") }
    ScreenColumn {
        PageTitle("Root 与锁屏")
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

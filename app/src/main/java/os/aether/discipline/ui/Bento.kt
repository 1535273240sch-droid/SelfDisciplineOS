package os.aether.discipline.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.TodayUi
import os.aether.discipline.domain.greetingForHour
import os.aether.discipline.domain.mealTypeFor
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalTime

@Composable
fun BentoToday(repo: AetherRepository, nav: NavHostController, onCompanion: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var today by remember { mutableStateOf(TodayUi()) }
    var shot by remember { mutableStateOf("scale") }
    var vision by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf("") }
    val file = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { vision = readVision(repo, file, shot) }
    }
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(uri)
    }
    LaunchedEffect(Unit) { while (true) { today = repo.today(); delay(1500) } }
    val headerDate = remember(today.date, today.weekday) { headerDate(today.date, today.weekday) }
    ScreenColumn(spacing = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Quiet(headerDate.ifBlank { "今天" })
            Text(greetingForHour(LocalTime.now().hour), color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Quiet("连续自律 ${today.streak} 天 · 最高 ${today.maxStreak} 天")
        }
        GlassCard {
            Text(
                if (today.planSource == "ai") "今日计划（AI 生成）" else "今日计划",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (today.planSource != "ai") {
                Quiet(
                    if (today.aiReady) "还没按状态生成，下面是本地底稿。"
                    else "模型未绑定。这是本地安排，应用可以照常记。",
                )
            }
            if (generating) {
                Quiet("正在按今天的状态生成…")
            } else if (today.planItems.isEmpty()) {
                Quiet("今天的安排还在整理。")
            } else {
                today.planItems.forEach { Body("• $it") }
            }
            if (today.planNote.isNotBlank()) Quiet(today.planNote)
            if (planError.isNotBlank()) Text(planError, color = colors.danger, fontSize = 12.sp, lineHeight = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    if (generating) "生成中…" else "按状态重新生成",
                    modifier = Modifier,
                    enabled = !generating,
                ) {
                    scope.launch {
                        generating = true
                        planError = ""
                        val (_, error) = repo.generateTodayPlan()
                        planError = error.orEmpty()
                        today = repo.today()
                        generating = false
                    }
                }
                Text(
                    "微调",
                    color = colors.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onCompanion).padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            if (!today.aiReady || planError.isNotBlank()) {
                Text(
                    "打开模型路由",
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { nav.navigate("providers") }.padding(top = 4.dp),
                )
            }
        }
        GlassCard(padding = 12.dp) {
            Body("今日硬项")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusChip("作息", sleepLabel(today.sleepOk), sleepTone(today.sleepOk), Modifier.weight(1f))
                StatusChip("学习", studyState(today), studyTone(today), Modifier.weight(1f))
                StatusChip("录入", if (today.dataMet) "已完成" else "未完成", if (today.dataMet) Tone.Done else Tone.Idle, Modifier.weight(1f))
            }
        }
        TodayLink(Icons.AutoMirrored.Filled.MenuBook, "学习", studyLine(today)) { nav.navigate("lab") }
        TodayLink(
            Icons.Filled.Restaurant,
            "饮食",
            dietLine(today),
            trailing = {
                Text(
                    "识秤",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { shot = "scale"; permit.launch(Manifest.permission.CAMERA) }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
                Text(
                    "识餐",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { shot = "meal"; permit.launch(Manifest.permission.CAMERA) }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            },
        ) { nav.navigate("backfill") }
        TodayLink(Icons.AutoMirrored.Filled.DirectionsWalk, "训练", trainLine(today)) { nav.navigate("workout") }
        TodayLink(Icons.Filled.Lock, "锁屏", lockLine(today)) { nav.navigate("root") }
        if (vision.isNotBlank()) {
            GlassCard {
                Body(vision.take(280))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("入库") { scope.launch { saveVision(repo, vision, shot); vision = "" } }
                    AccentButton("丢弃") { vision = "" }
                }
            }
        }
    }
}

private enum class Tone { Done, Active, Idle }

@Composable
private fun StatusChip(title: String, state: String, tone: Tone, modifier: Modifier) {
    val colors = LocalAether.current
    val tint = when (tone) {
        Tone.Done -> colors.up
        Tone.Active -> colors.accent
        Tone.Idle -> colors.textSecondary
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Column {
            Quiet(title)
            Text(state, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TodayLink(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = LocalAether.current
    GlassCard(Modifier.fillMaxWidth().height(84.dp), padding = 12.dp) {
        Row(Modifier.fillMaxSize().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = title, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Body(title)
                Quiet(subtitle)
            }
            trailing()
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

private fun headerDate(iso: String, weekday: String): String {
    val parsed = runCatching { java.time.LocalDate.parse(iso) }.getOrNull() ?: return weekday
    return "${parsed.monthValue}月${parsed.dayOfMonth}日 $weekday"
}

private fun sleepLabel(ok: Boolean?) = when (ok) {
    true -> "已完成"
    false -> "未完成"
    null -> "未记录"
}

private fun sleepTone(ok: Boolean?) = when (ok) {
    true -> Tone.Done
    false -> Tone.Idle
    null -> Tone.Idle
}

private fun studyState(today: TodayUi) = when {
    today.drivingMet -> "已完成"
    today.drivingDone > 0 -> "进行中"
    else -> "未完成"
}

private fun studyTone(today: TodayUi) = when {
    today.drivingMet -> Tone.Done
    today.drivingDone > 0 -> Tone.Active
    else -> Tone.Idle
}

private fun studyLine(today: TodayUi): String {
    val name = if (today.hardUnit == "题") "驾考" else today.hardTitle.substringBefore("（").substringBefore("(").ifBlank { "学习" }.take(8)
    return "$name ${today.drivingDone}/${today.drivingTarget} ${today.hardUnit}".trim()
}

private fun dietLine(today: TodayUi): String = when {
    today.calorieLimit > 0 -> "${today.calories} / ${today.calorieLimit} kcal"
    today.calories > 0 -> "${today.calories} kcal"
    else -> "还没记录"
}

private fun trainLine(today: TodayUi): String {
    val fromPlan = today.planItems.firstOrNull { it.contains("散步") || it.contains("训练") || it.contains("跑") }
    return (fromPlan ?: today.fitnessName).ifBlank { "跑步或散步" }
}

private fun lockLine(today: TodayUi): String {
    val parts = today.countdownClock.split(":")
    val hours = parts.getOrNull(0)?.toIntOrNull()
    val mins = parts.getOrNull(1)?.toIntOrNull()
    val remain = if (today.inLock || hours == null || mins == null) "锁屏中" else "剩余 $hours 小时 $mins 分"
    return today.lockCaption.removeSuffix(" 锁定") + " · " + remain
}

private suspend fun readVision(repo: AetherRepository, file: File, kind: String): String {
    val bound = repo.bound("vision") ?: return "还没绑定视觉模型"
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return "没有读到照片"
    val width = 1024
    val height = (bitmap.height * (width / bitmap.width.toFloat())).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val bytes = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
    val prompt = if (kind == "scale") "只返回 JSON {\"kg\":72.4}。读电子秤。读不到就 {\"kg\":null}。" else "只返回 JSON {\"items\":\"\",\"calories\":0,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0}。"
    return runCatching { repo.ai.vision(bound.provider.baseUrl, bound.key, bound.model, prompt, bytes.toByteArray()) }.getOrElse { it.message ?: "视觉请求失败" }
}

private suspend fun saveVision(repo: AetherRepository, text: String, kind: String) {
    val json = parseVisionJson(text) ?: return
    if (kind == "scale") {
        if (!json.isNull("kg")) repo.recordWeight(json.optDouble("kg").toFloat(), true, "SCALE_OCR")
    } else {
        val items = json.optString("items").trim()
        if (items.isBlank()) return
        repo.recordDiet(
            mealTypeFor(LocalTime.now().hour),
            items,
            json.optInt("calories"),
            json.optDouble("protein_g").toFloat(),
            json.optDouble("carbs_g").toFloat(),
            json.optDouble("fat_g").toFloat(),
            needsBackfill = json.optInt("calories") == 0,
        )
    }
}

private fun parseVisionJson(text: String): JSONObject? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
}

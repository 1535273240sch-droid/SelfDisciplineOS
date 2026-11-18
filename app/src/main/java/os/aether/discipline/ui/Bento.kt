package os.aether.discipline.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.TodayUi
import os.aether.discipline.domain.mealTypeFor
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalTime

@Composable
fun BentoToday(repo: AetherRepository, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var today by remember { mutableStateOf(TodayUi()) }
    var briefOpen by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf("scale") }
    var vision by remember { mutableStateOf("") }
    val file = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { vision = readVision(repo, file, shot) }
    }
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(uri)
    }
    LaunchedEffect(Unit) { while (true) { today = repo.today(); delay(1500) } }
    val bar by animateFloatAsState((today.drivingDone / today.drivingTarget.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f), aetherSpring(), label = "bar")
    ScreenColumn {
        GlassCard(padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Metric("连续 ${today.streak} 天", size = 18)
                    Quiet("最高纪录 ${today.maxStreak} 天 · 不断链")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("作息", today.sleepOk == true)
                    Pill("硬项", today.drivingMet)
                    Pill("录入", today.dataMet)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(Modifier.weight(1.45f).clickable { nav.navigate("driving") }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Quiet("今日硬指标")
                    Quiet(today.hardHint)
                }
                Metric("${today.drivingDone} / ${today.drivingTarget} ${today.hardUnit}", size = 22)
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(99.dp)).background(colors.textSecondary.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(bar).height(6.dp).background(colors.accent))
                }
                Quiet(today.hardDetail.ifBlank { today.hardTitle })
            }
            GlassCard(Modifier.weight(1f)) {
                Quiet("物理强锁屏")
                Metric(today.countdownClock, size = 26)
                Quiet(today.lockCaption)
                Quiet(today.guardText)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Quiet("空腹体重")
                    AccentButton("识秤") { shot = "scale"; permit.launch(Manifest.permission.CAMERA) }
                }
                Metric(today.weightValue, size = 26)
                Quiet(if (today.deltaText.isBlank()) "较昨日 —" else "较昨日 ${today.deltaText}")
            }
            GlassCard(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Quiet("热量摄入")
                    AccentButton("识餐") { shot = "meal"; permit.launch(Manifest.permission.CAMERA) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    CalorieRing(today.calories, today.calorieLimit, Modifier.size(64.dp))
                    Column(Modifier.weight(1f)) {
                        Metric(if (today.calorieLimit == 0) "未设上限" else "上限 ${today.calorieLimit}", size = 15)
                        Quiet("蛋白 ${today.protein}g · 碳水 ${today.carbs}g")
                        Quiet("脂肪 ${today.fat}g")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(Modifier.weight(1f).clickable { nav.navigate("english") }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Quiet("美式自由行")
                    Quiet(today.travelText)
                }
                Metric("${today.englishDone} / ${today.englishTarget}", size = 22)
                Quiet(today.englishScene.ifBlank { "场景跟读" })
            }
            GlassCard(Modifier.weight(1f).clickable { nav.navigate("workout") }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Quiet("体能与运动")
                    Quiet("GPS 就绪")
                }
                Body(today.fitnessName)
                Quiet(today.fitnessGate)
            }
        }
        GlassCard(Modifier.clickable { briefOpen = !briefOpen }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body("08:00 垂直情报早报")
                Quiet(if (briefOpen) "收起" else "展开")
            }
            val lines = if (briefOpen) today.briefingHead + today.briefingRest else today.briefingHead
            if (lines.isEmpty()) Quiet(today.briefing.take(180)) else lines.forEach { Quiet(it.take(120)) }
            if (today.briefingMiss > 0) Quiet("${today.briefingMiss} 个源今天不可达")
        }
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

@Composable
private fun CalorieRing(consumed: Int, limit: Int, modifier: Modifier = Modifier) {
    val colors = LocalAether.current
    val target = if (limit <= 0) 1 else limit
    val ratio by animateFloatAsState((consumed.toFloat() / target).coerceIn(0f, 1f), aetherSpring(), label = "kcal")
    val over = limit in 1..consumed
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                colors.textSecondary.copy(alpha = 0.14f),
                0f, 360f, false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (ratio > 0f) {
                val sweep = colors.accent.let { if (over) colors.danger else it }
                drawArc(
                    brush = Brush.sweepGradient(listOf(sweep.copy(alpha = 0.55f), sweep, sweep.copy(alpha = 0.55f))),
                    startAngle = -90f, sweepAngle = 360f * ratio, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Metric("$consumed", size = 17)
            Quiet("kcal")
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean) {
    val colors = LocalAether.current
    Quiet(
        label,
        Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) colors.accentSoft else colors.textSecondary.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
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

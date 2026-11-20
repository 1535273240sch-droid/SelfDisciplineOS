package os.aether.discipline.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.TodayUi
import os.aether.discipline.domain.extractJsonObject
import os.aether.discipline.domain.greetingForHour
import os.aether.discipline.domain.mealTypeFor
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun BentoToday(repo: AetherRepository, nav: NavHostController, onCompanion: () -> Unit) {
    val today by remember(repo) { repo.observeToday() }.collectAsStateWithLifecycle(TodayUi())
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var generating by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf("") }
    val date = LocalDate.now()
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Quiet(if (today.date.isBlank()) "今天" else "${date.monthValue}月${date.dayOfMonth}日 · ${today.weekday}")
                PageTitle(greetingForHour(LocalTime.now().hour))
            }
            Box(
                Modifier.size(46.dp).glassSurface(CircleShape, floating = true)
                    .clickable(role = Role.Button) { nav.navigate("themes") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, "切换主题", tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        }
        DayProgress(today)
        GlassCard(padding = 16.dp) {
            SectionTitle("三项硬指标")
            val sleepLabel = when (today.sleepOk) {
                true -> "已达标"
                false -> "超过作息红线"
                null -> "未记录昨晚作息"
            }
            ChecklistRow(Icons.Filled.Bedtime, "作息", sleepLabel, today.sleepOk == true && today.sleepLogged) {
                nav.navigate("record/sleep")
            }
            ChecklistRow(
                Icons.AutoMirrored.Filled.MenuBook, "学习",
                "${today.hardTitle.substringBefore("（")} · ${today.drivingDone}/${today.drivingTarget} ${today.hardUnit}",
                today.drivingMet,
            ) { nav.navigate("lab") }
            val missing = listOfNotNull(
                "体重".takeUnless { today.weightLogged },
                "饮食".takeUnless { today.dietLogged },
                "作息".takeUnless { today.sleepLogged },
            )
            ChecklistRow(Icons.Filled.EditNote, "录入", if (missing.isEmpty()) "体重、饮食、作息已记录" else "待记录 " + missing.joinToString("、"), today.dataMet) {
                nav.navigate("record/all")
            }
        }
        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("今日计划", Modifier.weight(1f))
                Quiet(if (today.planSource == "ai") "AI 计划" else "本地计划")
            }
            if (today.planItems.isEmpty()) Quiet("正在读取今日安排…")
            today.planItems.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(colors.accent))
                    Text(item, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            if (today.planNote.isNotBlank()) Quiet(today.planNote)
            if (planError.isNotBlank()) Text(planError, color = colors.danger, fontSize = 12.sp, lineHeight = 18.sp)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (today.aiReady) {
                    PrimaryButton(if (generating) "生成中…" else "按状态重排", Modifier.weight(1f), enabled = !generating) {
                        scope.launch {
                            generating = true
                            planError = ""
                            try {
                                planError = repo.generateTodayPlan().second.orEmpty()
                            } finally {
                                generating = false
                            }
                        }
                    }
                } else {
                    PrimaryButton("接入模型", Modifier.weight(1f)) { nav.navigate("providers") }
                }
                AccentButton("找搭档微调", onCompanion)
            }
        }
        SectionTitle("快捷入口")
        GlassCard {
            ChecklistRow(Icons.Filled.Restaurant, "饮食与体重", "${today.calories} kcal · ${today.weightText}") {
                nav.navigate("record/diet")
            }
            CameraActions(repo)
            ChecklistRow(Icons.Filled.FitnessCenter, "训练", today.fitnessName) { nav.navigate("workout") }
            ChecklistRow(Icons.Filled.Insights, "趋势复盘", "最近 14 天的记录") { nav.navigate("trends") }
            ChecklistRow(Icons.Filled.Lock, "锁屏", if (today.inLock) "锁屏时段" else today.lockCaption) {
                nav.navigate("root")
            }
        }
    }
}

@Composable
private fun DayProgress(today: TodayUi) {
    val colors = LocalAether.current
    val label = when {
        today.status == "BROKEN" -> "今日已断链"
        today.hardDone == 3 -> "今日已达标"
        else -> "今日进行中"
    }
    GlassCard(padding = 18.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("连续自律", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric(today.streak.toString(), size = 44)
                    Quiet("天", Modifier.padding(bottom = 8.dp))
                }
                Quiet("最长 ${today.maxStreak} 天 · $label")
            }
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                val states = listOf(today.sleepOk == true && today.sleepLogged, today.drivingMet, today.dataMet)
                Canvas(Modifier.matchParentSize()) {
                    val inset = 6.dp.toPx()
                    states.forEachIndexed { index, complete ->
                        drawArc(
                            color = if (complete) colors.accent else colors.accent.copy(alpha = 0.17f),
                            startAngle = -84f + index * 120f,
                            sweepAngle = 108f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Metric("${today.hardDone}/3", size = 24)
                    Quiet("已完成")
                }
            }
        }
    }
}

@Composable
internal fun ChecklistRow(
    icon: ImageVector, title: String, detail: String, complete: Boolean = false, onClick: () -> Unit,
) {
    val colors = LocalAether.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(37.dp).clip(RoundedCornerShape(12.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Quiet(detail)
        }
        Icon(
            if (complete) Icons.Filled.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            if (complete) "已完成" else null,
            tint = if (complete) colors.up else colors.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun CameraActions(repo: AetherRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalAether.current
    var shot by rememberSaveable { mutableStateOf("scale") }
    var result by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val file = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            busy = true
            try {
                result = readVision(repo, file, shot)
                if (visionJson(result, shot) == null) {
                    result = ""
                    error = "没有识别到可保存的数据，请重拍或手动填写。"
                }
            } catch (errorCause: CancellationException) {
                throw errorCause
            } catch (errorCause: Exception) {
                error = errorCause.message ?: "识别失败，请重试。"
            } finally {
                busy = false
                file.delete()
            }
        }
    }
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(uri) else error = "相机权限未授权。"
    }
    if (!busy && result.isBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("scale" to "拍秤识别", "meal" to "拍餐识别").forEach { (kind, label) ->
                AccentButton(label) {
                    shot = kind
                    error = ""
                    permit.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }
    if (busy) Quiet("正在处理照片…")
    if (error.isNotBlank()) Text(error, color = colors.danger, fontSize = 12.sp)
    val parsed = remember(result, shot) { visionJson(result, shot) }
    if (parsed != null) {
        Body(if (shot == "scale") "识别到 ${parsed.optDouble("kg")} kg" else "${parsed.optString("items")} · ${parsed.optInt("calories")} kcal")
        if (!busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("确认入库") {
                scope.launch {
                    busy = true
                    try {
                        if (shot == "scale") {
                            repo.recordWeight(parsed.optDouble("kg").toFloat(), true, "SCALE_OCR")
                        } else {
                            repo.recordDiet(
                                mealTypeFor(LocalTime.now().hour), parsed.optString("items"), parsed.optInt("calories"),
                                parsed.optDouble("protein_g", 0.0).toFloat(), parsed.optDouble("carbs_g", 0.0).toFloat(),
                                parsed.optDouble("fat_g", 0.0).toFloat(), needsBackfill = parsed.optInt("calories") == 0,
                            )
                        }
                        result = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "保存失败，识别结果仍可重试。"
                    } finally {
                        busy = false
                    }
                }
            }
            AccentButton("丢弃") { result = "" }
        }
    }
}

private fun visionJson(text: String, kind: String): JSONObject? {
    val json = extractJsonObject(text)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    return json.takeIf {
        if (kind == "scale") it.optDouble("kg") in 30.0..250.0
        else !it.isNull("items") && it.optString("items").isNotBlank()
    }
}

private suspend fun readVision(repo: AetherRepository, file: File, kind: String): String {
    val bound = repo.bound("vision") ?: error("请先在模型路由绑定拍照识别。")
    val jpeg = withContext(Dispatchers.IO) {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, probe)
        val options = BitmapFactory.Options()
        options.inSampleSize = 1
        while (maxOf(probe.outWidth, probe.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("没有读到照片。")
        try {
            val ratio = (1024f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            try {
                ByteArrayOutputStream().use { bytes ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                    bytes.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
    val prompt = if (kind == "scale") "只返回 JSON {\"kg\":72.4}。读电子秤。读不到就 {\"kg\":null}。" else "只返回 JSON {\"items\":\"\",\"calories\":0,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0}。"
    return repo.ai.vision(bound.provider.baseUrl, bound.key, bound.model, prompt, jpeg)
}

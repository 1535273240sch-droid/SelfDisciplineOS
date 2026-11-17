package os.aether.discipline.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import os.aether.discipline.AetherApp
import os.aether.discipline.data.AetherRepository
import os.aether.discipline.data.TodayUi
import java.io.ByteArrayOutputStream
import java.io.File

private val android.content.Context.uiStore by preferencesDataStore("aether_ui")
private val themeKey = stringPreferencesKey("theme")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as AetherApp).repo
        setContent {
            val theme by this@MainActivity.uiStore.data.map { it[themeKey] ?: "frost" }.collectAsStateWithLifecycle(initialValue = "frost")
            var voice by remember { mutableStateOf(false) }
            AetherTheme(theme) {
                val nav = rememberNavController()
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        NavHost(nav, startDestination = "today", modifier = Modifier.weight(1f)) {
                            composable("today") { Dashboard(repo, nav) }
                            composable("lab") { Lab(repo, nav) }
                            composable("settings") { Settings(nav) }
                            composable("driving") { DrivingScreen(repo) }
                            composable("english") { EnglishScreen(repo) }
                            composable("workout") { WorkoutScreen() }
                            composable("calisthenics") { CalisthenicsScreen(repo) }
                            composable("thresholds") { ThresholdScreen(repo) }
                            composable("providers") { ProviderScreen(repo) }
                            composable("root") { RootScreen(repo) }
                        }
                        if (!voice) Bar(nav) { voice = true }
                    }
                    if (voice) VoiceLayer(repo) { voice = false }
                }
            }
        }
    }
}

@Composable
private fun Bar(nav: NavHostController, onVoice: () -> Unit) {
    val colors = LocalAether.current
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(84.dp)) {
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 10.dp)) {
            BarText("今日", "today", current, nav, Modifier.weight(1f))
            BarText("训练", "lab", current, nav, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            BarText("设置", "settings", current, nav, Modifier.weight(1f))
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = 4.dp)
                .size(64.dp)
                .clip(CircleShape)
                .clickable(onClick = onVoice),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(64.dp)) { drawCircle(colors.accent.copy(alpha = 0.92f)) }
            Text("说", color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun BarText(label: String, route: String, current: String?, nav: NavHostController, modifier: Modifier) {
    val colors = LocalAether.current
    Box(modifier.clickable { nav.navigate(route) { launchSingleTop = true } }, contentAlignment = Alignment.Center) {
        Text(label, color = if (current == route) colors.accent else colors.textSecondary)
    }
}

@Composable
private fun Dashboard(repo: AetherRepository, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var today by remember { mutableStateOf(TodayUi()) }
    var shotKind by remember { mutableStateOf("scale") }
    var vision by remember { mutableStateOf("") }
    val file = remember { File(context.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() } }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch { vision = readVision(repo, file, shotKind) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(uri)
    }
    LaunchedEffect(Unit) { while (true) { today = repo.today(); delay(1500) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Quiet("${today.date} ${today.weekday}")
        Metric("${today.streak}")
        Quiet("连续天数 · 最长 ${today.maxStreak} · 距锁屏 ${today.countdown}")
        Quiet("今日硬项 ${today.hardDone}/3  ${today.brokenReason}")
        GlassCard {
            Quiet("体重")
            Metric(today.weightText)
            Quiet(today.weightDelta.ifBlank { "还没有对照" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AccentButton("−0.1") { scope.launch { nudge(repo, -0.1f) } }
                AccentButton("+0.1") { scope.launch { nudge(repo, 0.1f) } }
                AccentButton("拍秤") { shotKind = "scale"; cameraPermission.launch(Manifest.permission.CAMERA) }
            }
        }
        GlassCard {
            Quiet("早报")
            Body(today.briefing.take(420))
            if (today.briefingMiss > 0) Quiet("${today.briefingMiss} 个源今天不可达")
            AccentButton("更新", { scope.launch { repo.refreshBriefing() } })
        }
        GlassCard {
            Quiet("热量")
            CalorieRing(today.calories, today.calorieLimit)
            Quiet("蛋白 ${today.protein}g · 碳水 ${today.carbs}g · 脂肪 ${today.fat}g")
            AccentButton("拍餐") { shotKind = "meal"; cameraPermission.launch(Manifest.permission.CAMERA) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(Modifier.weight(1f)) {
                Quiet("驾考")
                Metric("${today.drivingDone}")
                Quiet("/ ${today.drivingTarget}")
                Text("去刷", color = LocalAether.current.accent, modifier = Modifier.clickable { nav.navigate("driving") })
            }
            GlassCard(Modifier.weight(1f)) {
                Quiet("英语")
                Metric("${today.englishDone}")
                Quiet("/ ${today.englishTarget}")
                Text("去读", color = LocalAether.current.accent, modifier = Modifier.clickable { nav.navigate("english") })
            }
        }
        GlassCard {
            Quiet("体能")
            Body(today.steps.ifBlank { "六式还停在第一式" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("训练") { nav.navigate("calisthenics") }
                AccentButton("出门") { nav.navigate("workout") }
            }
        }
        Quiet("${today.examText} · ${today.travelText}")
        if (vision.isNotBlank()) {
            GlassCard {
                Body(vision)
                AccentButton("按这个入库") { scope.launch { applyVision(repo, vision, shotKind); vision = "" } }
                AccentButton("丢弃") { vision = "" }
            }
        }
    }
}

@Composable
private fun CalorieRing(eaten: Int, limit: Int) {
    val colors = LocalAether.current
    val ratio = if (limit <= 0) 0f else (eaten / limit.toFloat()).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(92.dp)) {
            drawArc(colors.border, 0f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawArc(colors.accent, -90f, 360f * ratio, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
        }
        Quiet(if (limit <= 0) "$eaten" else "$eaten/$limit")
    }
}

private suspend fun nudge(repo: AetherRepository, delta: Float) {
    val current = repo.db.weight().latest()?.weightKg ?: return
    repo.recordWeight(current + delta, true, "MANUAL")
}

private suspend fun readVision(repo: AetherRepository, file: File, kind: String): String {
    val bound = repo.bound("vision") ?: return "还没绑定视觉模型。可以先在设置里填渠道。"
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return "没有读到照片"
    val width = 1024
    val height = (bitmap.height * (width / bitmap.width.toFloat())).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val bytes = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
    val prompt = if (kind == "scale") {
        "只返回 JSON，不要解释。格式 {\"kg\":72.4}。这是电子秤屏幕。读不到就 {\"kg\":null}。"
    } else {
        "只返回 JSON，不要解释。格式 {\"items\":\"菜名\",\"calories\":0,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0}。估算这一餐。"
    }
    return runCatching { repo.ai.vision(bound.provider.baseUrl, bound.key, bound.model, prompt, bytes.toByteArray()) }
        .getOrElse { it.message ?: "视觉请求失败" }
}

private suspend fun applyVision(repo: AetherRepository, text: String, kind: String) {
    val json = runCatching { JSONObject(text.substringAfter("{").let { "{" + it.substringBeforeLast("}") + "}" }.trim()) }.getOrNull() ?: return
    if (kind == "scale") {
        if (!json.isNull("kg")) repo.recordWeight(json.optDouble("kg").toFloat(), true, "SCALE_OCR")
    } else {
        repo.recordDiet(
            "LUNCH",
            json.optString("items"),
            json.optInt("calories"),
            json.optDouble("protein_g").toFloat(),
            json.optDouble("carbs_g").toFloat(),
            json.optDouble("fat_g").toFloat(),
            needsBackfill = json.optInt("calories") == 0,
        )
    }
}

@Composable
private fun Lab(repo: AetherRepository, nav: NavHostController) {
    var today by remember { mutableStateOf(TodayUi()) }
    LaunchedEffect(Unit) { today = repo.today() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Quiet("训练与学习")
        GlassCard {
            Body(today.steps.ifBlank { "六式从第一式开始" })
            AccentButton("六艺") { nav.navigate("calisthenics") }
            AccentButton("户外") { nav.navigate("workout") }
        }
        GlassCard {
            Body("驾考 ${today.drivingDone}/${today.drivingTarget} · ${today.phase}")
            AccentButton("刷题") { nav.navigate("driving") }
        }
        GlassCard {
            Body("英语 ${today.englishDone}/${today.englishTarget}")
            Quiet(today.travelText)
            AccentButton("跟读") { nav.navigate("english") }
        }
    }
}

@Composable
private fun Settings(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Quiet("偏好")
        listOf("frost" to "银白磨砂", "titanium" to "钛金属", "parchment" to "羊皮纸", "midnight" to "午夜").forEach { (id, label) ->
            GlassCard(Modifier.clickable {
                scope.launch { context.uiStore.edit { it[themeKey] = id } }
            }) { Body(label) }
        }
        GlassCard(Modifier.clickable { nav.navigate("providers") }) { Body("模型路由") }
        GlassCard(Modifier.clickable { nav.navigate("thresholds") }) { Body("数据与阈值") }
        GlassCard(Modifier.clickable { nav.navigate("root") }) { Body("Root 与锁屏") }
        Quiet("云端朗读会把回复文本再发一次。题库导入在 Root 页旁边的阈值页，不提供导出。")
    }
}

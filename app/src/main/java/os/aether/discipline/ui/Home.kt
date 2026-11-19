package os.aether.discipline.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import os.aether.discipline.AetherApp
import os.aether.discipline.domain.inLockWindow
import os.aether.discipline.system.LockActivity
import os.aether.discipline.system.LockRuntime
import java.time.LocalDateTime

private val android.content.Context.uiStore by preferencesDataStore("aether_ui")
private val themeKey = stringPreferencesKey("theme")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val repo = (application as AetherApp).repo
        lifecycleScope.launch {
            runCatching {
                val t = repo.loadThresholds()
                if (LockRuntime.mode == LockRuntime.LOCKED && inLockWindow(LocalDateTime.now(), t)) {
                    startActivity(Intent(this@MainActivity, LockActivity::class.java))
                    finish()
                }
            }
        }
        setContent {
            val theme by this@MainActivity.uiStore.data.map { it[themeKey] ?: "frost" }.collectAsStateWithLifecycle(initialValue = "frost")
            var voice by remember { mutableStateOf(false) }
            AetherTheme(theme) {
                val nav = rememberNavController()
                Box(Modifier.fillMaxSize()) {
                    // One inset owner for the whole window: status bar, punch-hole, navigation bar, and the keyboard.
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Box(Modifier.weight(1f)) {
                            NavHost(nav, startDestination = "today", modifier = Modifier.fillMaxSize()) {
                                composable("today") { BentoToday(repo, nav) { voice = true } }
                                composable("lab") { StudyScreen(repo, nav) }
                                composable("settings") { Settings(nav) }
                                composable("driving") { DrivingScreen(repo) }
                                composable("english") { EnglishScreen(repo) }
                                composable("workout") { WorkoutScreen() }
                                composable("calisthenics") { CalisthenicsScreen(repo) }
                                composable("thresholds") { ThresholdScreen(repo) }
                                composable("backfill") { BackfillScreen(repo) }
                                composable("providers") { ProviderScreen(repo, nav) }
                                composable("root") { RootScreen(repo) }
                            }
                        }
                        if (!voice) FloatingDock(nav) { voice = true }
                    }
                    AnimatedVisibility(
                        visible = voice,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                        ) + fadeIn(tween(160)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
                        ) + fadeOut(tween(140)),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        VoiceLayer(
                            repo,
                            onClose = { voice = false },
                            onOpenRouting = {
                                voice = false
                                nav.navigate("providers") { launchSingleTop = true }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingDock(nav: NavHostController, onVoice: () -> Unit) {
    val colors = LocalAether.current
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val selected = when (route) {
        "lab", "driving", "english", "workout", "calisthenics" -> "lab"
        "settings", "providers", "thresholds", "backfill", "root" -> "settings"
        else -> "today"
    }
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        DockSurface(Modifier.fillMaxWidth().height(64.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DockItem("今日", selected == "today", Icons.Filled.Home, Modifier.weight(1f)) {
                    nav.navigate("today") { launchSingleTop = true; popUpTo("today") }
                }
                DockItem("学习", selected == "lab", Icons.AutoMirrored.Filled.MenuBook, Modifier.weight(1f)) {
                    nav.navigate("lab") { launchSingleTop = true; popUpTo("today") }
                }
                Column(
                    Modifier.weight(1f).clickable(onClick = onVoice),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "搭档", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Text("搭档", color = colors.accent, fontSize = 11.sp)
                }
                DockItem("设置", selected == "settings", Icons.Filled.Settings, Modifier.weight(1f)) {
                    nav.navigate("settings") { launchSingleTop = true; popUpTo("today") }
                }
            }
        }
    }
}

@Composable
private fun DockItem(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalAether.current
    val tint = if (selected) colors.accent else colors.textSecondary
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun Settings(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ScreenColumn {
        Quiet("偏好")
        listOf("frost" to "银白磨砂", "nebula" to "星云紫", "sunset" to "落日橙", "parchment" to "羊皮纸", "titanium" to "钛金属", "midnight" to "午夜").forEach { (id, label) ->
            GlassCard(Modifier.clickable {
                scope.launch { context.uiStore.edit { it[themeKey] = id } }
            }) { Body(label) }
        }
        GlassCard {
            Body("万能桌面小组件")
            Quiet("磨砂卡片，直接唤起系统钉选。")
            AccentButton("双拼 4×2") { pinWidget(context, os.aether.discipline.widget.ComboWidgetReceiver::class.java) }
            AccentButton("行情 2×2") { pinWidget(context, os.aether.discipline.widget.CryptoWidgetReceiver::class.java) }
            AccentButton("金句 4×2") { pinWidget(context, os.aether.discipline.widget.QuoteWidgetReceiver::class.java) }
        }
        GlassCard(Modifier.clickable { nav.navigate("providers") }) {
            Body("模型路由")
            Quiet("AI 是应用的大脑。填好密钥后先测试连接。")
        }
        GlassCard(Modifier.clickable { nav.navigate("thresholds") }) { Body("数据与阈值") }
        GlassCard(Modifier.clickable { nav.navigate("backfill") }) { Body("补录过去的数据") }
        GlassCard(Modifier.clickable { nav.navigate("root") }) { Body("Root 与锁屏") }
        Quiet("云端朗读会把回复文本再发一次。题库导入在驾考页，不提供导出。")
    }
}

private fun pinWidget(context: android.content.Context, receiver: Class<*>) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    if (android.os.Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(android.content.ComponentName(context, receiver), null, null)
    }
}

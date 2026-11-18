package os.aether.discipline.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val scale by animateFloatAsState(if (voice) 0.95f else 1f, aetherSpring(), label = "sheet")
            AetherTheme(theme) {
                val nav = rememberNavController()
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            if (voice && android.os.Build.VERSION.SDK_INT >= 31) {
                                renderEffect = android.graphics.RenderEffect.createBlurEffect(16f, 16f, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
                            }
                        },
                    ) {
                        // Inset handling lives here, once: content starts below the status bar, never behind it.
                        Box(Modifier.weight(1f).statusBarsPadding()) {
                            NavHost(nav, startDestination = "today", modifier = Modifier.fillMaxSize()) {
                                composable("today") { BentoToday(repo, nav) }
                                composable("lab") { StudyScreen(repo, nav) }
                                composable("settings") { Settings(nav) }
                                composable("driving") { DrivingScreen(repo) }
                                composable("english") { EnglishScreen(repo) }
                                composable("workout") { WorkoutScreen() }
                                composable("calisthenics") { CalisthenicsScreen(repo) }
                                composable("thresholds") { ThresholdScreen(repo) }
                                composable("backfill") { BackfillScreen(repo) }
                                composable("providers") { ProviderScreen(repo) }
                                composable("root") { RootScreen(repo) }
                            }
                        }
                        // The dock is part of the flow, so screens never guess its height.
                        if (!voice) {
                            FloatingDock(
                                nav,
                                onVoice = { voice = true },
                                onLock = {
                                    this@MainActivity.lifecycleScope.launch {
                                        runCatching {
                                            val t = repo.loadThresholds()
                                            if (LockRuntime.mode == LockRuntime.LOCKED || inLockWindow(LocalDateTime.now(), t)) {
                                                startActivity(Intent(this@MainActivity, LockActivity::class.java))
                                            } else {
                                                Toast.makeText(this@MainActivity, "现在不是锁屏时段", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                            )
                        }
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
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.74f),
                    ) {
                        VoiceLayer(repo) { voice = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingDock(nav: NavHostController, onVoice: () -> Unit, onLock: () -> Unit) {
    val colors = LocalAether.current
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
    ) {
        DockSurface(Modifier.fillMaxWidth().height(58.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DockItem("今日", "today", current, nav, Modifier.weight(1f), onLock)
                DockItem("学习", "lab", current, nav, Modifier.weight(1f), onLock)
                Spacer(Modifier.size(64.dp))
                DockItem("设置", "settings", current, nav, Modifier.weight(1f), onLock)
                DockItem("锁屏", "lock", current, nav, Modifier.weight(1f), onLock)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(64.dp)
                .clip(CircleShape)
                .clickable(onClick = onVoice),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(64.dp)) {
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color.White, colors.accent)))
                drawCircle(colors.accent, radius = 11.dp.toPx())
            }
        }
    }
}

@Composable
private fun DockItem(
    label: String,
    route: String,
    current: String?,
    nav: NavHostController,
    modifier: Modifier,
    onLock: () -> Unit,
) {
    val colors = LocalAether.current
    Box(modifier.clickable {
        if (route == "lock") {
            onLock()
        } else {
            nav.navigate(route) { launchSingleTop = true; popUpTo("today") }
        }
    }, contentAlignment = Alignment.Center) {
        Text(label, color = if (current == route) colors.accent else colors.textSecondary, fontSize = 12.sp)
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
        GlassCard(Modifier.clickable { nav.navigate("providers") }) { Body("模型路由") }
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

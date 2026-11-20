package os.aether.discipline.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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

private val Context.uiStore by preferencesDataStore("aether_ui")
private val themeKey = stringPreferencesKey("theme")
internal fun Context.observeAetherTheme() = uiStore.data.map { it[themeKey] ?: "frost" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as AetherApp).repo
        val themes = observeAetherTheme()
        lifecycleScope.launch {
            val thresholds = repo.loadThresholds()
            if (LockRuntime.mode == LockRuntime.LOCKED && inLockWindow(LocalDateTime.now(), thresholds)) {
                startActivity(Intent(this@MainActivity, LockActivity::class.java))
                finish()
            }
        }
        setContent {
            val theme by themes.collectAsStateWithLifecycle(initialValue = "frost")
            var voice by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = voice) { voice = false }
            AetherTheme(theme) {
                val nav = rememberNavController()
                val route = nav.currentBackStackEntryAsState().value?.destination?.route
                val atRoot = route == "today" || route == "lab" || route == "settings" || route == null
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = 620.dp).fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    if (!atRoot) {
                        IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.padding(start = 11.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = LocalAether.current.accent)
                        }
                    }
                    NavHost(
                        nav, startDestination = "today", modifier = Modifier.weight(1f).fillMaxWidth(),
                        enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(100)) },
                        popEnterTransition = { fadeIn(tween(150)) }, popExitTransition = { fadeOut(tween(100)) },
                    ) {
                        composable("today") { BentoToday(repo, nav) { voice = true } }
                        composable("lab") { StudyScreen(repo, nav) }
                        composable("settings") { Settings(nav) }
                        composable("themes") { ThemeSettings() }
                        composable("driving") { DrivingScreen(repo) }
                        composable("english") { EnglishScreen(repo) }
                        composable("workout") { WorkoutScreen { nav.navigate("calisthenics") } }
                        composable("calisthenics") { CalisthenicsScreen(repo) }
                        composable("thresholds") { ThresholdScreen(repo) }
                        composable("record/{section}") { entry ->
                            RecordScreen(repo, initialSection = entry.arguments?.getString("section") ?: "all")
                        }
                        composable("backfill") { RecordScreen(repo, past = true) }
                        composable("trends") { TrendsScreen(repo) }
                        composable("providers") { ProviderScreen(repo) }
                        composable("root") { RootScreen(repo) }
                    }
                    if (atRoot) FloatingDock(nav, voice) { voice = true }
                }
                AnimatedVisibility(
                    visible = voice,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)) + fadeIn(tween(160)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) + fadeOut(tween(140)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AmbientCanvas(Modifier.matchParentSize(), LocalAether.current)
                        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 620.dp).fillMaxSize()) {
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
}

@Composable
private fun FloatingDock(nav: NavHostController, voiceOpen: Boolean, onVoice: () -> Unit) {
    val colors = LocalAether.current
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val selectedIndex = when {
        voiceOpen -> 2
        route == "lab" -> 1
        route == "settings" -> 3
        else -> 0
    }
    val position by animateFloatAsState(selectedIndex.toFloat(), aetherSpring(), label = "选中项滑动")
    DockSurface(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 7.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
            val tabWidth = ((maxWidth - 12.dp) / 4).coerceAtLeast(1.dp)
            Canvas(Modifier.matchParentSize()) {
                val x = 6.dp.toPx() + tabWidth.toPx() * (position + 0.5f)
                val center = Offset(x, size.height - 1.dp.toPx())
                val radius = tabWidth.toPx() * 0.86f
                val glow = if (colors.dark) colors.accent else colors.glow
                scale(1f, 0.105f, pivot = center) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to glow.copy(alpha = if (colors.dark) 0.40f else 0.34f),
                            0.40f to glow.copy(alpha = 0.16f),
                            1f to glow.copy(alpha = 0f),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            }
            Box(
                Modifier.offset(x = 6.dp + tabWidth * position).width(tabWidth).fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 6.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (colors.dark) 0.11f else 0.31f),
                            1f to colors.accent.copy(alpha = if (colors.dark) 0.07f else 0.035f),
                        ),
                    )
                    .glassRim(CircleShape, colors, floating = false),
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp).selectableGroup()) {
                DockItem("今日", selectedIndex == 0, Icons.Filled.Home, Modifier.weight(1f)) {
                    nav.navigate("today") { launchSingleTop = true; popUpTo("today") }
                }
                DockItem("学习", selectedIndex == 1, Icons.AutoMirrored.Filled.MenuBook, Modifier.weight(1f)) {
                    nav.navigate("lab") { launchSingleTop = true; popUpTo("today") }
                }
                DockItem("搭档", selectedIndex == 2, Icons.Filled.Mic, Modifier.weight(1f), onVoice)
                DockItem("设置", selectedIndex == 3, Icons.Filled.Settings, Modifier.weight(1f)) {
                    nav.navigate("settings") { launchSingleTop = true; popUpTo("today") }
                }
            }
        }
    }
}

@Composable
private fun DockItem(label: String, selected: Boolean, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalAether.current
    val tint = if (selected) colors.accent else colors.textSecondary
    val chip = modifier.fillMaxHeight().clip(CircleShape)
    Column(
        if (label == "搭档") chip.clickable(role = Role.Button, onClick = onClick)
        else chip.selectable(selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = if (label == "搭档") colors.accent else tint, modifier = Modifier.size(19.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Settings(nav: NavHostController) {
    val context = LocalContext.current
    ScreenColumn {
        PageTitle("设置", "外观、数据和应用权限")
        GlassCard {
            ChecklistRow(Icons.Filled.Palette, "外观主题", LocalAether.current.name) { nav.navigate("themes") }
            ChecklistRow(Icons.Filled.SmartToy, "模型路由", "搭档、拍照识别、语音等六个场景") { nav.navigate("providers") }
            ChecklistRow(Icons.Filled.Tune, "数据与阈值", "每日目标、考试日期和紧急密码") { nav.navigate("thresholds") }
            ChecklistRow(Icons.Filled.EditCalendar, "补录过去的数据", "历史体重、作息和饮食") { nav.navigate("backfill") }
            ChecklistRow(Icons.Filled.Lock, "Root 与锁屏", "权限检查与守卫设置") { nav.navigate("root") }
        }
        GlassCard {
            SectionTitle("桌面小组件")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccentButton("双拼 4×2") { pinWidget(context, os.aether.discipline.widget.ComboWidgetReceiver::class.java) }
                AccentButton("行情 2×2") { pinWidget(context, os.aether.discipline.widget.CryptoWidgetReceiver::class.java) }
                AccentButton("金句 4×2") { pinWidget(context, os.aether.discipline.widget.QuoteWidgetReceiver::class.java) }
            }
        }
    }
}

@Composable
private fun ThemeSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = LocalAether.current
    ScreenColumn(modifier = Modifier.selectableGroup()) {
        PageTitle("外观主题", "点选即可切换")
        AetherThemes.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { palette ->
                    val selected = current.id == palette.id
                    val shape = GlassCorners(24.dp)
                    Box(
                        Modifier.weight(1f).heightIn(min = 132.dp).clip(shape)
                            .border(if (selected) 2.dp else 1.dp, if (selected) current.accent else palette.rimHighlight, shape)
                            .selectable(selected, role = Role.RadioButton) {
                                scope.launch { context.uiStore.edit { it[themeKey] = palette.id } }
                            },
                    ) {
                        AmbientCanvas(Modifier.matchParentSize(), palette)
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(13.dp)).background(palette.surface)) {
                                Row(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Box(Modifier.size(26.dp, 8.dp).clip(RoundedCornerShape(10.dp)).background(palette.accent))
                                    Box(Modifier.size(14.dp, 8.dp).clip(RoundedCornerShape(10.dp)).background(palette.accent.copy(alpha = 0.25f)))
                                }
                                if (selected) Icon(Icons.Filled.Check, "当前主题", tint = palette.accent, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 9.dp).size(17.dp))
                            }
                            Text(palette.name, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun pinWidget(context: Context, receiver: Class<*>) {
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(android.content.ComponentName(context, receiver), null, null)
    }
}

package os.aether.discipline.ui

import android.app.Activity
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

data class AetherColors(
    val id: String,
    val name: String,
    val background: Color,
    val glow: Color,
    val mist: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val dark: Boolean,
) {
    val surface = if (dark) Color(0xA6192230) else Color(0xA6FFFFFF)
    val surfaceStrong = if (dark) Color(0xEB202634) else Color(0xEBFFFFFF)
    val accentSoft = accent.copy(alpha = if (dark) 0.18f else 0.09f)
    val onAccent = if (dark) Color(0xFF101620) else Color.White
    val rimTop = Color.White.copy(alpha = if (dark) 0.25f else 0.92f)
    val rimBottom = accent.copy(alpha = if (dark) 0.17f else 0.16f)
    val danger = if (dark) Color(0xFFFFA5A0) else Color(0xFFB3293A)
    val up = if (dark) Color(0xFF8BE8BC) else Color(0xFF16634C)
}

private fun theme(
    id: String, name: String, background: Long, glow: Long, mist: Long,
    text: Long, muted: Long, accent: Long, dark: Boolean = false,
) = AetherColors(id, name, Color(background), Color(glow), Color(mist), Color(text), Color(muted), Color(accent), dark)

val AetherThemes = listOf(
    theme("frost", "银白磨砂", 0xFFEAF0FA, 0xFF9FBFFD, 0xFFC0DBF4, 0xFF172039, 0xFF485777, 0xFF254DA9),
    theme("nebula", "星云紫", 0xFFF1EBFC, 0xFFBEA2FA, 0xFFF2C7EE, 0xFF2F1949, 0xFF634D79, 0xFF6636A8),
    theme("sunset", "落日橙", 0xFFFFEFE9, 0xFFF7B4A2, 0xFFF8D39D, 0xFF3B2020, 0xFF7B514A, 0xFFA53D32),
    theme("parchment", "羊皮纸", 0xFFF4EFE3, 0xFFD4C29D, 0xFFE7DCB7, 0xFF2C251A, 0xFF645844, 0xFF775016),
    theme("titanium", "钛金属", 0xFF151B28, 0xFF3C5A80, 0xFF526178, 0xFFF4F7FE, 0xFFB8C7DC, 0xFFB1D2FF, true),
    theme("midnight", "午夜黑", 0xFF080D13, 0xFF1D5B51, 0xFF142939, 0xFFF2FAFA, 0xFFB0C9C8, 0xFF8AEAC4, true),
)

fun themeById(id: String) = AetherThemes.firstOrNull { it.id == id } ?: AetherThemes.first()

val LocalAether = staticCompositionLocalOf { AetherThemes.first() }
private val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

fun aetherSpring() = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)

@Composable
private fun SystemBarStyle(dark: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
fun AetherTheme(id: String, content: @Composable BoxScope.() -> Unit) {
    val colors = themeById(id)
    val haze = rememberHazeState()
    SystemBarStyle(colors.dark)
    CompositionLocalProvider(LocalAether provides colors, LocalHaze provides haze) {
        ThemedMaterial(colors) {
            Box(Modifier.fillMaxSize()) {
                AmbientCanvas(Modifier.fillMaxSize().hazeSource(haze), colors)
                content()
            }
        }
    }
}

@Composable
fun AetherPureBlack(content: @Composable BoxScope.() -> Unit) {
    val colors = themeById("midnight")
    SystemBarStyle(dark = true)
    CompositionLocalProvider(LocalAether provides colors, LocalHaze provides null) {
        ThemedMaterial(colors) {
            Box(Modifier.fillMaxSize().background(Color.Black), content = content)
        }
    }
}

@Composable
private fun ThemedMaterial(colors: AetherColors, content: @Composable () -> Unit) {
    val base = if (colors.dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.surfaceStrong,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.accent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            surfaceContainer = colors.surfaceStrong,
            surfaceContainerHigh = colors.surfaceStrong,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.textSecondary.copy(alpha = 0.55f),
            error = colors.danger,
        ),
        content = content,
    )
}

@Composable
internal fun AmbientCanvas(modifier: Modifier, colors: AetherColors) {
    Canvas(modifier.background(colors.background)) {
        val width = size.width
        val height = size.height
        drawRect(
            Brush.radialGradient(
                listOf(colors.glow.copy(alpha = 0.78f), colors.glow.copy(alpha = 0f)),
                Offset(width * 0.9f, height * 0.07f), width * 1.13f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(colors.mist.copy(alpha = 0.68f), colors.mist.copy(alpha = 0f)),
                Offset(0f, height * 0.52f), width * 0.96f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(colors.glow.copy(alpha = 0.38f), colors.glow.copy(alpha = 0f)),
                Offset(width * 0.88f, height * 0.92f), width * 0.8f,
            ),
        )
    }
}

@Composable
internal fun Modifier.glassSurface(shape: Shape, floating: Boolean = false): Modifier {
    val colors = LocalAether.current
    val haze = LocalHaze.current
    val tint = if (floating) colors.surfaceStrong else colors.surface
    val material = if (haze == null) Modifier.background(tint) else Modifier.hazeEffect(
        state = haze,
        style = HazeStyle(
            backgroundColor = colors.background,
            tints = listOf(HazeTint(tint)),
            blurRadius = if (floating) 30.dp else 20.dp,
            noiseFactor = 0f,
        ),
    )
    return clip(shape).then(material)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (colors.dark) 0.075f else 0.42f), Color.Transparent)))
        .border(1.dp, Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom)), shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glassSurface(RoundedCornerShape(24.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(if (padding == 0.dp) 0.dp else 6.dp),
        content = content,
    )
}

@Composable
fun DockSurface(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glassSurface(RoundedCornerShape(28.dp), floating = true), content = content)
}

@Composable
fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = LocalAether.current.textPrimary, fontSize = 31.sp, fontWeight = FontWeight.Bold, lineHeight = 39.sp)
        subtitle?.let { Quiet(it) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalAether.current.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
}

@Composable
fun Metric(text: String, modifier: Modifier = Modifier, size: Int = 28) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = size.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = 15.sp, lineHeight = 22.sp))
}

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textSecondary, fontSize = 12.sp, lineHeight = 17.sp))
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit) {
    val colors = LocalAether.current
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(colors.accentSoft)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 42.dp).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalAether.current
    Box(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (enabled) colors.accent else colors.textSecondary.copy(alpha = 0.35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) colors.onAccent else colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ScreenColumn(
    spacing: Dp = 14.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

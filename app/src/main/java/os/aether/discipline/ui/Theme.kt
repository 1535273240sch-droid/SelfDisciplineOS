package os.aether.discipline.ui

import android.app.Activity
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
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
    val background: Color,
    val orb1: Color,
    val orb2: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val up: Color,
    val dark: Boolean,
)

private fun theme(
    id: String,
    background: Long,
    orb1: Long,
    orb2: Long,
    surface: Long,
    surfaceStrong: Long,
    rimTop: Long,
    rimBottom: Long,
    text: Long,
    muted: Long,
    accent: Long,
    dark: Boolean,
) = AetherColors(
    id, Color(background), Color(orb1), Color(orb2), Color(surface), Color(surfaceStrong),
    Color(rimTop), Color(rimBottom), Color(text), Color(muted), Color(accent),
    Color(accent).copy(alpha = 0.12f), if (dark) Color(0xFFFF6B6B) else Color(0xFFEF4444),
    Color(0xFF10B981), dark,
)

val Frost = theme("frost", 0xFFF2F4F7, 0xFFBAD6FF, 0xFFD8E4F8, 0xB8FFFFFF, 0xDBFFFFFF, 0xEBFFFFFF, 0x73CBD5E1, 0xFF111827, 0xFF6B7280, 0xFF007AFF, false)
val Nebula = theme("nebula", 0xFFF3F0FA, 0xFFD8C4FF, 0xFFE9DDFF, 0xBDFAF8FF, 0xE0FCFBFF, 0xF2FFFFFF, 0x7AC4B5FD, 0xFF1E1633, 0xFF6D6287, 0xFF7C3AED, false)
val Sunset = theme("sunset", 0xFFFBF3EC, 0xFFFDD8B5, 0xFFFEE7CE, 0xBDFFFBF7, 0xE0FFFCF9, 0xF2FFFFFF, 0x7AFDBA74, 0xFF29180B, 0xFF7C5E48, 0xFFEA580C, false)
val Parchment = theme("parchment", 0xFFF6F3EB, 0xFFEFE4CC, 0xFFF5EEDC, 0xD1FCFBF7, 0xEBFDFCF9, 0xE6FFFFFF, 0x8CD6CDB4, 0xFF23211D, 0xFF736E63, 0xFFB45309, false)
val Titanium = theme("titanium", 0xFF16171A, 0xFF384866, 0xFF282C38, 0xC2222428, 0xE02A2D34, 0x29FFFFFF, 0x0AFFFFFF, 0xFFF3F4F6, 0xFF9CA3AF, 0xFF5E9EFF, true)
val Midnight = theme("midnight", 0xFF000000, 0xFF141816, 0xFF0E0E12, 0xD10F0F12, 0xEB16161A, 0x24FFFFFF, 0x0AFFFFFF, 0xFFFFFFFF, 0xFF8E8E93, 0xFF30D158, true)

fun themeById(id: String) = when (id) {
    "nebula" -> Nebula
    "sunset" -> Sunset
    "parchment" -> Parchment
    "titanium" -> Titanium
    "midnight" -> Midnight
    else -> Frost
}

val LocalAether = staticCompositionLocalOf { Frost }
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

fun aetherSpring() = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)

@Composable
fun AetherTheme(id: String, content: @Composable BoxScope.() -> Unit) {
    val colors = themeById(id)
    val haze = rememberHazeState()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !colors.dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !colors.dark
        }
    }
    CompositionLocalProvider(LocalAether provides colors, LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            AmbientCanvas(Modifier.fillMaxSize().hazeSource(haze), colors)
            content()
        }
    }
}

@Composable
fun AetherPureBlack(content: @Composable BoxScope.() -> Unit) {
    val colors = Midnight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    CompositionLocalProvider(LocalAether provides colors, LocalHaze provides null) {
        Box(Modifier.fillMaxSize().background(Color.Black), content = content)
    }
}

@Composable
private fun AmbientCanvas(modifier: Modifier, colors: AetherColors) {
    androidx.compose.foundation.Canvas(modifier) {
        drawCircle(colors.orb1.copy(alpha = 0.75f), radius = size.minDimension * 0.42f, center = Offset(-40f, -20f))
        drawCircle(colors.orb2.copy(alpha = 0.7f), radius = size.minDimension * 0.48f, center = Offset(size.width + 30f, size.height * 0.38f))
        drawCircle(colors.surface.copy(alpha = 0.55f), radius = size.minDimension * 0.36f, center = Offset(size.width * 0.2f, size.height * 0.82f))
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAether.current
    val haze = LocalHaze.current
    val shape = RoundedCornerShape(22.dp)
    val glass = if (haze != null) {
        Modifier.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = colors.surface,
                tints = listOf(HazeTint(colors.surface.copy(alpha = 0.45f))),
                blurRadius = 26.dp,
                noiseFactor = 0.08f,
            ),
        )
    } else {
        Modifier.background(colors.surface)
    }
    Column(
        modifier
            .clip(shape)
            .then(glass)
            .border(0.8.dp, Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom)), shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun DockSurface(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = LocalAether.current
    val haze = LocalHaze.current
    val shape = RoundedCornerShape(50)
    val glass = if (haze != null) {
        Modifier.hazeEffect(
            haze,
            HazeStyle(colors.surfaceStrong, listOf(HazeTint(colors.surfaceStrong)), 28.dp, 0.06f),
        )
    } else {
        Modifier.background(colors.surfaceStrong)
    }
    Box(modifier.clip(shape).then(glass).border(0.8.dp, Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom)), shape), content = content)
}

@Composable
fun Metric(text: String, modifier: Modifier = Modifier, size: Int = 28) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = size.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = 15.sp, lineHeight = 22.5.sp))
}

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textSecondary, fontSize = 12.sp, lineHeight = 18.sp))
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit) {
    val colors = LocalAether.current
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentSoft).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = colors.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Standard screen container: one scroll column, one horizontal margin, one gap everywhere. */
@Composable
fun ScreenColumn(
    spacing: Dp = 12.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

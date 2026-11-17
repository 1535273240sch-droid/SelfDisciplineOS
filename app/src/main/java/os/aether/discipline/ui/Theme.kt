package os.aether.discipline.ui

import android.app.Activity
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

data class AetherColors(
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val danger: Color,
    val dark: Boolean,
)

val Frost = AetherColors(Color(0xFFF2F4F7), Color(0xCCFFFFFF), Color(0xFFE5E9F0), Color(0xFF111827), Color(0xFF6B7280), Color(0xFF007AFF), Color(0xFFB42318), false)
val Titanium = AetherColors(Color(0xFF16171A), Color(0xFF222428), Color(0xFF32353B), Color(0xFFF3F4F6), Color(0xFF9CA3AF), Color(0xFF5E9EFF), Color(0xFFFF6B6B), true)
val Parchment = AetherColors(Color(0xFFF6F3EB), Color(0xFFFCFBF7), Color(0xFFE4DEC9), Color(0xFF23211D), Color(0xFF736E63), Color(0xFFB45309), Color(0xFF9A3412), false)
val Midnight = AetherColors(Color(0xFF000000), Color(0xFF0D0D0F), Color(0xFF1F1F23), Color(0xFFFFFFFF), Color(0xFF8E8E93), Color(0xFF30D158), Color(0xFFFF453A), true)

fun themeById(id: String) = when (id) {
    "titanium" -> Titanium
    "parchment" -> Parchment
    "midnight" -> Midnight
    else -> Frost
}

val LocalAether = staticCompositionLocalOf { Frost }

@Composable
fun AetherTheme(id: String, content: @Composable () -> Unit) {
    val colors = themeById(id)
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
    CompositionLocalProvider(LocalAether provides colors) {
        Box(Modifier.background(colors.background)) { content() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAether.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .border(0.6.dp, colors.border, RoundedCornerShape(24.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Metric(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textPrimary, fontSize = 15.sp, lineHeight = 22.5.sp))
}

@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = TextStyle(color = LocalAether.current.textSecondary, fontSize = 13.sp, lineHeight = 19.5.sp))
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit) {
    val colors = LocalAether.current
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White,
        fontSize = 14.sp,
    )
}

fun aetherSpring() = spring<Float>(dampingRatio = 0.82f, stiffness = 380f)

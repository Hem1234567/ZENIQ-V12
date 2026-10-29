package com.astra.agent.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
class IosColors(
    val bg: Color, val card: Color, val fill: Color, val text: Color, val sub: Color, val line: Color,
    val accent: Color, val green: Color, val red: Color, val orange: Color, val dark: Boolean
)

val DarkIos = IosColors(Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color.White, Color(0xFF8E8E93),
    Color(0xFF38383A), Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF453A), Color(0xFFFF9F0A), true)
val LightIos = IosColors(Color.White, Color(0xFFF2F2F7), Color(0xFFE9E9EB), Color.Black, Color(0xFF8E8E93),
    Color(0xFFD1D1D6), Color(0xFF007AFF), Color(0xFF34C759), Color(0xFFFF3B30), Color(0xFFFF9500), false)

val LocalIos = staticCompositionLocalOf { LightIos }

@Composable
fun AstraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkIos else LightIos
    val scheme = if (dark) darkColorScheme(background = c.bg, surface = c.bg, onBackground = c.text, onSurface = c.text, primary = c.accent)
    else lightColorScheme(background = c.bg, surface = c.bg, onBackground = c.text, onSurface = c.text, primary = c.accent)
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.text, content = content) }
    }
}

val IosSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

/** iOS-style press: springs down to 94% and bounces back. */
fun Modifier.pressScale(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.93f else 1f, IosSpring, label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(src, null, enabled = enabled, onClick = onClick)
}

@Composable
fun SectionHeader(text: String) {
    Text(text.uppercase(), Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp),
        fontSize = 13.sp, color = LocalIos.current.sub, letterSpacing = 0.3.sp)
}

@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(LocalIos.current.card).animateContentSize(), content = content)
}

@Composable
fun Hairline() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(LocalIos.current.line))
}

@Composable
fun PillButton(text: String, color: Color = LocalIos.current.accent, enabled: Boolean = true,
               modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.pressScale(enabled, onClick).clip(CircleShape).background(color.copy(alpha = if (enabled) 0.15f else 0.07f))
        .padding(horizontal = 16.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

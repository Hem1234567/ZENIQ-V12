package com.astra.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS-style action sheet: dim background, floating rounded card with coloured icon tiles,
 * and a separate bold "Cancel" card underneath.
 */
@Composable
fun AttachSheet(visible: Boolean, onDismiss: () -> Unit, onCamera: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    val c = LocalIos.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (c.dark) 0.55f else 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
        }
        AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.8f, Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140))) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                    Text("Add to chat", Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        fontSize = 13.sp, color = c.sub, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Divider05()
                    SheetRow(Icons.Rounded.CameraAlt, Color(0xFF8E8E93), "Camera", "Take a photo", onCamera)
                    Divider05()
                    SheetRow(Icons.Rounded.PhotoLibrary, Color(0xFFFF9F0A), "Photos", "Choose from your library", onPhotos)
                    Divider05()
                    SheetRow(Icons.Rounded.FolderOpen, c.accent, "Files", "PDF, Word, Excel, PowerPoint, text", onFiles)
                }
                Spacer(Modifier.height(8.dp))
                SheetCancel(onDismiss)
            }
        }
    }
}

@Composable
private fun Divider05() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(LocalIos.current.line))
}

@Composable
private fun SheetRow(icon: ImageVector, tint: Color, label: String, sub: String, onClick: () -> Unit) {
    val c = LocalIos.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth()
        .background(if (pressed) c.fill else Color.Transparent)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 17.sp, color = c.text)
            Text(sub, fontSize = 13.sp, color = c.sub)
        }
    }
}

@Composable
private fun SheetCancel(onClick: () -> Unit) {
    val c = LocalIos.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (pressed) c.fill else c.card)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
        Text("Cancel", fontSize = 17.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
    }
}

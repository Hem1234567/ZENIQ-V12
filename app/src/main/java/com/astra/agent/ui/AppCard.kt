package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown in the chat after ZENIQ builds an app: file list, where it was saved, Download + Share. */
@Composable
fun AppCard(item: AppItem, onDownload: () -> Unit, onShare: () -> Unit) {
    val c = LocalIos.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name + ".zip", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = c.text)
                Text("${item.files.size} files \u00b7 ${item.sizeKb} KB \u00b7 ready to use", fontSize = 13.sp, color = c.sub)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.files.take(8).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = c.sub, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(f, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.text, maxLines = 1)
                }
            }
            if (item.files.size > 8) Text("+ ${item.files.size - 8} more", fontSize = 12.sp, color = c.sub)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = c.green, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Saved to ${item.publicPath}", fontSize = 12.sp, color = c.sub, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Download ZIP") { onDownload() }
            PillButton("Share", c.green) { onShare() }
        }
    }
}

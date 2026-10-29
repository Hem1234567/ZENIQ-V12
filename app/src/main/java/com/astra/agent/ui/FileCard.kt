package com.astra.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown in the chat after ZENIQ creates a PDF / Word / Excel / PowerPoint / CSV file: Open, Download, Share. */
@Composable
fun FileCard(item: FileItem, onOpen: () -> Unit, onDownload: () -> Unit, onShare: () -> Unit, onPreview: (() -> Unit)? = null) {
    val c = LocalIos.current
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val tint: Color = when (ext) {
        "pdf" -> Color(0xFFE5483B)
        "docx" -> Color(0xFF2B579A)
        "xlsx", "csv" -> Color(0xFF217346)
        "pptx" -> Color(0xFFD24726)
        "png", "jpg", "jpeg", "webp" -> Color(0xFF7C4DFF)
        "mp4", "webm" -> Color(0xFFE4572E)
        "html", "htm" -> Color(0xFF0EA5E9)
        else -> c.accent
    }
    val icon: ImageVector = when (ext) {
        "pdf" -> Icons.Rounded.PictureAsPdf
        "xlsx", "csv" -> Icons.Rounded.TableChart
        "pptx" -> Icons.Rounded.Slideshow
        "png", "jpg", "jpeg", "webp" -> Icons.Rounded.Image
        "mp4", "webm" -> Icons.Rounded.Movie
        "html", "htm" -> Icons.Rounded.Language
        else -> Icons.Rounded.Description
    }
    val preview by produceState<ImageBitmap?>(null, item.path) {
        value = if (ext in setOf("png", "jpg", "jpeg", "webp")) withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.path, o)
                var sample = 1
                while (o.outWidth / sample > 1100) sample *= 2
                BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Throwable) { null }
        } else null
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)) {
        val pv = preview
        if (pv != null) {
            Image(pv, contentDescription = item.name, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = c.text)
                Text("${item.kind} \u00b7 ${item.sizeKb} KB \u00b7 ready", fontSize = 13.sp, color = c.sub)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = c.green, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Saved to ${item.publicPath}", fontSize = 12.sp, color = c.sub, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ext == "html" && onPreview != null) PillButton("Preview", c.accent) { onPreview() }
            PillButton("Open") { onOpen() }
            PillButton("Download", c.green) { onDownload() }
            PillButton("Share", c.accent) { onShare() }
        }
    }
}

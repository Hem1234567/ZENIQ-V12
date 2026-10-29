package com.astra.agent.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Shows a generated page full screen. The preview is a separate Android screen ([com.astra.agent.PreviewActivity]) with a
 * real WebView; this composable only starts it. [onClose] is called straight away so the caller's "preview open" state resets.
 */
@Composable
fun SitePreview(path: String, title: String, onClose: () -> Unit, onOpenExternal: () -> Unit, root: String? = null) {
    val ctx = LocalContext.current
    LaunchedEffect(path) {
        try {
            ctx.startActivity(Intent(ctx, com.astra.agent.PreviewActivity::class.java)
                .putExtra(com.astra.agent.PreviewActivity.EXTRA_PATH, path)
                .putExtra(com.astra.agent.PreviewActivity.EXTRA_TITLE, title)
                .putExtra(com.astra.agent.PreviewActivity.EXTRA_ROOT, root))
        } catch (_: Throwable) { onOpenExternal() }
        onClose()
    }
}

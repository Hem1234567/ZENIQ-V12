package com.astra.agent.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.astra.agent.SettingsStore
import com.astra.agent.device.AstraAccessibilityService
import com.astra.agent.llm.OpenRouterClient
import com.astra.agent.llm.ORException
import java.io.ByteArrayOutputStream

/**
 * Lets the control agent LOOK at the screen: takes a screenshot through the accessibility service and asks an online vision model
 * what is on it and where to tap. The screenshot is sent to OpenRouter only for that request and is never stored.
 * The on-device text model cannot see images, so this needs the online model (an OpenRouter key).
 */
object ScreenVision {
    suspend fun describe(ctx: Context, svc: AstraAccessibilityService, task: String): String {
        val cfg = SettingsStore(ctx).load()
        val key = cfg.openRouterKey
        if (key.isBlank()) return "Looking at the screenshot needs the online model: add an OpenRouter key in Settings. Use read_screen for the text view of the screen meanwhile."
        val shot = svc.screenshotBitmap()
            ?: return "A screenshot is not available (needs Android 11+ and the ZENIQ accessibility service; turn the service off and on once after updating the app). Use read_screen instead."
        val realW = shot.width; val realH = shot.height
        val scale = 1024f / maxOf(realW, realH)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(shot, (realW * scale).toInt().coerceAtLeast(1), (realH * scale).toInt().coerceAtLeast(1), true) else shot
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 72, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val prompt = "You are the eyes of a phone-control agent. The image is a screenshot of an Android phone whose real screen is ${realW}x${realH} pixels " +
            "(the image is scaled to ${small.width}x${small.height}). Task of the agent: ${task.ifBlank { "understand the current screen" }}.\n" +
            "Answer in under 180 words: 1) which app/screen this is, 2) the important visible elements with the approximate CENTER coordinates in REAL screen pixels " +
            "as [x,y], 3) dialogs, errors, keyboard or selected/disabled states, 4) the single best next action for the task. Do not invent elements that are not visible."
        val msgs = listOf(OpenRouterClient.Message("user", prompt, listOf(b64)))
        val models = (if (OpenRouterClient.isOpenRouter) OpenRouterClient.visionModels + OpenRouterClient.AUTO_FREE else listOf(cfg.openRouterModel)).filter { it.isNotBlank() }.distinct().take(4)
        var lastError = "no vision model answered"
        for (m in models) {
            try {
                val sb = StringBuilder()
                OpenRouterClient.streamChat(apiKey = key, model = m, messages = msgs, maxTokens = 500, temperature = 0.2f) { sb.append(it); true }
                if (sb.isNotBlank()) return sb.toString().trim()
            } catch (e: ORException) { lastError = e.message ?: lastError }
            catch (e: Exception) { lastError = e.message ?: lastError }
        }
        return "Could not read the screenshot ($lastError). Use read_screen instead."
    }
}

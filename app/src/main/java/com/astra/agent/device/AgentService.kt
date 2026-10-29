package com.astra.agent.device

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.astra.agent.MainActivity
import com.astra.agent.R

/** Keeps the agent alive while it drives other apps and during background downloads. */
class AgentService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val mode = i?.getStringExtra("mode") ?: "agent"
        val text = i?.getStringExtra("text") ?: "Working…"
        val n = if (mode == "download") buildDownload(this, text) else buildAgent(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astra:agent")
                .apply { acquire(60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_AGENT = "astra_agent"
        private const val CHANNEL_DOWNLOAD = "astra_download"
        private const val NOTIF_ID = 1
        private const val NOTIF_DL_ID = 2

        private fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_AGENT) == null)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_AGENT, "ZENIQ agent", NotificationManager.IMPORTANCE_LOW)
                )
            if (nm.getNotificationChannel(CHANNEL_DOWNLOAD) == null)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_DOWNLOAD, "Model downloads", NotificationManager.IMPORTANCE_LOW)
                )
        }

        private fun pi(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        private fun buildAgent(c: Context, text: String): Notification =
            Notification.Builder(c, CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_stat_zeniq)
                .setContentTitle("ZENIQ")
                .setContentText(text)
                .setContentIntent(pi(c))
                .setOngoing(true)
                .build()

        private fun buildDownload(c: Context, text: String): Notification =
            Notification.Builder(c, CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_stat_zeniq)
                .setContentTitle("ZENIQ – Downloading model")
                .setContentText(text)
                .setContentIntent(pi(c))
                .setOngoing(true)
                .build()

        fun start(c: Context, text: String) = try {
            c.startForegroundService(
                Intent(c, AgentService::class.java)
                    .putExtra("mode", "agent")
                    .putExtra("text", text)
            )
        } catch (e: Exception) {}

        fun update(c: Context, text: String) {
            try {
                ensureChannels(c)
                c.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildAgent(c, text))
            } catch (e: Exception) {}
        }

        fun startDownload(c: Context, text: String) = try {
            c.startForegroundService(
                Intent(c, AgentService::class.java)
                    .putExtra("mode", "download")
                    .putExtra("text", text)
            )
        } catch (e: Exception) {}

        fun stopDownload(c: Context) {
            try {
                ensureChannels(c)
                c.getSystemService(NotificationManager::class.java).cancel(NOTIF_DL_ID)
            } catch (e: Exception) {}
        }

        fun stop(c: Context) { c.stopService(Intent(c, AgentService::class.java)) }
    }
}

package com.astra.agent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.astra.agent.personal.Kv
import com.astra.agent.personal.ScheduleIntent
import com.astra.agent.personal.ScheduledTask
import com.astra.agent.personal.TaskStore
import java.time.ZoneId

/** Daily scheduled tasks. At the set time ZENIQ shows a notification; tapping it opens the app and runs the task. */
object Alarms {
    const val EXTRA_PROMPT = "zeniq_prompt"
    private const val CHANNEL = "zeniq_tasks"

    fun store(ctx: Context): TaskStore {
        val sp = ctx.getSharedPreferences("zeniq_personal", Context.MODE_PRIVATE)
        return TaskStore(object : Kv {
            override fun get(key: String): String? = sp.getString(key, null)
            override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
        })
    }

    private fun pending(ctx: Context, t: ScheduledTask): PendingIntent {
        val i = Intent(ctx, TaskReceiver::class.java).putExtra("id", t.id).putExtra("hour", t.hour).putExtra("minute", t.minute).putExtra("prompt", t.prompt)
        return PendingIntent.getBroadcast(ctx, t.id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun arm(ctx: Context, t: ScheduledTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = ScheduleIntent.nextTime(t.hour, t.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, t)) } catch (_: Exception) {}
    }

    fun cancel(ctx: Context, t: ScheduledTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pending(ctx, t)) } catch (_: Exception) {}
    }

    fun armAll(ctx: Context) { for (t in store(ctx).list()) arm(ctx, t) }

    fun notifyTask(ctx: Context, t: ScheduledTask) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_PROMPT, t.prompt)
        val pi = PendingIntent.getActivity(ctx, t.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeniq).setContentTitle("ZENIQ \u2022 ${t.timeLabel()}").setContentText(t.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.prompt + "\n\nTap to run it now."))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(t.id.hashCode(), n) } catch (_: SecurityException) {}
    }
}

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val t = ScheduledTask(intent.getStringExtra("id") ?: return, intent.getIntExtra("hour", 8), intent.getIntExtra("minute", 0), intent.getStringExtra("prompt") ?: return)
        if (Alarms.store(ctx).list().none { it.id == t.id }) return       // deleted in the meantime
        Alarms.notifyTask(ctx, t)
        Alarms.arm(ctx, t)                                                // again tomorrow
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) Alarms.armAll(ctx) }
}

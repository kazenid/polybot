package app.polybot.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.polybot.MainActivity
import app.polybot.R
import app.polybot.domain.engine.EngineStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Notifikasi status (IMPORTANCE_LOW, tanpa suara) dan peringatan terpisah untuk peristiwa penting. */
@Singleton
class StatusNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var alertCounter = 0

    init { createChannels() }

    fun build(status: EngineStatus): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle(NotificationText.title(status))
            .setContentText(NotificationText.text(status, clockFormat.format(Date())))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", serviceAction(BotForegroundService.ACTION_STOP, 1))
            .addAction(0, "Kill Switch", serviceAction(BotForegroundService.ACTION_KILL, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission") // dijaga canNotify(); SecurityException tetap ditangkap di notifyOrIgnore
    fun update(status: EngineStatus) {
        if (canNotify()) notifyOrIgnore(STATUS_ID, build(status))
    }

    @SuppressLint("MissingPermission")
    private fun notifyOrIgnore(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Izin dicabut di tengah jalan: bot tetap berjalan, hanya notifikasi yang tidak tampil.
        }
    }

    fun alert(alert: Alert) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (alert.critical) CHANNEL_CRITICAL else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(if (alert.critical) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .build()
        notifyOrIgnore(ALERT_ID_BASE + (alertCounter++ % MAX_ALERTS), notification)
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context, requestCode, Intent(context, BotForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android 13+ butuh izin runtime; tanpa izin, service tetap jalan tetapi notifikasi tidak muncul. */
    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    private fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Status bot", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status bot yang sedang berjalan (tanpa suara)"
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Peringatan bot", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Bot dijeda atau berhenti"
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Peringatan penting", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Galat fatal, batas rugi tercapai, bot butuh tindakan Anda"
            },
        )
    }

    companion object {
        const val STATUS_ID = 1001
        const val ALERT_ID_BASE = 2000
        private const val MAX_ALERTS = 50
        const val CHANNEL_STATUS = "bot_status"
        const val CHANNEL_ALERTS = "bot_alerts"
        const val CHANNEL_CRITICAL = "bot_critical"
    }
}

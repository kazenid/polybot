package app.polybot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.polybot.core.log.BotLog
import app.polybot.data.bot.BotRunner
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Menjaga proses bot tetap hidup di latar belakang. Loop bot sendiri ada di [BotRunner]; service ini
 * memegang notifikasi foreground, wake lock saat bot perlu polling, dan menutup diri bila sesi berakhir.
 */
@AndroidEntryPoint
class BotForegroundService : Service() {
    @Inject lateinit var runner: BotRunner
    @Inject lateinit var notifier: StatusNotifier
    @Inject lateinit var log: BotLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var baseSession = 0L
    private var previous: EngineState = EngineState.Idle
    private var lastNotifyMs = 0L
    private var lastSignature = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> forward { runner.stop() }
            ACTION_KILL -> forward { runner.killSwitch() }
            ACTION_START -> begin(resume = false)
            // Intent null = sistem menghidupkan ulang service sticky; ACTION_RESUME = dari BootReceiver.
            else -> begin(resume = true)
        }
        return START_STICKY
    }

    /** Aksi dari notifikasi. Bila service tidak sedang mengamati sesi (notifikasi basi), jalankan lalu tutup. */
    private fun forward(action: () -> Unit) {
        action()
        if (observing?.isActive != true) stopSelf()
    }

    private fun begin(resume: Boolean) {
        if (!enterForeground()) {
            stopSelf()
            return
        }
        if (observing?.isActive == true) {
            if (!resume) runner.start()
            return
        }
        baseSession = runner.status.value.sessionId
        observing = scope.launch {
            if (resume) {
                if (!runner.resumeIfNeeded()) {
                    finish()
                    return@launch
                }
            } else {
                runner.start()
            }
            launch { ticker() }
            launch { startTimeout() }
            runner.status.collect { onStatus(it) }
        }
    }

    private fun enterForeground(): Boolean = try {
        // specialUse baru dikenal di Android 14; versi lama memakai tipe yang tertulis di manifest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, StatusNotifier.STATUS_ID, notifier.build(runner.status.value), type)
        true
    } catch (e: Exception) {
        // Termasuk ForegroundServiceStartNotAllowedException: restart sticky dari latar belakang tidak dijamin diizinkan.
        log.error("service", "Gagal masuk mode foreground: ${e.javaClass.simpleName}: ${e.message}")
        Log.w("PolyBot", "startForeground gagal", e)
        false
    }

    private fun onStatus(st: EngineStatus) {
        val ended = st.sessionId > baseSession && !st.state.inSession
        syncWakeLock(st.state)
        if (st.sessionId > baseSession || st.state.inSession) {
            NotificationText.alertFor(previous, st.state)?.let(notifier::alert)
            previous = st.state
            val signature = "${st.state}|${st.openOrders}|${st.sessionPnl?.setScale(2, java.math.RoundingMode.HALF_UP)}"
            val now = System.currentTimeMillis()
            if (signature != lastSignature || now - lastNotifyMs >= NOTIFY_EVERY_MS) {
                lastSignature = signature
                lastNotifyMs = now
                notifier.update(st)
            }
        }
        if (ended) finish()
    }

    /** Menyegarkan jam di notifikasi dan memperpanjang wake lock selama bot masih butuh polling. */
    private suspend fun ticker() {
        while (scope.isActive) {
            delay(NOTIFY_EVERY_MS)
            val st = runner.status.value
            if (st.state.inSession) {
                syncWakeLock(st.state)
                notifier.update(st)
            }
        }
    }

    /** Bila Start tidak menghasilkan sesi baru (mis. runner menolak), jangan biarkan service menggantung. */
    private suspend fun startTimeout() {
        delay(START_TIMEOUT_MS)
        val st = runner.status.value
        if (st.sessionId <= baseSession && !st.state.inSession) finish()
    }

    private fun needsCpu(state: EngineState) =
        state is EngineState.Running || state is EngineState.Starting || (state is EngineState.Paused && state.auto)

    private fun syncWakeLock(state: EngineState) {
        if (needsCpu(state)) {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "polybot:bot")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS) // batas waktu: tidak pernah menahan CPU selamanya bila service macet
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.polybot.action.START"
        const val ACTION_RESUME = "app.polybot.action.RESUME"
        const val ACTION_STOP = "app.polybot.action.STOP"
        const val ACTION_KILL = "app.polybot.action.KILL"
        private const val NOTIFY_EVERY_MS = 30_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

        fun start(context: Context) = launch(context, ACTION_START)

        fun resume(context: Context) = launch(context, ACTION_RESUME)

        private fun launch(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, BotForegroundService::class.java).setAction(action))
        }
    }
}

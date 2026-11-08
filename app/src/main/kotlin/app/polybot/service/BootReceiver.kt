package app.polybot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.polybot.data.prefs.BotConfigStore
import app.polybot.data.prefs.SessionStore
import app.polybot.domain.engine.ResumeDecision
import app.polybot.domain.engine.SessionPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Setelah HP menyala ulang atau aplikasi diperbarui, lanjutkan sesi hanya bila [SessionPolicy] mengizinkan.
 * Keputusan diambil di sini (bukan di service) supaya tidak ada notifikasi foreground yang berkedip tanpa sesi.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var configStore: BotConfigStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val decision = SessionPolicy.decide(sessions.load(), configStore.current(), System.currentTimeMillis())
                if (decision is ResumeDecision.Resume) BotForegroundService.resume(context.applicationContext)
            } catch (e: Exception) {
                // Gagal memulihkan tidak boleh membuat receiver crash; pengguna bisa Start manual.
            } finally {
                pending.finish()
            }
        }
    }
}

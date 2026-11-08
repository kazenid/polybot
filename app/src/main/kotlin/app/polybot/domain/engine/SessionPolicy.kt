package app.polybot.domain.engine

import app.polybot.domain.config.BotConfig

/** Catatan sesi yang bertahan setelah proses mati. [wantRunning] hanya true saat bot Running atau Paused otomatis. */
data class SavedSession(val wantRunning: Boolean, val lastActiveMs: Long)

sealed interface ResumeDecision {
    data object Resume : ResumeDecision
    data class Skip(val reason: String) : ResumeDecision
}

/**
 * Kapan sesi boleh dilanjutkan otomatis setelah proses dimatikan sistem atau HP restart. Konservatif:
 * hanya bila pengguna tidak menghentikannya, belum lama, dan (untuk live) sudah mengizinkan pemulihan.
 */
object SessionPolicy {
    const val MAX_GAP_MS = 6 * 3_600_000L

    fun decide(saved: SavedSession, config: BotConfig, nowMs: Long): ResumeDecision = when {
        !saved.wantRunning -> ResumeDecision.Skip("tidak ada sesi aktif")
        nowMs - saved.lastActiveMs > MAX_GAP_MS -> ResumeDecision.Skip("sesi terakhir terlalu lama (> 6 jam)")
        nowMs < saved.lastActiveMs -> ResumeDecision.Skip("jam perangkat mundur")
        config.validate().isNotEmpty() -> ResumeDecision.Skip("konfigurasi tidak valid")
        !config.dryRun && !config.autoResumeLive -> ResumeDecision.Skip("pemulihan otomatis live tidak diizinkan")
        else -> ResumeDecision.Resume
    }
}

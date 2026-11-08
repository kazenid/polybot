package app.polybot.service

import app.polybot.domain.engine.BotEngine
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import java.math.BigDecimal
import java.math.RoundingMode

data class Alert(val title: String, val text: String, val critical: Boolean)

/** Isi notifikasi dan kebijakan peringatan. Murni Kotlin (tanpa Android) supaya mudah diuji. */
object NotificationText {
    fun stateShort(state: EngineState): String = when (state) {
        EngineState.Idle -> "Siaga"
        EngineState.Starting -> "Memulai"
        EngineState.Running -> "Berjalan"
        is EngineState.Paused -> "Dijeda"
        EngineState.Stopping -> "Menghentikan"
        is EngineState.Stopped -> "Berhenti"
        is EngineState.Fatal -> "GALAT"
    }

    fun title(status: EngineStatus): String =
        "PolyBot · ${if (status.dryRun) "DRY RUN" else "LIVE"} · ${stateShort(status.state)}"

    /** [clockLabel] = jam terakhir diperbarui (mis. "14:03:22"), diberikan pemanggil agar fungsi ini deterministik. */
    fun text(status: EngineStatus, clockLabel: String): String {
        val parts = mutableListOf<String>()
        status.symbol?.let { parts += it }
        parts += "${status.openOrders} order"
        status.sessionPnl?.let { parts += "PnL ${signed(it)}" }
        val reason = when (val s = status.state) {
            is EngineState.Paused -> s.reason
            is EngineState.Stopped -> s.reason
            is EngineState.Fatal -> s.reason
            else -> null
        }
        reason?.let { parts += it }
        parts += clockLabel
        return parts.joinToString(" · ")
    }

    /** Peringatan terpisah (bisa berbunyi) hanya pada peralihan yang butuh perhatian pengguna. */
    fun alertFor(previous: EngineState, next: EngineState): Alert? = when {
        next is EngineState.Fatal -> Alert("PolyBot: galat fatal", next.reason, critical = true)
        next is EngineState.Paused && previous !is EngineState.Paused ->
            if (next.reason == BotEngine.NETWORK_REASON) null
            else Alert("PolyBot dijeda", next.reason + if (next.auto) "" else ". Buka aplikasi untuk melanjutkan.", critical = !next.auto)
        next is EngineState.Stopped && previous.inSession && next.reason !in USER_STOPS ->
            Alert("PolyBot berhenti", next.reason, critical = next.reason.contains("Rugi harian"))
        else -> null
    }

    private val USER_STOPS = setOf("Dihentikan pengguna", "Kill switch")

    private fun signed(v: BigDecimal): String {
        val scaled = v.setScale(2, RoundingMode.HALF_UP)
        return (if (scaled.signum() > 0) "+" else "") + scaled.toPlainString()
    }
}

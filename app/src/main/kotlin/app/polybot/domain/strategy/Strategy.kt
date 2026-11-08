package app.polybot.domain.strategy

import app.polybot.domain.config.BotConfig
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.model.Side
import java.math.BigDecimal

sealed interface Intent {
    data class Place(
        val side: Side,
        val price: BigDecimal,
        val qty: BigDecimal,
        val postOnly: Boolean = true,
        val tif: String = "GTC",
        val expireAtMs: Long? = null,
        /** Alasan keputusan, untuk log. */
        val note: String = "",
    ) : Intent {
        val notional: BigDecimal get() = price.multiply(qty)
    }

    data class Cancel(val clientOrderId: String, val reason: String) : Intent

    /** Strategi meminta bot berhenti (mis. harga keluar rentang grid, DCA selesai). */
    data class Halt(val reason: String) : Intent
}

/** Strategi murni: snapshot masuk, niat keluar. Tidak pernah memanggil jaringan. */
interface Strategy {
    val name: String
    fun decide(snapshot: Snapshot, config: BotConfig): List<Intent>
}

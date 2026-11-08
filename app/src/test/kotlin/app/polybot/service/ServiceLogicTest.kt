package app.polybot.service

import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.GridConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.engine.BotEngine
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import app.polybot.domain.engine.ResumeDecision
import app.polybot.domain.engine.SavedSession
import app.polybot.domain.engine.SessionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ServiceLogicTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private fun skipReason(d: ResumeDecision) = (assertInstanceOf(ResumeDecision.Skip::class.java, d)).reason

    // ---- kebijakan pemulihan sesi ----

    @Test
    fun `a recent dry run session is resumed`() {
        assertEquals(ResumeDecision.Resume, SessionPolicy.decide(SavedSession(true, now - hour), BotConfig(dryRun = true), now))
    }

    @Test
    fun `a session the user stopped is never resumed`() {
        assertTrue(skipReason(SessionPolicy.decide(SavedSession(false, now), BotConfig(), now)).contains("tidak ada sesi"))
    }

    @Test
    fun `live sessions need explicit permission to resume`() {
        val saved = SavedSession(true, now - 60_000)
        assertTrue(skipReason(SessionPolicy.decide(saved, BotConfig(dryRun = false), now)).contains("live"))
        assertEquals(ResumeDecision.Resume, SessionPolicy.decide(saved, BotConfig(dryRun = false, autoResumeLive = true), now))
    }

    @Test
    fun `stale or time-travelling sessions are not resumed`() {
        val cfg = BotConfig(dryRun = true)
        assertTrue(skipReason(SessionPolicy.decide(SavedSession(true, now - 7 * hour), cfg, now)).contains("6 jam"))
        assertEquals(ResumeDecision.Resume, SessionPolicy.decide(SavedSession(true, now - 6 * hour), cfg, now))
        assertTrue(skipReason(SessionPolicy.decide(SavedSession(true, now + hour), cfg, now)).contains("mundur"))
    }

    @Test
    fun `an invalid configuration blocks resuming`() {
        val broken = BotConfig(strategy = StrategyType.GRID, grid = GridConfig(BigDecimal.ZERO, BigDecimal.ZERO))
        assertTrue(skipReason(SessionPolicy.decide(SavedSession(true, now), broken, now)).contains("konfigurasi"))
    }

    // ---- teks notifikasi ----

    private fun status(state: EngineState, dry: Boolean = true, open: Int = 2, start: String? = "10000", equity: String? = "10012.345") =
        EngineStatus(
            state = state, dryRun = dry, symbol = "BTC-USDT", openOrders = open,
            startEquity = start?.let(::BigDecimal), equity = equity?.let(::BigDecimal),
        )

    @Test
    fun `title shows the mode and the state`() {
        assertEquals("PolyBot · DRY RUN · Berjalan", NotificationText.title(status(EngineState.Running)))
        assertEquals("PolyBot · LIVE · Dijeda", NotificationText.title(status(EngineState.Paused("x", true), dry = false)))
        assertEquals("PolyBot · LIVE · GALAT", NotificationText.title(status(EngineState.Fatal("x"), dry = false)))
    }

    @Test
    fun `text shows symbol, orders, signed pnl, reason and clock`() {
        assertEquals("BTC-USDT · 2 order · PnL +12.35 · 14:03:22", NotificationText.text(status(EngineState.Running), "14:03:22"))
        assertEquals(
            "BTC-USDT · 0 order · PnL -5.00 · Tidak ada jaringan · 09:00:00",
            NotificationText.text(status(EngineState.Paused("Tidak ada jaringan", true), open = 0, equity = "9995"), "09:00:00"),
        )
        assertEquals("BTC-USDT · 1 order · 01:02:03", NotificationText.text(status(EngineState.Running, open = 1, start = null, equity = null), "01:02:03"))
    }

    // ---- kebijakan peringatan ----

    @Test
    fun `fatal errors always alert as critical`() {
        val alert = NotificationText.alertFor(EngineState.Running, EngineState.Fatal("Autentikasi ditolak"))!!
        assertTrue(alert.critical)
        assertTrue(alert.text.contains("Autentikasi"))
    }

    @Test
    fun `pauses alert once, and network pauses stay quiet`() {
        assertNotNull(NotificationText.alertFor(EngineState.Running, EngineState.Paused("Order book crossed", auto = true)))
        assertNull(NotificationText.alertFor(EngineState.Paused("a", true), EngineState.Paused("b", true)), "tidak mengulang peringatan")
        assertNull(NotificationText.alertFor(EngineState.Running, EngineState.Paused(BotEngine.NETWORK_REASON, auto = true)))
        val manual = NotificationText.alertFor(EngineState.Running, EngineState.Paused("5 error beruntun", auto = false))!!
        assertTrue(manual.critical && manual.text.contains("Buka aplikasi"))
    }

    @Test
    fun `user initiated stops are silent but automatic stops alert`() {
        assertNull(NotificationText.alertFor(EngineState.Running, EngineState.Stopped("Dihentikan pengguna")))
        assertNull(NotificationText.alertFor(EngineState.Stopping, EngineState.Stopped("Kill switch")))
        val loss = NotificationText.alertFor(EngineState.Running, EngineState.Stopped("Rugi harian 25 mencapai batas 20"))!!
        assertTrue(loss.critical)
        assertFalse(NotificationText.alertFor(EngineState.Running, EngineState.Stopped("Strategi: DCA selesai"))!!.critical)
        assertNull(NotificationText.alertFor(EngineState.Idle, EngineState.Stopped("Gagal start")), "bukan peralihan dari sesi aktif")
    }
}

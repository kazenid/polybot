package app.polybot.domain.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PnlSummaryTest {
    private fun s(volume: String, realized: String, fees: String, trades: Int = 4) =
        PnlSummary(trades, BigDecimal(volume), BigDecimal(realized), BigDecimal(fees))

    @Test
    fun `fee is 10 bps of volume and eats most of a thin gross profit`() {
        // Bentuk sesi 1 Nov: volume ~160, laba kotor 0.058, fee 0.160
        val summary = s("160", "0.058", "0.160")
        assertEquals(0, BigDecimal("10.0").compareTo(summary.feeBps))
        assertEquals(0, BigDecimal("276").compareTo(summary.feeShareOfGross))
        assertFalse(summary.coversFees)
        assertTrue(summary.net.signum() < 0)
    }

    @Test
    fun `gross above fees covers them`() {
        val summary = s("1000", "2.0", "1.0")
        assertTrue(summary.coversFees)
        assertEquals(0, BigDecimal("50").compareTo(summary.feeShareOfGross))
    }

    @Test
    fun `no gross profit gives no share and no volume gives no bps`() {
        val none = s("0", "0", "0", trades = 0)
        assertNull(none.feeBps)
        assertNull(none.feeShareOfGross)
        assertNull(s("100", "-0.5", "0.1").feeShareOfGross)
    }

    @Test
    fun `rebates reduce fees`() {
        val summary = s("100", "0.3", "-0.05")
        assertTrue(summary.coversFees)
        assertEquals(0, BigDecimal("0.35").compareTo(summary.net))
    }
}

package app.polybot.ui.guide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryGuideTest {
    @Test
    fun `xiaomi family shares one guide with autostart`() {
        for (m in listOf("Xiaomi", "Redmi", "POCO", "xiaomi")) {
            val g = BatteryGuide.forManufacturer(m)
            assertTrue(g.brand.contains("Xiaomi"), m)
            assertTrue(g.steps.any { it.contains("Mulai otomatis") }, m)
        }
    }

    @Test
    fun `other brands get their own steps and unknown ones a generic guide`() {
        assertTrue(BatteryGuide.forManufacturer("samsung").steps.any { it.contains("Aplikasi yang tidak pernah tidur") })
        assertTrue(BatteryGuide.forManufacturer("HUAWEI").steps.any { it.contains("Kelola manual") })
        assertTrue(BatteryGuide.forManufacturer("OnePlus").brand.contains("OPPO"))
        assertEquals("Android umum", BatteryGuide.forManufacturer("Fairphone").brand)
        assertEquals("Android umum", BatteryGuide.forManufacturer("").brand)
    }

    @Test
    fun `every guide ends with the shared advice about locking and charging`() {
        for (m in listOf("xiaomi", "oppo", "vivo", "huawei", "samsung", "other")) {
            val steps = BatteryGuide.forManufacturer(m).steps
            assertTrue(steps.any { it.contains("Kunci aplikasi") }, m)
            assertTrue(steps.any { it.contains("charger") }, m)
        }
    }
}

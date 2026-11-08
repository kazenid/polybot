package app.polybot.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientOrderIdsTest {
    private val allowed = Regex("^[A-Za-z0-9._:/-]{1,36}$")

    @Test
    fun `ids follow the server format and stay within 36 characters`() = runBlocking {
        val ids = ClientOrderIds(InMemoryOrderStore("k3z9"))
        val generated = (1..2_000).map { ids.next() }

        assertTrue(generated.all { allowed.matches(it) })
        assertTrue(generated.all { it.length <= 36 })
        assertEquals("pbk3z9-1", generated.first())
        assertEquals(generated.size, generated.toSet().size)
    }

    @Test
    fun `sequence survives a restart because it lives in the store`() = runBlocking {
        val store = InMemoryOrderStore()
        val before = ClientOrderIds(store).next()
        val after = ClientOrderIds(store).next() // instance baru = proses baru, store sama

        assertTrue(before != after)
        assertEquals("pbab12-2", after)
    }

    @Test
    fun `ownership check only claims this bot's ids`() {
        assertTrue(ClientOrderIds.isOurs("pbab12-1"))
        assertTrue(ClientOrderIds.isOurs("pbzz99-zz"))
        assertFalse(ClientOrderIds.isOurs("manual-1"))
        assertFalse(ClientOrderIds.isOurs(""))
        assertFalse(ClientOrderIds.isOurs("pbnodash"))
    }
}

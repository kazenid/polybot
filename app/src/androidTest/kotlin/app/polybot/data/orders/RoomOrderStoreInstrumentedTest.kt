package app.polybot.data.orders

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.polybot.data.db.AppDatabase
import app.polybot.domain.engine.ClientOrderIds
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOrderStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "polybot-test.db"
    private lateinit var db: AppDatabase

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun order(id: String, status: LocalStatus) = LocalOrder(
        clientOrderId = id, orderId = null, symbolId = 1, side = Side.BUY, priceTicks = 77_000_000_000_000,
        qtyScaled = 10_000, filledScaled = 0, postOnly = true, tif = "GTC", status = status, reason = null,
        createdAtMs = 1, updatedAtMs = 1,
    )

    @Test
    fun saveGetAndActiveFilter() = runBlocking {
        val store = RoomOrderStore(db.orders(), db.counters())
        store.save(order("pbaa-1", LocalStatus.PENDING))
        store.save(order("pbaa-2", LocalStatus.OPEN))
        store.save(order("pbaa-3", LocalStatus.CANCELED))

        assertEquals(LocalStatus.OPEN, store.get("pbaa-2")!!.status)
        assertNull(store.get("nope"))
        assertEquals(setOf("pbaa-1", "pbaa-2"), store.active().map { it.clientOrderId }.toSet())

        store.save(order("pbaa-1", LocalStatus.FILLED)) // upsert menimpa
        assertEquals(setOf("pbaa-2"), store.active().map { it.clientOrderId }.toSet())
    }

    @Test
    fun sequenceIsUniqueUnderConcurrency() = runBlocking {
        val store = RoomOrderStore(db.orders(), db.counters())
        val values = (1..200).map { async(Dispatchers.IO) { store.nextSeq() } }.awaitAll()
        assertEquals(200, values.toSet().size)
        assertEquals((1L..200L).toSet(), values.toSet())
    }

    @Test
    fun sequenceAndRunShortSurviveReopen() = runBlocking {
        val first = RoomOrderStore(db.orders(), db.counters())
        val run = first.runShort()
        assertEquals(4, run.length)
        assertTrue(run.all { it.isDigit() || it in 'a'..'z' })
        repeat(5) { first.nextSeq() }
        val firstId = ClientOrderIds(first).next()
        db.close()

        db = open() // simulasi proses dimulai ulang
        val second = RoomOrderStore(db.orders(), db.counters())
        assertEquals(run, second.runShort())
        val nextId = ClientOrderIds(second).next()
        assertEquals("pb$run-7", nextId)
        assertTrue(firstId != nextId)
    }

    @Test
    fun ordersSurviveReopen() = runBlocking {
        RoomOrderStore(db.orders(), db.counters()).save(order("pbaa-1", LocalStatus.UNKNOWN))
        db.close()
        db = open()
        assertNotNull(RoomOrderStore(db.orders(), db.counters()).get("pbaa-1"))
    }
}

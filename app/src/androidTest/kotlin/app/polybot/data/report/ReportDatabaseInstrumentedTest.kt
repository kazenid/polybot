package app.polybot.data.report

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportDatabaseInstrumentedTest {
    private lateinit var db: ReportDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ReportDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun fill(match: Long, order: Long, ts: Long, symbol: String = "BTC-USDT") =
        FillEntity(match, order, symbol, "BUY", true, "77000", "0.0001", "0.01", "QUOTE", ts)

    @Test
    fun resyncingTheSameFillsNeverDuplicates() = runBlocking {
        val rows = listOf(fill(1, 10, 100), fill(2, 10, 200), fill(3, 11, 300))
        assertEquals(3, db.fills().insertAll(rows).count { it != -1L })
        assertEquals("baris yang sama harus diabaikan", 0, db.fills().insertAll(rows).count { it != -1L })
        assertEquals(3, db.fills().count())
    }

    @Test
    fun theSameMatchCanBelongToTwoOrders() = runBlocking {
        db.fills().insertAll(listOf(fill(1, 10, 100), fill(1, 11, 100)))
        assertEquals(2, db.fills().count())
    }

    @Test
    fun maxMatchIdIsPerSymbolAndObserveIsChronological() = runBlocking {
        db.fills().insertAll(listOf(fill(5, 1, 300), fill(9, 2, 100), fill(7, 3, 200, "ETH-USDT")))
        assertEquals(9L, db.fills().maxMatchId("BTC-USDT"))
        assertEquals(7L, db.fills().maxMatchId("ETH-USDT"))
        assertEquals(null, db.fills().maxMatchId("SOL-USDT"))
        assertEquals(listOf(100L, 200L, 300L), db.fills().observeAll().first().map { it.tsMs })
    }

    @Test
    fun snapshotsFilterByTimeAndPrune() = runBlocking {
        listOf(1_000L, 2_000L, 3_000L).forEach { db.snapshots().insert(EquitySnapshotEntity(tsMs = it, symbol = "BTC-USDT", equityQuote = "10000", dryRun = true)) }
        assertEquals(listOf(2_000L, 3_000L), db.snapshots().observeSince(2_000).first().map { it.tsMs })
        assertEquals(1, db.snapshots().prune(2_000))
        assertEquals(2, db.snapshots().count())
        db.snapshots().deleteAll()
        assertEquals(0, db.snapshots().count())
    }

    @Test
    fun logsComeBackNewestFirstAndPrune() = runBlocking {
        (1..5).forEach { db.logs().insert(LogEntity(tsMs = it * 1_000L, level = "INFO", category = "t", message = "m$it")) }
        assertEquals(listOf("m5", "m4", "m3"), db.logs().recent(3).map { it.message })
        assertEquals(2, db.logs().prune(3_000))
        assertEquals(3, db.logs().count())
        db.logs().deleteAll()
        assertTrue(db.logs().recent(10).isEmpty())
    }

    @Test
    fun oldFillsArePruned() = runBlocking {
        db.fills().insertAll(listOf(fill(1, 1, 100), fill(2, 2, 900)))
        assertEquals(1, db.fills().prune(500))
        assertEquals(listOf(2L), db.fills().observeAll().first().map { it.matchId })
    }
}

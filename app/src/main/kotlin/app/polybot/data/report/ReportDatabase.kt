package app.polybot.data.report

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Fill akun. Angka disimpan sebagai teks desimal agar presisi tidak hilang. Kunci: (matchId, orderId). */
@Entity(tableName = "fills", primaryKeys = ["matchId", "orderId"], indices = [Index("tsMs"), Index("symbol")])
data class FillEntity(
    val matchId: Long,
    val orderId: Long,
    val symbol: String,
    val side: String,
    val isMaker: Boolean,
    val price: String,
    val qty: String,
    /** Fee dalam satuan quote; negatif bila rebate. */
    val feeQuote: String,
    val feeAsset: String,
    val tsMs: Long,
)

@Entity(tableName = "equity_snapshots", indices = [Index("tsMs")])
data class EquitySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val symbol: String,
    val equityQuote: String,
    val dryRun: Boolean,
)

@Entity(tableName = "logs", indices = [Index("tsMs")])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val level: String,
    val category: String,
    val message: String,
)

@Dao
interface FillDao {
    /** IGNORE: fill yang sama dari sinkronisasi berulang tidak pernah ganda. Mengembalikan jumlah baris baru. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(fills: List<FillEntity>): List<Long>

    @Query("SELECT * FROM fills ORDER BY tsMs, matchId")
    fun observeAll(): Flow<List<FillEntity>>

    @Query("SELECT MAX(matchId) FROM fills WHERE symbol = :symbol")
    suspend fun maxMatchId(symbol: String): Long?

    @Query("SELECT COUNT(*) FROM fills")
    suspend fun count(): Int

    @Query("DELETE FROM fills WHERE tsMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int

    @Query("DELETE FROM fills")
    suspend fun deleteAll()
}

@Dao
interface SnapshotDao {
    @Insert
    suspend fun insert(snapshot: EquitySnapshotEntity)

    @Query("SELECT * FROM equity_snapshots WHERE tsMs >= :sinceMs ORDER BY tsMs")
    fun observeSince(sinceMs: Long): Flow<List<EquitySnapshotEntity>>

    @Query("SELECT COUNT(*) FROM equity_snapshots")
    suspend fun count(): Int

    @Query("DELETE FROM equity_snapshots WHERE tsMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int

    @Query("DELETE FROM equity_snapshots")
    suspend fun deleteAll()
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntity)

    /** Terbaru dulu. */
    @Query("SELECT * FROM logs ORDER BY tsMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LogEntity>

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int

    @Query("DELETE FROM logs WHERE tsMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int

    @Query("DELETE FROM logs")
    suspend fun deleteAll()
}

/** Terpisah dari database order supaya tidak perlu migrasi pada data order yang sudah ada. */
@Database(entities = [FillEntity::class, EquitySnapshotEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class ReportDatabase : RoomDatabase() {
    abstract fun fills(): FillDao
    abstract fun snapshots(): SnapshotDao
    abstract fun logs(): LogDao
}

package app.polybot.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

@Entity(tableName = "orders", indices = [Index("status"), Index("createdAtMs")])
data class OrderEntity(
    @PrimaryKey val clientOrderId: String,
    val orderId: Long?,
    val symbolId: Long,
    val side: String,
    val priceTicks: Long,
    val qtyScaled: Long,
    val filledScaled: Long,
    val postOnly: Boolean,
    val tif: String,
    val status: String,
    val reason: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val adopted: Boolean,
)

@Entity(tableName = "counters")
data class CounterEntity(@PrimaryKey val name: String, val value: Long)

@Dao
interface OrderDao {
    @Upsert
    suspend fun upsert(order: OrderEntity)

    @Query("SELECT * FROM orders WHERE clientOrderId = :clientOrderId")
    suspend fun get(clientOrderId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE status NOT IN ('FILLED', 'CANCELED', 'REJECTED') ORDER BY createdAtMs")
    suspend fun active(): List<OrderEntity>

    @Query("SELECT * FROM orders ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OrderEntity>

    @Query("DELETE FROM orders WHERE status IN ('FILLED', 'CANCELED', 'REJECTED') AND updatedAtMs < :olderThanMs")
    suspend fun pruneTerminal(olderThanMs: Long): Int
}

@Dao
abstract class CounterDao {
    @Query("SELECT value FROM counters WHERE name = :name")
    abstract suspend fun get(name: String): Long?

    @Upsert
    abstract suspend fun put(counter: CounterEntity)

    /** Atomik: dua pemanggil tidak pernah mendapat nilai yang sama. */
    @Transaction
    open suspend fun increment(name: String): Long {
        val next = (get(name) ?: 0L) + 1
        put(CounterEntity(name, next))
        return next
    }

    @Transaction
    open suspend fun getOrCreate(name: String, initial: Long): Long =
        get(name) ?: initial.also { put(CounterEntity(name, it)) }
}

@Database(entities = [OrderEntity::class, CounterEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orders(): OrderDao
    abstract fun counters(): CounterDao
}

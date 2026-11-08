package app.polybot.domain.engine

import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.Market
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.RemoteOrder
import java.math.BigDecimal

/**
 * Batas antara engine dan API. Semua fungsi melempar [app.polybot.core.result.ApiError] saat gagal.
 * Mutasi (`create`, `cancel`) tidak boleh diulang otomatis oleh implementasi.
 */
interface ExchangeGateway {
    /** Mengembalikan `orderId` dari server. Bisa melempar `DuplicateClientOrderId`. */
    suspend fun create(request: OrderRequest): Long

    suspend fun cancel(clientOrderId: String, symbolId: Long)

    /** Membatalkan SEMUA order di simbol itu, termasuk yang bukan milik bot. Hanya untuk kill switch bila diizinkan. */
    suspend fun cancelAll(symbolIds: List<Long>)

    /** Null berarti server memastikan order itu tidak ada. */
    suspend fun get(clientOrderId: String): RemoteOrder?

    suspend fun listOpen(): List<RemoteOrder>
}

/**
 * Biaya pembelian terbaru yang belum terjual (lot teratas) untuk aset dasar di [market], dari riwayat fill.
 * Null bila tidak diketahui (tanpa posisi, dry run, atau gagal).
 */
fun interface CostBasisSource {
    suspend fun lotCost(market: Market): BigDecimal?
}

interface OrderStore {
    /** Counter monoton yang bertahan setelah restart proses. */
    suspend fun nextSeq(): Long

    /** Penanda instalasi 4 karakter base36, tetap selama data aplikasi ada. */
    suspend fun runShort(): String

    suspend fun save(order: LocalOrder)
    suspend fun get(clientOrderId: String): LocalOrder?

    /** Semua order yang belum terminal. */
    suspend fun active(): List<LocalOrder>

    suspend fun recent(limit: Int): List<LocalOrder>
}

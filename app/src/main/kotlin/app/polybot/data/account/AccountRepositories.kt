package app.polybot.data.account

import app.polybot.core.result.ApiError
import app.polybot.core.result.userMessage
import app.polybot.data.api.ApiGateway
import app.polybot.data.api.dto.MeResponse
import app.polybot.data.api.dto.OrderDto
import app.polybot.data.api.services.AuthApi
import app.polybot.data.api.services.LedgerApi
import app.polybot.data.api.services.OrdersApi
import app.polybot.data.catalog.CatalogRepository
import app.polybot.domain.model.Balance
import app.polybot.domain.model.Catalog
import app.polybot.domain.model.OpenOrder
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class StepResult(val name: String, val ok: Boolean, val detail: String)

data class ConnectionReport(
    val steps: List<StepResult>,
    val accountId: String?,
    val apiKeyId: String?,
    val username: String?,
    val marketCount: Int,
    val enabledMarketCount: Int,
    val balanceCount: Int?,
    val openOrderCount: Int?,
    val latencyMs: Long,
    val clockSkewMs: Long?,
) {
    val allOk: Boolean get() = steps.all { it.ok }
}

@Singleton
class ConnectionTester @Inject constructor(
    private val gateway: ApiGateway,
    private val auth: AuthApi,
    private val ledger: LedgerApi,
    private val orders: OrdersApi,
    private val catalog: CatalogRepository,
) {
    /**
     * Katalog (publik) -> Me -> GetBalances -> GetOpenOrders. Tiap langkah dicatat sendiri-sendiri dan semuanya
     * dijalankan walau satu gagal, supaya terlihat izin mana yang kurang. Hanya kegagalan katalog yang dilempar.
     */
    suspend fun run(): ConnectionReport {
        val started = System.nanoTime()
        val steps = mutableListOf<StepResult>()

        val loaded = catalog.hydrate()
        val enabled = loaded.markets.count { it.status.acceptsNewQuotes }
        steps += StepResult("Katalog pasar (publik)", true, "${loaded.markets.size} pair, $enabled aktif")

        var me: MeResponse? = null
        step(steps, "Autentikasi (Me)", "Policy API key tidak mengizinkan membaca profil.") {
            me = auth.me()
            "Account ID ${me!!.accountId}"
        }
        var balanceCount: Int? = null
        step(
            steps, "Baca saldo (GetBalances)",
            "Policy API key belum mengizinkan membaca saldo (ledger read). Periksa Permissions key di aplikasi Polyester.",
        ) {
            balanceCount = ledger.balances().balances.size
            "$balanceCount aset bersaldo"
        }
        var openOrderCount: Int? = null
        step(
            steps, "Baca order terbuka (GetOpenOrders)",
            "Policy API key belum mengizinkan membaca order. Periksa Spot trading dan market di Permissions key.",
        ) {
            openOrderCount = orders.openOrders(limit = 1).orders.size
            "OK"
        }

        val latencyMs = (System.nanoTime() - started) / steps.size / 1_000_000
        return ConnectionReport(
            steps = steps,
            accountId = me?.accountId?.takeIf { it.isNotEmpty() },
            apiKeyId = me?.apiKeyId?.takeIf { it.isNotEmpty() },
            username = me?.username?.takeIf { it.isNotEmpty() },
            marketCount = loaded.markets.size,
            enabledMarketCount = enabled,
            balanceCount = balanceCount,
            openOrderCount = openOrderCount,
            latencyMs = latencyMs,
            clockSkewMs = gateway.clockMonitor.skewMs(),
        )
    }

    private suspend fun step(
        steps: MutableList<StepResult>,
        name: String,
        permissionHint: String,
        block: suspend () -> String,
    ) {
        steps += try {
            StepResult(name, true, block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StepResult(name, false, describe(e, permissionHint))
        }
    }

    private fun describe(e: Exception, permissionHint: String): String {
        if (e is ApiError.Auth && e.code.uppercase() in PERMISSION_CODES) {
            val server = e.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
            return "Izin ditolak (${e.code}). $permissionHint" + (server?.let { " Pesan server: $it" } ?: "")
        }
        return e.userMessage()
    }

    private companion object {
        val PERMISSION_CODES = setOf("PERMISSION_DENIED", "FORBIDDEN")
    }
}

@Singleton
class BalanceRepository @Inject constructor(
    private val ledger: LedgerApi,
    private val catalog: CatalogRepository,
) {
    suspend fun trading(): List<Balance> {
        val cat = catalog.ensure()
        return ledger.balances().balances.map { b ->
            Balance(
                asset = cat.assetByLedgerId(b.assetId)?.symbol ?: "#${b.assetId}",
                trading = Numeric.u128ToDecimal(b.trading.hi, b.trading.lo),
                funding = Numeric.u128ToDecimal(b.funding.hi, b.funding.lo),
                reserved = Numeric.u128ToDecimal(b.reserved.hi, b.reserved.lo),
                available = Numeric.u128ToDecimal(b.available.hi, b.available.lo),
            )
        }.sortedBy { it.asset }
    }
}

@Singleton
class OpenOrderRepository @Inject constructor(
    private val orders: OrdersApi,
    private val catalog: CatalogRepository,
) {
    suspend fun openOrders(): List<OpenOrder> {
        val cat = catalog.ensure()
        val result = mutableListOf<OpenOrder>()
        var token = ""
        do {
            val page = orders.openOrders(pageToken = token)
            page.orders.mapTo(result) { toOpenOrder(it, cat) }
            token = page.nextPageToken
        } while (token.isNotEmpty() && result.size < MAX_ORDERS)
        return result.sortedByDescending { it.createdAtMs }
    }

    /** Riwayat order (terisi, dibatalkan, ditolak), terbaru dulu, dibatasi [MAX_HISTORY]. */
    suspend fun history(): List<OpenOrder> {
        val cat = catalog.ensure()
        val result = mutableListOf<OpenOrder>()
        var token = ""
        do {
            val page = orders.orderHistory(pageToken = token)
            page.orders.mapTo(result) { toOpenOrder(it, cat) }
            token = page.nextPageToken
        } while (token.isNotEmpty() && page.orders.isNotEmpty() && result.size < MAX_HISTORY)
        return result.sortedByDescending { it.createdAtMs }.take(MAX_HISTORY)
    }

    private fun toOpenOrder(o: OrderDto, cat: Catalog): OpenOrder {
        val market = cat.market(o.symbolId)
        val scale = market?.baseScale ?: 0
        return OpenOrder(
            orderId = o.orderId,
            clientOrderId = o.clientOrderId,
            symbol = market?.symbol ?: "#${o.symbolId}",
            side = when (o.side) {
                "BUY" -> Side.BUY
                "SELL" -> Side.SELL
                else -> null
            },
            orderType = o.orderType.removePrefix("ORDER_TYPE_"),
            timeInForce = o.timeInForce.removePrefix("TIME_IN_FORCE_"),
            status = o.status.removePrefix("ORDER_STATUS_"),
            postOnly = o.postOnly,
            price = Numeric.ticksToPrice(o.priceTicks),
            quantity = Numeric.scaledToQty(o.origQtyScaled, scale),
            filled = Numeric.scaledToQty(o.cumQtyScaled, scale),
            remaining = Numeric.scaledToQty(o.leavesQtyScaled, scale),
            createdAtMs = o.createdTsNs / 1_000_000,
            terminalReason = o.terminalReason,
        )
    }

    private companion object {
        const val MAX_ORDERS = 500
        const val MAX_HISTORY = 200
    }
}

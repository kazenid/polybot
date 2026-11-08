package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Market
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.math.BigDecimal

sealed interface SubmitOutcome {
    /** Server menerima order (atau order itu ditemukan di server). Periksa `order.status`. */
    data class Accepted(val order: LocalOrder) : SubmitOutcome

    /** Terbukti tidak masuk, atau ditolak lokal/server. [order] null bila ditolak sebelum ID dialokasikan. */
    data class Rejected(val order: LocalOrder?, val reason: String, val error: ApiError? = null) : SubmitOutcome

    /** Hasil ambigu dan belum bisa dibuktikan. Jangan kirim ulang; [Reconciler] menyelesaikannya. */
    data class Unresolved(val order: LocalOrder) : SubmitOutcome
}

sealed interface CancelOutcome {
    data class Closed(val order: LocalOrder) : CancelOutcome
    data class Requested(val order: LocalOrder) : CancelOutcome
    data object NotTracked : CancelOutcome
    data class Failed(val error: ApiError) : CancelOutcome
}

/**
 * Membuat dan membatalkan order secara idempoten (plan bagian 9):
 * simpan PENDING -> kirim -> bila ambigu, baca balik dengan `client_order_id` sebelum apa pun.
 * Mengirim ulang dengan ID yang sama hanya setelah terbukti tidak ada; server menolak ID ganda,
 * jadi balapan dengan request yang masih melayang tetap aman.
 */
class OrderManager(
    private val exchange: ExchangeGateway,
    private val store: OrderStore,
    private val ids: ClientOrderIds,
    private val clock: Clock = Clock.System,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun submit(
        market: Market,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
        postOnly: Boolean = true,
        tif: String = "GTC",
        expireAtMs: Long? = null,
    ): SubmitOutcome {
        if (!market.status.acceptsNewQuotes) return SubmitOutcome.Rejected(null, "Pair ${market.symbol} berstatus ${market.status}")
        Numeric.validate(market, price, qty)?.let { return SubmitOutcome.Rejected(null, "Validasi lokal: $it") }
        val priceTicks: Long
        val qtyScaled: Long
        try {
            priceTicks = Numeric.priceToTicks(price)
            qtyScaled = Numeric.qtyToScaled(qty, market.baseScale)
        } catch (e: ArithmeticException) {
            return SubmitOutcome.Rejected(null, "Harga/kuantitas tidak bisa dinyatakan dalam satuan server")
        }

        val now = clock.nowMs()
        val request = OrderRequest(ids.next(), market.symbolId, side, priceTicks, qtyScaled, postOnly, tif, expireAtMs)
        val pending = LocalOrder(
            clientOrderId = request.clientOrderId, orderId = null, symbolId = market.symbolId, side = side,
            priceTicks = priceTicks, qtyScaled = qtyScaled, filledScaled = 0, postOnly = postOnly, tif = tif,
            status = LocalStatus.PENDING, reason = null, createdAtMs = now, updatedAtMs = now,
        )
        store.save(pending) // harus tersimpan sebelum byte pertama dikirim
        return transmit(pending, request)
    }

    private suspend fun transmit(initial: LocalOrder, request: OrderRequest): SubmitOutcome {
        val order = initial
        var resends = 0
        while (true) {
            try {
                val orderId = exchange.create(request)
                return SubmitOutcome.Accepted(save(order.copy(orderId = orderId, status = LocalStatus.OPEN, reason = null)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.DuplicateClientOrderId) {
                return adoptExisting(order, e.existingOrderId)
            } catch (e: ApiError) {
                when (e) {
                    is ApiError.Auth, is ApiError.Validation, is ApiError.NotAvailable, is ApiError.NotFound ->
                        return reject(order, e)
                    // Ditolak sebelum diproses: aman dikirim ulang dengan ID yang sama.
                    is ApiError.RateLimited -> {
                        if (resends++ >= MAX_RESENDS) return reject(order, e)
                        pause(e.retryAfterMs)
                    }
                    is ApiError.ClockSkew -> if (resends++ >= MAX_RESENDS) return reject(order, e)
                    // Ambigu: timeout, jaringan, 5xx, OUTCOME_UNKNOWN, respons tak terbaca.
                    else -> {
                        val remote = try {
                            exchange.get(order.clientOrderId)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (ignored: ApiError) {
                            return unresolved(order, e)
                        }
                        if (remote != null) return SubmitOutcome.Accepted(save(order.merge(remote, clock.nowMs())))
                        if (resends++ >= MAX_RESENDS) return unresolved(order, e)
                    }
                }
            }
        }
    }

    private suspend fun adoptExisting(order: LocalOrder, existingId: Long?): SubmitOutcome {
        val remote = try {
            exchange.get(order.clientOrderId)
        } catch (c: CancellationException) {
            throw c
        } catch (ignored: ApiError) {
            null
        }
        val adopted = when {
            remote != null -> order.merge(remote, clock.nowMs())
            existingId != null -> order.copy(orderId = existingId, status = LocalStatus.OPEN, updatedAtMs = clock.nowMs())
            else -> order.copy(status = LocalStatus.UNKNOWN, reason = "ID ganda tanpa detail order", updatedAtMs = clock.nowMs())
        }
        val saved = save(adopted)
        return if (saved.status == LocalStatus.UNKNOWN) SubmitOutcome.Unresolved(saved) else SubmitOutcome.Accepted(saved)
    }

    suspend fun cancel(clientOrderId: String): CancelOutcome {
        val order = store.get(clientOrderId) ?: return CancelOutcome.NotTracked
        if (order.status.terminal) return CancelOutcome.Closed(order)
        try {
            exchange.cancel(clientOrderId, order.symbolId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.NotFound) {
            return settleCancel(order)
        } catch (e: ApiError) {
            return CancelOutcome.Failed(e)
        }
        return settleCancel(save(order.copy(status = LocalStatus.CANCEL_REQUESTED, updatedAtMs = clock.nowMs())))
    }

    /** Respons cancel hanya tanda diterima: baca balik sampai order benar-benar tertutup. */
    private suspend fun settleCancel(order: LocalOrder): CancelOutcome {
        var current = order
        repeat(CONFIRM_ATTEMPTS) {
            val remote = try {
                exchange.get(order.clientOrderId)
            } catch (c: CancellationException) {
                throw c
            } catch (ignored: ApiError) {
                return CancelOutcome.Requested(current)
            } ?: return CancelOutcome.Requested(current)
            current = save(current.merge(remote, clock.nowMs()))
            if (current.status.terminal) return CancelOutcome.Closed(current)
            pause(CONFIRM_DELAY_MS)
        }
        return CancelOutcome.Requested(current)
    }

    private suspend fun reject(order: LocalOrder, error: ApiError): SubmitOutcome {
        val saved = save(order.copy(status = LocalStatus.REJECTED, reason = error.message, updatedAtMs = clock.nowMs()))
        return SubmitOutcome.Rejected(saved, error.message.orEmpty(), error)
    }

    private suspend fun unresolved(order: LocalOrder, error: ApiError): SubmitOutcome =
        SubmitOutcome.Unresolved(save(order.copy(status = LocalStatus.UNKNOWN, reason = error.message, updatedAtMs = clock.nowMs())))

    private suspend fun save(order: LocalOrder): LocalOrder = order.also { store.save(it) }

    private companion object {
        const val MAX_RESENDS = 2
        const val CONFIRM_ATTEMPTS = 3
        const val CONFIRM_DELAY_MS = 300L
    }
}

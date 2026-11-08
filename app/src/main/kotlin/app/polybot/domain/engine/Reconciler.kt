package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.RemoteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class OrphanPolicy { ADOPT, CANCEL }

data class ReconcileReport(
    val updated: Int = 0,
    val adopted: List<String> = emptyList(),
    val canceledOrphans: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    /** Order terbuka di akun yang bukan milik bot ini (prefix berbeda); tidak pernah disentuh. */
    val foreignIgnored: Int = 0,
) {
    val clean: Boolean get() = unresolved.isEmpty()
}

/**
 * Menyamakan catatan lokal dengan server. Dijalankan saat start, setelah restart, dan tiap N siklus.
 * Bila `listOpen` gagal, tidak ada yang diubah.
 */
class Reconciler(
    private val exchange: ExchangeGateway,
    private val store: OrderStore,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    suspend fun run(policy: OrphanPolicy = OrphanPolicy.ADOPT): ReconcileReport = mutex.withLock {
        val remoteOpen = exchange.listOpen()
        val byClientId = remoteOpen.filter { it.clientOrderId.isNotEmpty() }.associateBy { it.clientOrderId }
        var updated = 0
        val rejected = mutableListOf<String>()
        val unresolved = mutableListOf<String>()

        for (local in store.active()) {
            val now = clock.nowMs()
            val open = byClientId[local.clientOrderId]
            if (open != null) {
                val merged = local.merge(open, now)
                if (merged !== local) { store.save(merged); updated++ }
                continue
            }
            // Order yang baru dikirim bisa masih melayang: beri waktu sebelum menilai.
            if (local.status == LocalStatus.PENDING && now - local.updatedAtMs < PENDING_GRACE_MS) continue

            val remote = try {
                exchange.get(local.clientOrderId)
            } catch (c: CancellationException) {
                throw c
            } catch (ignored: ApiError) {
                unresolved += local.clientOrderId
                continue
            }
            if (remote != null) {
                val merged = local.merge(remote, now)
                if (merged !== local) { store.save(merged); updated++ }
                continue
            }
            when (local.status) {
                LocalStatus.PENDING, LocalStatus.UNKNOWN -> {
                    store.save(local.copy(status = LocalStatus.REJECTED, reason = "Tidak pernah diterima server", updatedAtMs = now))
                    rejected += local.clientOrderId
                    updated++
                }
                else -> {
                    store.save(local.copy(status = LocalStatus.UNKNOWN, reason = "Hilang dari server", updatedAtMs = now))
                    unresolved += local.clientOrderId
                    updated++
                }
            }
        }

        val adopted = mutableListOf<String>()
        val canceled = mutableListOf<String>()
        var foreign = 0
        for (remote in remoteOpen) {
            if (!ClientOrderIds.isOurs(remote.clientOrderId)) { foreign++; continue }
            if (store.get(remote.clientOrderId) != null) continue
            when (policy) {
                OrphanPolicy.ADOPT -> { store.save(adoptedFrom(remote, LocalStatus.OPEN)); adopted += remote.clientOrderId }
                OrphanPolicy.CANCEL -> try {
                    exchange.cancel(remote.clientOrderId, remote.symbolId)
                    store.save(adoptedFrom(remote, LocalStatus.CANCEL_REQUESTED))
                    canceled += remote.clientOrderId
                } catch (c: CancellationException) {
                    throw c
                } catch (ignored: ApiError) {
                    unresolved += remote.clientOrderId
                }
            }
        }
        ReconcileReport(updated, adopted, canceled, rejected, unresolved, foreign)
    }

    private fun adoptedFrom(remote: RemoteOrder, status: LocalStatus): LocalOrder {
        val now = clock.nowMs()
        return LocalOrder(
            clientOrderId = remote.clientOrderId, orderId = remote.orderId, symbolId = remote.symbolId,
            side = remote.side ?: app.polybot.domain.model.Side.BUY, priceTicks = remote.priceTicks,
            qtyScaled = remote.qtyScaled, filledScaled = remote.filledScaled, postOnly = remote.postOnly,
            tif = remote.tif, status = status, reason = "Order yatim", createdAtMs = now, updatedAtMs = now, adopted = true,
        )
    }

    private companion object {
        const val PENDING_GRACE_MS = 30_000L
    }
}

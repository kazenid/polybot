package app.polybot.domain.engine

import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.RemoteOrder
import app.polybot.domain.model.RemoteStatus

/**
 * Menggabungkan status server ke catatan lokal. Status terminal tidak pernah mundur, dan
 * CANCEL_REQUESTED tidak turun ke OPEN selama server masih menyebut order itu bekerja.
 * Mengembalikan instance yang sama bila tidak ada yang berubah.
 */
internal fun LocalOrder.merge(remote: RemoteOrder, nowMs: Long): LocalOrder {
    if (status.terminal) return this
    val mapped = when (remote.status) {
        RemoteStatus.PENDING, RemoteStatus.WORKING ->
            if (status == LocalStatus.CANCEL_REQUESTED) LocalStatus.CANCEL_REQUESTED else LocalStatus.OPEN
        RemoteStatus.PENDING_CANCEL -> LocalStatus.CANCEL_REQUESTED
        RemoteStatus.FILLED -> LocalStatus.FILLED
        RemoteStatus.CANCELED -> LocalStatus.CANCELED
        RemoteStatus.REJECTED -> LocalStatus.REJECTED
        RemoteStatus.UNKNOWN -> status
    }
    if (mapped == status && remote.filledScaled == filledScaled && remote.orderId == orderId) return this
    return copy(orderId = remote.orderId, status = mapped, filledScaled = remote.filledScaled, reason = null, updatedAtMs = nowMs)
}

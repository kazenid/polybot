package app.polybot.domain.model

/** Status order menurut catatan lokal bot. */
enum class LocalStatus {
    /** Tersimpan sebelum dikirim; belum ada konfirmasi server. */
    PENDING,
    OPEN,
    CANCEL_REQUESTED,
    FILLED,
    CANCELED,
    REJECTED,

    /** Hasil kiriman ambigu dan belum bisa dibuktikan; Reconciler yang menyelesaikan. */
    UNKNOWN;

    val terminal: Boolean get() = this == FILLED || this == CANCELED || this == REJECTED
}

data class LocalOrder(
    val clientOrderId: String,
    val orderId: Long?,
    val symbolId: Long,
    val side: Side,
    val priceTicks: Long,
    val qtyScaled: Long,
    val filledScaled: Long,
    val postOnly: Boolean,
    val tif: String,
    val status: LocalStatus,
    val reason: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** True bila order ditemukan di server tanpa catatan lokal (yatim) lalu diadopsi. */
    val adopted: Boolean = false,
)

/** Permintaan order yang sudah dalam satuan kawat. */
data class OrderRequest(
    val clientOrderId: String,
    val symbolId: Long,
    val side: Side,
    val priceTicks: Long,
    val qtyScaled: Long,
    val postOnly: Boolean,
    val tif: String,
    val expireAtMs: Long?,
)

enum class RemoteStatus { PENDING, WORKING, PENDING_CANCEL, FILLED, CANCELED, REJECTED, UNKNOWN }

data class RemoteOrder(
    val orderId: Long,
    val clientOrderId: String,
    val symbolId: Long,
    val side: Side?,
    val status: RemoteStatus,
    val priceTicks: Long,
    val qtyScaled: Long,
    val filledScaled: Long,
    val postOnly: Boolean,
    val tif: String,
)

package app.polybot.core.result

import app.polybot.data.catalog.CatalogNotReadyException

/** Pesan untuk UI. Tidak pernah memuat nilai kunci. */
fun Throwable.userMessage(): String = when (this) {
    is ApiError.RateLimited -> "Terlalu banyak request. Coba lagi dalam ${(retryAfterMs + 999) / 1000} detik."
    is ApiError.ClockSkew -> "Jam perangkat tidak sinkron dengan server. Aktifkan waktu otomatis di pengaturan HP."
    is ApiError.Transient -> "Server sedang bermasalah: $message"
    is ApiError.Timeout -> "Server tidak merespons (timeout)."
    is ApiError.Network -> "Tidak ada koneksi ke server. Periksa jaringan."
    is ApiError.OutcomeUnknown -> "Hasil operasi tidak diketahui ($code). Periksa status sebelum mengulang."
    is ApiError.NotFound -> "Data tidak ditemukan di server."
    is ApiError.DuplicateClientOrderId -> "client_order_id sudah dipakai oleh order lain."
    is ApiError.NotAvailable -> "Fitur tidak tersedia di server ini ($code)."
    is ApiError.BadResponse -> "Respons server tidak sesuai harapan: $message"
    is ApiError.Validation -> "Permintaan ditolak ($code): $message"
    is ApiError.Auth -> when (code.uppercase()) {
        "NO_CREDENTIALS" -> "API key belum diisi. Buka Pengaturan > Akun & API Key."
        "SIGNATURE_INVALID" -> "Tanda tangan ditolak: private key tidak cocok dengan API key id."
        "KEY_DISABLED" -> "API key dinonaktifkan di Polyester."
        "IP_NOT_ALLOWED" -> "IP perangkat tidak diizinkan oleh API key."
        "UNAUTHENTICATED" -> "Autentikasi ditolak. Periksa API key id dan private key."
        else -> "Akses ditolak ($code). Periksa policy API key (Spot trading, market, ukuran order)."
    }
    is CatalogNotReadyException -> "Katalog pasar belum dimuat."
    else -> "Terjadi kesalahan: ${javaClass.simpleName}"
}

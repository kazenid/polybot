package app.polybot.ui.guide

/** Langkah per merek supaya sistem tidak mematikan bot di latar belakang. Murni data, mudah diuji. */
data class BrandGuide(val brand: String, val steps: List<String>)

object BatteryGuide {
    private val common = listOf(
        "Kunci aplikasi di daftar aplikasi terbaru (recents): tahan kartu PolyBot lalu pilih Kunci, supaya tidak ikut tersapu \"Bersihkan semua\".",
        "Jangan geser-hapus PolyBot dari daftar aplikasi terbaru saat bot berjalan.",
        "Pakai HP khusus yang tercolok charger. Bila ada, batasi pengisian sampai sekitar 80% agar baterai tidak menggembung.",
        "Jauhkan dari tempat panas dan jangan ditutup bantal atau casing tebal.",
    )

    fun forManufacturer(manufacturer: String): BrandGuide {
        val m = manufacturer.lowercase()
        return when {
            m.containsAny("xiaomi", "redmi", "poco") -> BrandGuide(
                "Xiaomi / Redmi / POCO (HyperOS atau MIUI)",
                listOf(
                    "Info aplikasi PolyBot > Penghemat baterai > pilih \"Tanpa batasan\".",
                    "Info aplikasi PolyBot > Mulai otomatis: aktifkan.",
                    "Pengaturan > Baterai > matikan optimasi untuk PolyBot bila ada daftar aplikasinya.",
                ) + common,
            )
            m.containsAny("oppo", "realme", "oneplus") -> BrandGuide(
                "OPPO / realme / OnePlus (ColorOS atau OxygenOS)",
                listOf(
                    "Info aplikasi PolyBot > Penggunaan baterai > aktifkan \"Izinkan aktivitas latar belakang\" dan \"Izinkan mulai otomatis\".",
                    "Pengaturan > Baterai > matikan \"Optimalkan penggunaan baterai\" untuk PolyBot.",
                ) + common,
            )
            m.containsAny("vivo", "iqoo") -> BrandGuide(
                "vivo / iQOO (Funtouch atau OriginOS)",
                listOf(
                    "Pengaturan > Baterai > Konsumsi daya latar belakang > PolyBot > izinkan.",
                    "Pengaturan > Aplikasi > Mulai otomatis: aktifkan PolyBot.",
                ) + common,
            )
            m.containsAny("huawei", "honor") -> BrandGuide(
                "Huawei / Honor (EMUI atau MagicOS)",
                listOf(
                    "Pengaturan > Baterai > Peluncuran aplikasi > PolyBot > Kelola manual, lalu aktifkan Peluncuran otomatis, Peluncuran sekunder, dan Berjalan di latar belakang.",
                ) + common,
            )
            m.containsAny("samsung") -> BrandGuide(
                "Samsung (One UI)",
                listOf(
                    "Pengaturan > Baterai > Batas penggunaan di latar belakang: matikan \"Tidurkan aplikasi yang tidak digunakan\".",
                    "Tambahkan PolyBot ke \"Aplikasi yang tidak pernah tidur\".",
                    "Info aplikasi PolyBot > Baterai > pilih \"Tanpa batasan\".",
                ) + common,
            )
            else -> BrandGuide(
                "Android umum",
                listOf("Info aplikasi PolyBot > Baterai > pilih \"Tanpa batasan\" (atau Unrestricted).") + common,
            )
        }
    }

    private fun String.containsAny(vararg parts: String) = parts.any { contains(it) }
}

package app.polybot.domain.config

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Profil siap pakai untuk Market Maker. Profil hanya menandai tingkat kehati-hatian (seberapa lebar jarak kuotasi,
 * seberapa cepat order diperbarui, seberapa besar inventori ditahan); bukan jaminan hasil.
 */
enum class MmProfile(val label: String, val description: String, internal val marginOverFloorBps: Int) {
    CAUTIOUS("Hati-hati", "Jarak lebar: order jarang terisi, tetapi margin per putaran tebal dan inventori dijaga kecil.", 20),
    BALANCED("Seimbang", "Seimbang antara seringnya order terisi dan tebalnya margin. Ini nilai bawaan.", 5),
    AGGRESSIVE("Agresif", "Jarak rapat: sering terisi, tetapi margin hampir habis oleh fee dan lebih rentan saat harga bergerak searah.", 1),
}

object MarketMakerProfiles {
    private class Params(val requote: String, val maxAgeSec: Int, val maxInventory: String, val skew: String, val cooldownSec: Int, val trendHold: String)

    private fun params(profile: MmProfile) = when (profile) {
        MmProfile.CAUTIOUS -> Params("10", 180, "0.6", "0.5", 8, "60")
        MmProfile.BALANCED -> Params("8", 120, "0.8", "0.5", 5, "80")
        MmProfile.AGGRESSIVE -> Params("6", 90, "0.9", "0.4", 4, "100")
    }

    /** Jarak kuotasi profil = batas aman (fee + margin) ditambah selisih tetap, jadi ikut bergeser bila fee diubah. */
    fun halfSpread(profile: MmProfile, config: MarketMakerConfig): BigDecimal =
        config.makerFeeBps.add(config.minNetEdgeBps).add(BigDecimal(profile.marginOverFloorBps))

    /** Menerapkan profil; nilai pengguna untuk order, fee, margin, level, jendela tren, dan saklar impas dipertahankan. */
    fun apply(profile: MmProfile, current: MarketMakerConfig): MarketMakerConfig {
        val p = params(profile)
        return current.copy(
            halfSpreadBps = halfSpread(profile, current),
            requoteThresholdBps = BigDecimal(p.requote),
            maxOrderAgeSec = p.maxAgeSec,
            maxInventoryPct = BigDecimal(p.maxInventory),
            skewFactor = BigDecimal(p.skew),
            cooldownSec = p.cooldownSec,
            trendHoldBps = BigDecimal(p.trendHold),
        )
    }

    /** Profil yang cocok persis dengan pengaturan sekarang; null berarti Kustom. */
    fun detect(config: MarketMakerConfig): MmProfile? = MmProfile.entries.firstOrNull { profile ->
        val p = params(profile)
        config.halfSpreadBps.compareTo(halfSpread(profile, config)) == 0 &&
            config.requoteThresholdBps.compareTo(BigDecimal(p.requote)) == 0 &&
            config.maxOrderAgeSec == p.maxAgeSec &&
            config.maxInventoryPct.compareTo(BigDecimal(p.maxInventory)) == 0 &&
            config.skewFactor.compareTo(BigDecimal(p.skew)) == 0 &&
            config.cooldownSec == p.cooldownSec &&
            config.trendHoldBps.compareTo(BigDecimal(p.trendHold)) == 0
    }
}

enum class Verdict { LOSS, THIN, GOOD }

/**
 * Hasil teoritis satu putaran beli lalu jual: kedua sisi di jarak [halfSpreadBps] dari harga tengah, kedua fill kena
 * fee maker. Tidak memperhitungkan harga yang bergerak melawan order, jadi angka kenyataan lebih kecil.
 */
data class RoundTrip(
    val halfSpreadBps: BigDecimal,
    val grossBps: BigDecimal,
    val feeBps: BigDecimal,
    val netBps: BigDecimal,
    val netQuote: BigDecimal,
    val buyPrice: BigDecimal?,
    val sellPrice: BigDecimal?,
) {
    val verdict: Verdict
        get() = when {
            netBps.signum() <= 0 -> Verdict.LOSS
            netBps < THIN_BPS -> Verdict.THIN
            else -> Verdict.GOOD
        }

    companion object {
        /** Di bawah ini margin bersih per putaran dianggap tipis. */
        val THIN_BPS: BigDecimal = BigDecimal(20)
    }
}

object MarketMakerAdvice {
    private val BPS = BigDecimal(10_000)

    fun roundTrip(config: MarketMakerConfig, mid: BigDecimal?): RoundTrip {
        val half = config.effectiveHalfSpreadBps
        val gross = half.multiply(BigDecimal(2))
        val fee = config.makerFeeBps.multiply(BigDecimal(2))
        val net = gross.subtract(fee)
        val offset = half.divide(BPS, 12, RoundingMode.HALF_UP)
        return RoundTrip(
            halfSpreadBps = half,
            grossBps = gross,
            feeBps = fee,
            netBps = net,
            netQuote = config.orderQuote.multiply(net).divide(BPS, 6, RoundingMode.HALF_UP),
            buyPrice = mid?.multiply(BigDecimal.ONE.subtract(offset)),
            sellPrice = mid?.multiply(BigDecimal.ONE.add(offset)),
        )
    }

    enum class Field { HALF_SPREAD, FEE, MARGIN, REQUOTE, ORDER_AGE, COOLDOWN, TREND_HOLD }

    /** Peringatan singkat per kolom untuk nilai yang masuk akal secara angka tetapi hampir pasti merugikan. */
    fun check(config: MarketMakerConfig): Map<Field, String> = buildMap {
        val floor = config.safeHalfSpreadBps
        if (config.halfSpreadBps < MarketMakerConfig.MIN_HALF_SPREAD_BPS) {
            put(Field.HALF_SPREAD, "Di bawah batas mutlak ${plain(MarketMakerConfig.MIN_HALF_SPREAD_BPS)} bps, dipakai ${plain(MarketMakerConfig.MIN_HALF_SPREAD_BPS)} bps")
        } else if (config.halfSpreadBps < floor) {
            put(Field.HALF_SPREAD, "Di bawah fee + margin (${plain(floor)} bps): margin bersih per putaran menipis atau rugi")
        }
        if (config.makerFeeBps.signum() <= 0) put(Field.FEE, "Fee 0 membuat bot menghitung seolah tanpa biaya. Testnet saat ini 10 bps")
        if (config.minNetEdgeBps.signum() <= 0) put(Field.MARGIN, "Tanpa margin, satu putaran paling banter impas setelah fee")
        if (config.requoteThresholdBps >= config.effectiveHalfSpreadBps) {
            put(Field.REQUOTE, "Lebih besar dari jarak kuotasi: order bisa terisi sebelum sempat dibatalkan")
        }
        if (config.maxOrderAgeSec < 15) put(Field.ORDER_AGE, "Terlalu singkat: order sering diperiksa ulang tanpa perlu")
        if (config.cooldownSec < 2) put(Field.COOLDOWN, "Terlalu rapat: banyak permintaan ke server dan mudah kena batas laju")
        if (config.trendHoldBps < BigDecimal(5)) put(Field.TREND_HOLD, "Terlalu sensitif: beli sering ditahan oleh gerak harga kecil")
    }

    /**
     * Ringkasan satu-dua kalimat dari pengaturan. [buyText] dan [sellText] adalah harga yang sudah diformat;
     * null bila harga pasar belum diketahui.
     */
    fun summary(config: BotConfig, buyText: String?, sellText: String?, quote: String): String {
        val mm = config.marketMaker
        val half = mm.effectiveHalfSpreadBps
        val order = plain(mm.orderQuote)
        val place = if (buyText != null && sellText != null) {
            "Bot memasang beli $order $quote di $buyText dan jual di $sellText"
        } else {
            "Bot memasang beli dan jual masing-masing $order $quote, ${plain(half)} bps (${percent(half)}%) dari harga tengah"
        }
        val exposure = plain(config.risk.maxExposurePct.multiply(BigDecimal(100)))
        return "$place, lalu memperbarui bila harga bergeser ${plain(mm.requoteThresholdBps)} bps. " +
            "Maksimal menahan $exposure% ekuitas dalam aset dasar dan berhenti bila rugi ${plain(config.risk.maxDailyLossQuote)} $quote dalam sehari."
    }

    /** Persen dari bps dengan koma desimal, mis. 25 bps -> "0,25". */
    fun percent(bps: BigDecimal): String = plain(bps.divide(BigDecimal(100), 4, RoundingMode.HALF_UP)).replace('.', ',')

    fun plain(value: BigDecimal): String = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
}

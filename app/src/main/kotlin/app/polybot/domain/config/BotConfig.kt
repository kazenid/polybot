package app.polybot.domain.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

/** Angka desimal disimpan sebagai teks supaya presisi tidak hilang (tanpa Double). */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
}

enum class StrategyType(val label: String) {
    MARKET_MAKER("Market Maker ringan"),
    GRID("Grid"),
    DCA("DCA terjadwal"),
}

/** Batas risiko. Default sengaja kecil dan konservatif (plan bagian 10.6). */
@Serializable
data class RiskConfig(
    /** Batas mutlak nilai quote per order. */
    @Serializable(with = BigDecimalSerializer::class) val maxOrderQuote: BigDecimal = BigDecimal("10"),
    /** Batas nilai per order sebagai fraksi ekuitas (0.02 = 2%). */
    @Serializable(with = BigDecimalSerializer::class) val maxOrderPctOfEquity: BigDecimal = BigDecimal("0.02"),
    /** Nilai aset dasar maksimum sebagai fraksi ekuitas. */
    @Serializable(with = BigDecimalSerializer::class) val maxExposurePct: BigDecimal = BigDecimal("0.15"),
    val maxOpenOrders: Int = 6,
    val maxNewOrdersPerMinute: Int = 20,
    /** Order ditolak bila harganya menyimpang lebih dari ini dari mid (0.02 = 2%). */
    @Serializable(with = BigDecimalSerializer::class) val maxPriceDeviationPct: BigDecimal = BigDecimal("0.02"),
    /** Fraksi saldo quote yang selalu disisakan. */
    @Serializable(with = BigDecimalSerializer::class) val reserveQuotePct: BigDecimal = BigDecimal("0.05"),
    /** Rugi harian (dalam quote) yang menghentikan bot dan membatalkan order. */
    @Serializable(with = BigDecimalSerializer::class) val maxDailyLossQuote: BigDecimal = BigDecimal("20"),
    val maxConsecutiveErrors: Int = 5,
    val maxClockSkewMs: Long = 3_000,
    /** Hanya simbol ini yang boleh diperdagangkan. */
    val allowedSymbols: Set<String> = setOf("BTC-USDT"),
)

@Serializable
data class MarketMakerConfig(
    @Serializable(with = BigDecimalSerializer::class) val halfSpreadBps: BigDecimal = BigDecimal("25"),
    @Serializable(with = BigDecimalSerializer::class) val orderQuote: BigDecimal = BigDecimal("10"),
    val levels: Int = 1,
    @Serializable(with = BigDecimalSerializer::class) val levelStepBps: BigDecimal = BigDecimal("10"),
    @Serializable(with = BigDecimalSerializer::class) val requoteThresholdBps: BigDecimal = BigDecimal("8"),
    val maxOrderAgeSec: Int = 60,
    /**
     * Bagian dari batas eksposur (RiskConfig.maxExposurePct) di mana sisi beli mulai ditahan; sisi jual ditahan
     * di bawah (1 - nilai ini). Inventori target = separuh batas eksposur.
     */
    @Serializable(with = BigDecimalSerializer::class) val maxInventoryPct: BigDecimal = BigDecimal("0.8"),
    /** Geser kuotasi maksimum = skewFactor x halfSpread, saat inventori berada di ujung pita eksposur. */
    @Serializable(with = BigDecimalSerializer::class) val skewFactor: BigDecimal = BigDecimal("0.5"),
    val cooldownSec: Int = 5,
    /** Fee maker per fill (bps). Testnet Polyester: 10 bps. Half spread efektif tidak pernah di bawah fee + margin. */
    @Serializable(with = BigDecimalSerializer::class) val makerFeeBps: BigDecimal = BigDecimal("10"),
    /** Margin bersih minimum per sisi di atas fee (bps) agar satu pergeseran harga kecil tidak menghabiskan laba. */
    @Serializable(with = BigDecimalSerializer::class) val minNetEdgeBps: BigDecimal = BigDecimal("10"),
    /** Jendela pengamatan tren (detik). Beli ditahan bila harga turun sebesar [trendHoldBps] dari puncak jendela. */
    val trendWindowSec: Int = 300,
    @Serializable(with = BigDecimalSerializer::class) val trendHoldBps: BigDecimal = BigDecimal("20"),
    /**
     * Jual tidak pernah di bawah titik impas: biaya pembelian terbaru yang belum terjual + fee beli dan jual +
     * [minNetEdgeBps]. Tanpa ini, penutupan posisi saat harga turun merealisasi rugi yang dimakan fee dua kali.
     */
    val sellAboveCost: Boolean = true,
) {
    /** Half spread yang benar-benar dipakai: pengaturan pengguna, tetapi tidak di bawah fee + margin minimum. */
    val effectiveHalfSpreadBps: BigDecimal get() = halfSpreadBps.max(makerFeeBps.add(minNetEdgeBps))

    /**
     * Harga jual impas untuk lot berbiaya [lotCost]: hasil jual setelah fee maker harus menutup biaya
     * beli beserta fee maker-nya dan margin. `jual x (1 - f) >= biaya x (1 + f + m)`.
     */
    fun breakEvenSell(lotCost: BigDecimal): BigDecimal {
        val f = makerFeeBps.divide(BPS)
        val m = minNetEdgeBps.divide(BPS)
        return lotCost.multiply(BigDecimal.ONE.add(f).add(m)).divide(BigDecimal.ONE.subtract(f), 12, java.math.RoundingMode.HALF_UP)
    }

    private companion object {
        val BPS: BigDecimal = BigDecimal(10_000)
    }
}

@Serializable
data class GridConfig(
    @Serializable(with = BigDecimalSerializer::class) val lower: BigDecimal = BigDecimal("0"),
    @Serializable(with = BigDecimalSerializer::class) val upper: BigDecimal = BigDecimal("0"),
    val gridCount: Int = 10,
    @Serializable(with = BigDecimalSerializer::class) val orderQuote: BigDecimal = BigDecimal("10"),
    /** Bila mid keluar rentang lebih dari ini (bps), semua order dibatalkan dan bot berhenti. */
    @Serializable(with = BigDecimalSerializer::class) val rangeBreakBps: BigDecimal = BigDecimal("100"),
)

@Serializable
data class DcaConfig(
    val intervalSec: Int = 3_600,
    @Serializable(with = BigDecimalSerializer::class) val orderQuote: BigDecimal = BigDecimal("10"),
    @Serializable(with = BigDecimalSerializer::class) val maxTotalQuote: BigDecimal = BigDecimal("100"),
)

@Serializable
data class BotConfig(
    val symbol: String = "BTC-USDT",
    val strategy: StrategyType = StrategyType.MARKET_MAKER,
    /** Aman secara default: keputusan hanya dicatat, tidak ada order dikirim. */
    val dryRun: Boolean = true,
    /** Saat Stop/error fatal, batalkan order milik bot (berdasarkan prefix client_order_id). */
    val cancelOnStop: Boolean = true,
    /** Kill switch juga membatalkan order manual di pair ini (cancel_all dengan filter simbol). */
    val killSwitchCancelsAllOnSymbol: Boolean = false,
    /**
     * Setelah proses dimatikan sistem atau HP restart, sesi LIVE dilanjutkan otomatis. Mati secara default:
     * sesi dry run selalu boleh dilanjutkan, sesi live butuh persetujuan eksplisit ini.
     */
    val autoResumeLive: Boolean = false,
    val pollActiveSec: Int = 5,
    val pollIdleSec: Int = 20,
    val risk: RiskConfig = RiskConfig(),
    val marketMaker: MarketMakerConfig = MarketMakerConfig(),
    val grid: GridConfig = GridConfig(),
    val dca: DcaConfig = DcaConfig(),
) {
    /** Kesalahan konfigurasi dalam bahasa pengguna; kosong bila valid. */
    fun validate(): List<String> = buildList {
        if (symbol.isBlank()) add("Simbol kosong")
        if (symbol !in risk.allowedSymbols) add("Simbol $symbol tidak ada di daftar simbol yang diizinkan")
        if (pollActiveSec < 1 || pollIdleSec < pollActiveSec) add("Interval polling tidak valid")
        if (risk.maxOrderQuote.signum() <= 0) add("Batas nilai per order harus positif")
        if (risk.maxOpenOrders < 1) add("Maksimum order terbuka minimal 1")
        if (risk.maxOrderPctOfEquity.signum() <= 0 || risk.maxOrderPctOfEquity > BigDecimal.ONE) add("Persen order per ekuitas harus 0-1")
        if (risk.maxExposurePct.signum() <= 0 || risk.maxExposurePct > BigDecimal.ONE) add("Eksposur maksimum harus 0-1")
        if (risk.maxDailyLossQuote.signum() <= 0) add("Batas rugi harian harus positif")
        when (strategy) {
            StrategyType.MARKET_MAKER -> {
                if (marketMaker.halfSpreadBps.signum() <= 0) add("Half spread harus positif")
                if (marketMaker.makerFeeBps.signum() < 0 || marketMaker.minNetEdgeBps.signum() < 0) add("Fee dan margin minimum tidak boleh negatif")
                if (marketMaker.trendWindowSec < 30 || marketMaker.trendHoldBps.signum() <= 0) add("Pengaturan deteksi tren tidak valid (jendela minimal 30 detik)")
                if (marketMaker.orderQuote.signum() <= 0) add("Nilai order MM harus positif")
                if (marketMaker.levels !in 1..3) add("Level MM harus 1-3")
                if (marketMaker.maxInventoryPct <= BigDecimal("0.5") || marketMaker.maxInventoryPct >= BigDecimal.ONE) {
                    add("Batas inventori MM harus di antara 0.5 dan 1")
                }
                if (marketMaker.levels * 2 > risk.maxOpenOrders) add("Level MM x 2 melebihi maksimum order terbuka")
                if (marketMaker.orderQuote > risk.maxOrderQuote) add("Nilai order MM melebihi batas risiko per order")
            }
            StrategyType.GRID -> {
                if (grid.lower.signum() <= 0 || grid.upper <= grid.lower) add("Rentang grid tidak valid (lower < upper, keduanya positif)")
                if (grid.gridCount < 2) add("Jumlah grid minimal 2")
                if (grid.orderQuote > risk.maxOrderQuote) add("Nilai order grid melebihi batas risiko per order")
                if (grid.gridCount > risk.maxOpenOrders) add("Jumlah grid melebihi maksimum order terbuka")
            }
            StrategyType.DCA -> {
                if (dca.intervalSec < 10) add("Interval DCA minimal 10 detik")
                if (dca.orderQuote.signum() <= 0 || dca.maxTotalQuote < dca.orderQuote) add("Total DCA harus >= nilai satu order")
                if (dca.orderQuote > risk.maxOrderQuote) add("Nilai order DCA melebihi batas risiko per order")
            }
        }
    }
}

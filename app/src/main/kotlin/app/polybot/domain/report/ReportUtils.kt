package app.polybot.domain.report

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CsvExporter {
    private const val HEADER = "waktu_utc,simbol,sisi,peran,harga,jumlah,nilai_quote,fee_quote,pnl_terealisasi,order_id,match_id"

    fun fills(results: List<FillResult>): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = StringBuilder(HEADER).append('\n')
        for (r in results) {
            val f = r.fill
            out.append(
                listOf(
                    format.format(Date(f.tsMs)), f.symbol, f.side.name, if (f.isMaker) "maker" else "taker",
                    f.price.toPlainString(), f.qty.toPlainString(), f.notional.toPlainString(), f.feeQuote.toPlainString(),
                    r.realized.toPlainString(), f.orderId.toString(), f.matchId.toString(),
                ).joinToString(",") { escape(it) },
            ).append('\n')
        }
        return out.toString()
    }

    /** Sel yang diawali = + - @ dijaga dari injeksi rumus spreadsheet; koma dan kutip di-escape. */
    internal fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+@" ) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** Mengurangi deret ke paling banyak [maxPoints] titik tanpa mengubah ujung-ujungnya. */
fun <T> downsample(items: List<T>, maxPoints: Int): List<T> {
    require(maxPoints >= 2) { "maxPoints minimal 2" }
    if (items.size <= maxPoints) return items
    val step = (items.size - 1).toDouble() / (maxPoints - 1)
    return List(maxPoints) { i -> items[Math.round(i * step).toInt().coerceAtMost(items.size - 1)] }
}

fun BigDecimal.money(scale: Int = 2): String = setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString()

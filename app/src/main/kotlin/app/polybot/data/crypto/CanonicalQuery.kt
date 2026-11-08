package app.polybot.data.crypto

import java.io.ByteArrayOutputStream

/**
 * Query kanonik untuk string yang ditandatangani: pasangan diurutkan menurut key lalu value,
 * tiap komponen di-percent-encode, hanya `A-Z a-z 0-9 - . _ ~` yang literal.
 * `+` di input dibaca sebagai spasi, lalu di-encode ulang sebagai `%20`.
 */
object CanonicalQuery {
    private const val HEX = "0123456789ABCDEF"

    fun canonicalize(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                decode(key) to decode(value)
            }
            .sortedWith(PAIR_ORDER)
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    internal fun encode(component: String): String {
        val out = StringBuilder()
        for (b in component.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            if (isUnreserved(v)) {
                out.append(v.toChar())
            } else {
                out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
        }
        return out.toString()
    }

    private fun decode(component: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < component.length) {
            val c = component[i]
            when {
                c == '+' -> {
                    out.write(' '.code)
                    i++
                }
                c == '%' && isHexPair(component, i + 1) -> {
                    out.write((Character.digit(component[i + 1], 16) shl 4) or Character.digit(component[i + 2], 16))
                    i += 3
                }
                else -> {
                    val cp = component.codePointAt(i)
                    out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(cp)
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun isHexPair(s: String, at: Int): Boolean =
        at + 1 < s.length && Character.digit(s[at], 16) >= 0 && Character.digit(s[at + 1], 16) >= 0

    private fun isUnreserved(v: Int): Boolean =
        v in 'A'.code..'Z'.code || v in 'a'.code..'z'.code || v in '0'.code..'9'.code ||
            v == '-'.code || v == '.'.code || v == '_'.code || v == '~'.code

    private val PAIR_ORDER = Comparator<Pair<String, String>> { a, b ->
        compareBytes(a.first, b.first).takeIf { it != 0 } ?: compareBytes(a.second, b.second)
    }

    private fun compareBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }
}

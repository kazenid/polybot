package app.polybot.domain.engine

/**
 * `pb{runShort}-{seq}`: maks 36 karakter ASCII dari `A-Z a-z 0-9 . _ : / -`.
 * `seq` disimpan di [OrderStore] dan monoton, jadi ID tidak pernah dipakai ulang setelah restart.
 */
class ClientOrderIds(private val store: OrderStore) {
    suspend fun next(): String {
        val id = "$PREFIX${store.runShort()}-${store.nextSeq().toString(36)}"
        check(VALID.matches(id)) { "client_order_id tidak valid" }
        return id
    }

    companion object {
        const val PREFIX = "pb"
        private val VALID = Regex("^[A-Za-z0-9._:/-]{1,36}$")

        /** Order yang dibuat bot ini (juga dari instalasi sebelumnya). Order lain tidak boleh disentuh. */
        fun isOurs(clientOrderId: String): Boolean = clientOrderId.startsWith(PREFIX) && clientOrderId.contains('-')
    }
}

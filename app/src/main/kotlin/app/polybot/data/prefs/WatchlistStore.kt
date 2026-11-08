package app.polybot.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchlistDataStore by preferencesDataStore(name = "watchlist")

/** Daftar simbol di dashboard, urutan dipertahankan. `null` = belum pernah diatur (pakai bawaan). */
@Singleton
class WatchlistStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("symbols")

    val symbols: Flow<List<String>?> = context.watchlistDataStore.data.map { decode(it[key]) }

    suspend fun current(): List<String>? = symbols.first()

    suspend fun save(symbols: List<String>) {
        context.watchlistDataStore.edit { it[key] = encode(symbols) }
    }

    companion object {
        fun encode(symbols: List<String>): String = symbols.distinct().joinToString(",")

        fun decode(raw: String?): List<String>? = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()

        /** Bawaan: BTC-USDT dan ETH-USDT bila aktif, lalu pair aktif lain sampai [limit]. */
        fun defaults(activeSymbols: List<String>, limit: Int = 5): List<String> {
            val preferred = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT").filter { it in activeSymbols }
            return (preferred + activeSymbols.filter { it !in preferred }).take(limit)
        }
    }
}

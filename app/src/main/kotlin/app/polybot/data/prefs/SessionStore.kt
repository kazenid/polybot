package app.polybot.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.polybot.domain.engine.SavedSession
import kotlinx.coroutines.flow.first

private val Context.sessionDataStore by preferencesDataStore(name = "bot_session")

/** Menandai apakah bot sedang dalam sesi, supaya bisa dipulihkan bila proses mati tanpa Stop dari pengguna. */
class SessionStore(private val context: Context) {
    private val wantKey = booleanPreferencesKey("want_running")
    private val lastKey = longPreferencesKey("last_active_ms")

    suspend fun load(): SavedSession {
        val prefs = context.sessionDataStore.data.first()
        return SavedSession(prefs[wantKey] ?: false, prefs[lastKey] ?: 0L)
    }

    suspend fun mark(wantRunning: Boolean, nowMs: Long) {
        context.sessionDataStore.edit { it[wantKey] = wantRunning; it[lastKey] = nowMs }
    }

    /** Denyut: memperbarui waktu aktif terakhir tanpa mengubah penanda. */
    suspend fun touch(nowMs: Long) {
        context.sessionDataStore.edit { it[lastKey] = nowMs }
    }
}

package app.polybot.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class PolyEnv(val label: String, val apiUrl: String) {
    TESTNET("Testnet", "https://api.testnet.polyester.com"),
    DEVNET("Devnet", "https://api.devnet.polyester.com"),
}

private val Context.envDataStore by preferencesDataStore(name = "env_config")

class EnvStore(private val context: Context) {
    private val key = stringPreferencesKey("environment")

    val environment: Flow<PolyEnv> = context.envDataStore.data.map { prefs ->
        prefs[key]?.let { name -> PolyEnv.entries.firstOrNull { it.name == name } } ?: PolyEnv.TESTNET
    }

    suspend fun current(): PolyEnv = environment.first()

    suspend fun set(env: PolyEnv) {
        context.envDataStore.edit { it[key] = env.name }
    }
}

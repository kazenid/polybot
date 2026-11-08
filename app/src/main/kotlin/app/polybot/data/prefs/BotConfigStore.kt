package app.polybot.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.polybot.domain.config.BotConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.botConfigDataStore by preferencesDataStore(name = "bot_config")

/** Konfigurasi bot sebagai satu dokumen JSON. JSON rusak atau versi lama jatuh ke default, tidak crash. */
class BotConfigStore(private val context: Context) {
    private val key = stringPreferencesKey("config_json")

    val config: Flow<BotConfig> = context.botConfigDataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): BotConfig = config.first()

    suspend fun save(config: BotConfig) {
        context.botConfigDataStore.edit { it[key] = JSON.encodeToString(BotConfig.serializer(), config) }
    }

    /** Baca-ubah-tulis atomik: ketikan cepat dari beberapa field tidak saling menimpa. */
    suspend fun update(transform: (BotConfig) -> BotConfig) {
        context.botConfigDataStore.edit { prefs -> prefs[key] = encode(transform(decode(prefs[key]))) }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): BotConfig =
            raw?.let { runCatching { JSON.decodeFromString(BotConfig.serializer(), it) }.getOrNull() } ?: BotConfig()

        fun encode(config: BotConfig): String = JSON.encodeToString(BotConfig.serializer(), config)
    }
}

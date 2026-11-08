package app.polybot.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class ApiCredentials(
    val keyId: String,
    val privateKeyHex: String,
    val accountId: String,
) {
    override fun toString(): String = "ApiCredentials(keyId=$keyId, accountId=$accountId, privateKey=***)"
}

interface SecureKeyStore {
    fun save(credentials: ApiCredentials)
    fun load(): ApiCredentials?
    fun clear()
}

/** Kredensial dienkripsi AES-GCM dengan kunci Android Keystore; ciphertext disimpan di prefs privat app. */
class AndroidKeystoreKeyStore(context: Context) : SecureKeyStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(credentials: ApiCredentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(Json.encodeToString(ApiCredentials.serializer(), credentials).toByteArray())
        val blob = cipher.iv + ciphertext
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun load(): ApiCredentials? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            Json.decodeFromString(ApiCredentials.serializer(), String(plain))
        }.getOrNull()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "polybot_credentials_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "polybot_secure"
        const val KEY_BLOB = "credentials"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

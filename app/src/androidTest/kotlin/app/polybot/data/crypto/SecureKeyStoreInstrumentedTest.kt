package app.polybot.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AndroidKeystoreKeyStore
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

    @Before
    fun setUp() {
        store = AndroidKeystoreKeyStore(context)
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun savedCredentialsRoundTrip() {
        assertNull(store.load())
        store.save(ApiCredentials("key-1", seed, "acc-9"))

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("key-1", loaded!!.keyId)
        assertEquals(seed, loaded.privateKeyHex)
        assertEquals("acc-9", loaded.accountId)
    }

    @Test
    fun privateKeyIsNotStoredInPlaintext() {
        store.save(ApiCredentials("key-1", seed, "acc-9"))

        val raw = context.getSharedPreferences("polybot_secure", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains(seed))
        assertFalse(raw.contains("key-1"))
    }

    @Test
    fun clearRemovesCredentials() {
        store.save(ApiCredentials("key-1", seed, "acc-9"))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun savingTwiceOverwrites() {
        store.save(ApiCredentials("key-1", seed, "a"))
        store.save(ApiCredentials("key-2", seed, "b"))
        assertEquals("key-2", store.load()!!.keyId)
    }

    @Test
    fun corruptedBlobYieldsNullInsteadOfCrashing() {
        store.save(ApiCredentials("key-1", seed, "a"))
        context.getSharedPreferences("polybot_secure", Context.MODE_PRIVATE).edit().putString("credentials", "AAAA").commit()
        assertNull(store.load())
    }
}

package app.polybot.data.api

import app.polybot.core.time.Clock
import app.polybot.core.time.ClockMonitor
import app.polybot.data.crypto.Ed25519Signer
import app.polybot.data.crypto.RequestSigner
import app.polybot.data.crypto.SecureKeyStore
import app.polybot.data.crypto.TimestampAllocator
import app.polybot.data.prefs.EnvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Satu [ConnectClient] aktif per kombinasi environment + kredensial. Panggil [invalidate] saat
 * keduanya berubah; client dibangun ulang pada pemakaian berikutnya. Satu key = satu allocator timestamp.
 */
@Singleton
class ApiGateway @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val envStore: EnvStore,
    private val http: OkHttpClient,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    val clockMonitor: ClockMonitor,
) {
    private val mutex = Mutex()
    @Volatile private var cached: ConnectClient? = null

    suspend fun client(): ConnectClient = cached ?: mutex.withLock {
        cached ?: build().also { cached = it }
    }

    fun invalidate() {
        cached = null
    }

    private suspend fun build(): ConnectClient {
        val env = envStore.current()
        val credentials = withContext(Dispatchers.IO) { keyStore.load() }
        val signer = credentials?.let {
            RequestSigner(it.keyId, Ed25519Signer.fromHex(it.privateKeyHex), TimestampAllocator(clock))
        }
        return ConnectClient(env.apiUrl, http, signer, rateLimiter, clock, clockMonitor)
    }
}

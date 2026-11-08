package app.polybot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polybot.core.result.userMessage
import app.polybot.data.account.ConnectionReport
import app.polybot.data.account.ConnectionTester
import app.polybot.data.api.ApiGateway
import app.polybot.data.crypto.ApiCredentials
import app.polybot.data.crypto.Ed25519Signer
import app.polybot.data.crypto.PrivateKeyFormat
import app.polybot.data.crypto.SecureKeyStore
import app.polybot.data.prefs.EnvStore
import app.polybot.data.prefs.PolyEnv
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AccountUiState(
    val env: PolyEnv = PolyEnv.TESTNET,
    val keyId: String = "",
    val accountId: String = "",
    /** Hanya diisi saat pengguna mengetik; dikosongkan segera setelah disimpan. */
    val privateKey: String = "",
    val hasStoredKey: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val report: ConnectionReport? = null,
    /** Public key (hex) yang diturunkan dari private key. Bukan rahasia. */
    val publicKey: String? = null,
    val publicKeyFromStored: Boolean = false,
    /** Penjelasan bila private key yang diketik belum berformat benar. */
    val privateKeyProblem: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val envStore: EnvStore,
    private val gateway: ApiGateway,
    private val tester: ConnectionTester,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountUiState())
    private var storedPublicKey: String? = null
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { keyStore.load() }
            val env = envStore.current()
            storedPublicKey = stored?.privateKeyHex?.let(Ed25519Signer::publicKeyHexOrNull)
            _state.update {
                it.copy(
                    env = env,
                    keyId = stored?.keyId.orEmpty(),
                    accountId = stored?.accountId.orEmpty(),
                    hasStoredKey = stored != null,
                    publicKey = storedPublicKey,
                    publicKeyFromStored = true,
                )
            }
        }
    }

    fun onEnv(env: PolyEnv) = _state.update { it.copy(env = env) }
    fun onKeyId(value: String) = _state.update { it.copy(keyId = value.trim()) }
    fun onAccountId(value: String) = _state.update { it.copy(accountId = value.trim()) }
    fun onPrivateKey(value: String) {
        val typed = value.trim()
        val problem = if (typed.isEmpty()) null else PrivateKeyFormat.check(typed)
        val derived = when {
            typed.isEmpty() -> storedPublicKey
            problem == null -> Ed25519Signer.publicKeyHexOrNull(typed)
            else -> null
        }
        _state.update {
            it.copy(privateKey = typed, privateKeyProblem = problem, publicKey = derived, publicKeyFromStored = typed.isEmpty())
        }
    }

    fun save() {
        val s = _state.value
        if (s.keyId.isEmpty()) return fail("API key id wajib diisi.")
        if (s.privateKey.isNotEmpty()) {
            PrivateKeyFormat.check(s.privateKey)?.let { return fail("Private key tidak valid. $it") }
        }
        if (s.privateKey.isEmpty() && !s.hasStoredKey) return fail("Private key wajib diisi.")
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                storedPublicKey = withContext(Dispatchers.IO) {
                    val privateKey = s.privateKey.ifEmpty { keyStore.load()?.privateKeyHex.orEmpty() }
                    if (privateKey.isEmpty()) error("Private key tersimpan tidak terbaca; isi ulang.")
                    keyStore.save(ApiCredentials(s.keyId, privateKey, s.accountId))
                    Ed25519Signer.publicKeyHexOrNull(privateKey)
                }
                envStore.set(s.env)
                gateway.invalidate()
                _state.update {
                    it.copy(privateKey = "", privateKeyProblem = null, publicKey = storedPublicKey,
                        publicKeyFromStored = true, hasStoredKey = true, busy = false, report = null,
                        message = "Tersimpan. Jalankan Tes koneksi.", messageIsError = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Gagal menyimpan: ${e.javaClass.simpleName}")
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, report = null) }
            try {
                val report = tester.run()
                val failed = report.steps.count { !it.ok }
                _state.update {
                    it.copy(
                        busy = false, report = report,
                        message = if (failed == 0) "Koneksi berhasil." else "$failed langkah gagal. Lihat rinciannya di bawah.",
                        messageIsError = failed > 0,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.userMessage(), messageIsError = true) }
            }
        }
    }

    fun deleteKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keyStore.clear() }
            gateway.invalidate()
            storedPublicKey = null
            _state.update {
                it.copy(keyId = "", accountId = "", privateKey = "", privateKeyProblem = null, publicKey = null,
                    hasStoredKey = false, report = null,
                    message = "Kunci dihapus dari perangkat.", messageIsError = false)
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(busy = false, message = message, messageIsError = true) }
}

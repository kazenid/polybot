package app.polybot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polybot.core.result.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScreenState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAtMs: Long = 0,
)

/** Data lama tetap tampil bila refresh gagal; error ditampilkan di atasnya. */
abstract class PollingViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow(ScreenState<T>())
    val state: StateFlow<ScreenState<T>> = _state.asStateFlow()
    private var job: Job? = null

    protected abstract suspend fun load(): T

    /** [force] membatalkan pemuatan yang sedang berjalan, mis. setelah masukan pengguna berubah. */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true) {
            if (!force) return
            job?.cancel()
        }
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val value = load()
                _state.value = ScreenState(value, loading = false, error = null, updatedAtMs = System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }
}

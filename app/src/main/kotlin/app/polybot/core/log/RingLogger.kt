package app.polybot.core.log

import app.polybot.core.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val timeMs: Long, val level: LogLevel, val category: String, val message: String)

interface BotLog {
    fun log(level: LogLevel, category: String, message: String)

    fun info(category: String, message: String) = log(LogLevel.INFO, category, message)
    fun warn(category: String, message: String) = log(LogLevel.WARN, category, message)
    fun error(category: String, message: String) = log(LogLevel.ERROR, category, message)
}

/** Log terputar berukuran tetap (terlama dibuang). Jangan pernah mencatat kunci atau nilai rahasia. */
class RingLogger(private val capacity: Int = 500, private val clock: Clock = Clock.System) : BotLog {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Urut dari yang terlama; UI membaliknya. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Dipanggil untuk setiap catatan baru (mis. penyimpanan ke Room). Tidak boleh memblokir. */
    @Volatile
    var sink: ((LogEntry) -> Unit)? = null

    override fun log(level: LogLevel, category: String, message: String) {
        val entry = LogEntry(clock.nowMs(), level, category, message)
        _entries.update { (it + entry).takeLast(capacity) }
        sink?.invoke(entry)
    }

    /** Memulihkan catatan lama (terlama dulu) di depan catatan yang sudah ada. */
    fun restore(old: List<LogEntry>) {
        _entries.update { (old + it).takeLast(capacity) }
    }

    fun clear() = _entries.update { emptyList() }
}

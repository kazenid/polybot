package app.polybot

import android.app.Application
import app.polybot.core.log.RingLogger
import app.polybot.data.report.LogRepository
import app.polybot.data.report.RetentionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PolyBotApp : Application() {
    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var logger: RingLogger
    @Inject lateinit var retention: RetentionManager

    override fun onCreate() {
        super.onCreate()
        logRepository.attach(logger)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { retention.prune(System.currentTimeMillis()) }
        }
    }
}

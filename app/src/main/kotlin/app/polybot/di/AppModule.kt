package app.polybot.di

import android.content.Context
import app.polybot.core.time.Clock
import app.polybot.core.time.ClockMonitor
import app.polybot.data.api.RateLimiter
import app.polybot.data.crypto.AndroidKeystoreKeyStore
import app.polybot.data.crypto.SecureKeyStore
import app.polybot.data.prefs.EnvStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    @Provides
    @Singleton
    fun clockMonitor(): ClockMonitor = ClockMonitor()

    @Provides
    @Singleton
    fun rateLimiter(clock: Clock): RateLimiter = RateLimiter(clock)

    @Provides
    @Singleton
    fun secureKeyStore(@ApplicationContext context: Context): SecureKeyStore =
        AndroidKeystoreKeyStore(context)

    @Provides
    @Singleton
    fun envStore(@ApplicationContext context: Context): EnvStore = EnvStore(context)

    /**
     * Satu instance untuk seluruh app. `retryOnConnectionFailure(false)`: OkHttp tidak boleh mengirim ulang
     * request bertanda tangan secara diam-diam; retry diputuskan [app.polybot.data.api.ConnectClient].
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}

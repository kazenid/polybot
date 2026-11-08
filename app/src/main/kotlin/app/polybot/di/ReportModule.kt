package app.polybot.di

import android.content.Context
import androidx.room.Room
import app.polybot.data.report.ReportDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReportModule {
    @Provides
    @Singleton
    fun reportDatabase(@ApplicationContext context: Context): ReportDatabase =
        Room.databaseBuilder(context, ReportDatabase::class.java, "polybot-report.db").build()
}

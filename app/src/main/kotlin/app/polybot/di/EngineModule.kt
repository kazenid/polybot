package app.polybot.di

import android.content.Context
import androidx.room.Room
import app.polybot.core.time.Clock
import app.polybot.data.db.AppDatabase
import app.polybot.data.orders.ConnectExchangeGateway
import app.polybot.data.orders.RoomOrderStore
import app.polybot.domain.engine.ClientOrderIds
import app.polybot.domain.engine.ExchangeGateway
import app.polybot.domain.engine.OrderManager
import app.polybot.domain.engine.OrderStore
import app.polybot.domain.engine.Reconciler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "polybot.db").build()

    @Provides
    @Singleton
    fun orderStore(db: AppDatabase): OrderStore = RoomOrderStore(db.orders(), db.counters())

    @Provides
    @Singleton
    fun exchangeGateway(impl: ConnectExchangeGateway): ExchangeGateway = impl

    @Provides
    @Singleton
    fun clientOrderIds(store: OrderStore): ClientOrderIds = ClientOrderIds(store)

    @Provides
    @Singleton
    fun orderManager(exchange: ExchangeGateway, store: OrderStore, ids: ClientOrderIds, clock: Clock): OrderManager =
        OrderManager(exchange, store, ids, clock)

    @Provides
    @Singleton
    fun reconciler(exchange: ExchangeGateway, store: OrderStore, clock: Clock): Reconciler =
        Reconciler(exchange, store, clock)
}

package app.polybot.di

import android.content.Context
import app.polybot.core.log.BotLog
import app.polybot.core.log.RingLogger
import app.polybot.core.time.Clock
import app.polybot.data.bot.LiveMarketData
import app.polybot.data.catalog.CatalogRepository
import app.polybot.data.prefs.BotConfigStore
import app.polybot.data.prefs.SessionStore
import app.polybot.data.report.FillCostBasis
import app.polybot.domain.engine.BotEngine
import app.polybot.domain.engine.CatalogProvider
import app.polybot.domain.engine.ClientOrderIds
import app.polybot.domain.engine.DryRunExchange
import app.polybot.domain.engine.EngineComponents
import app.polybot.domain.engine.ExchangeGateway
import app.polybot.domain.engine.OrderManager
import app.polybot.domain.engine.OrderStore
import app.polybot.domain.engine.Reconciler
import app.polybot.domain.engine.VolatileOrderStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BotModule {
    @Provides
    @Singleton
    fun ringLogger(clock: Clock): RingLogger = RingLogger(capacity = 500, clock = clock)

    @Provides
    @Singleton
    fun botLog(logger: RingLogger): BotLog = logger

    @Provides
    @Singleton
    fun botConfigStore(@ApplicationContext context: Context): BotConfigStore = BotConfigStore(context)

    @Provides
    @Singleton
    fun sessionStore(@ApplicationContext context: Context): SessionStore = SessionStore(context)

    @Provides
    @Singleton
    fun catalogProvider(catalog: CatalogRepository): CatalogProvider =
        CatalogProvider { refresh -> if (refresh) catalog.hydrate() else catalog.ensure() }

    @Provides
    @Singleton
    fun botEngine(
        exchange: ExchangeGateway,
        store: OrderStore,
        manager: OrderManager,
        reconciler: Reconciler,
        data: LiveMarketData,
        costBasis: FillCostBasis,
        catalogProvider: CatalogProvider,
        log: BotLog,
        clock: Clock,
    ): BotEngine = BotEngine(
        live = EngineComponents(exchange, store, manager, reconciler, costBasis),
        dryRun = {
            // Set komponen baru tiap sesi dry run: order virtual tidak bocor ke sesi berikutnya.
            val dryExchange = DryRunExchange()
            val dryStore = VolatileOrderStore()
            EngineComponents(
                dryExchange, dryStore,
                OrderManager(dryExchange, dryStore, ClientOrderIds(dryStore), clock),
                Reconciler(dryExchange, dryStore, clock),
            )
        },
        data = data,
        catalogProvider = catalogProvider,
        log = log,
        clock = clock,
    )
}

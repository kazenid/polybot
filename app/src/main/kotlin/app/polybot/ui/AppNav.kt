package app.polybot.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.polybot.ui.balances.BalancesScreen
import app.polybot.ui.bot.ControlScreen
import app.polybot.ui.bot.LogsScreen
import app.polybot.ui.bot.RiskScreen
import app.polybot.ui.bot.StrategyScreen
import app.polybot.ui.dashboard.DashboardScreen
import app.polybot.ui.guide.BatteryGuideScreen
import app.polybot.ui.market.MarketDetailScreen
import app.polybot.ui.orders.OrdersScreen
import app.polybot.ui.report.TransactionsScreen
import app.polybot.ui.settings.DataScreen
import app.polybot.ui.settings.AccountScreen
import app.polybot.ui.settings.AboutScreen
import app.polybot.ui.settings.GuideScreen
import app.polybot.ui.settings.OrderTestScreen
import app.polybot.ui.settings.PollingScreen
import app.polybot.ui.settings.SettingsScreen

private const val MARKET_ROUTE = "market/{symbol}"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Dashboard", Icons.Rounded.SpaceDashboard),
    Tab("transactions", "Transaksi", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab("orders", "Order", Icons.AutoMirrored.Rounded.ListAlt),
    Tab("balances", "Saldo", Icons.Rounded.AccountBalanceWallet),
    Tab("logs", "Log", Icons.AutoMirrored.Rounded.Notes),
    Tab("settings", "Setelan", Icons.Rounded.Settings),
)

/** Layar anak di bawah Setelan: route -> judul bar atas. */
private val subScreens = mapOf(
    "settings/control" to "Kontrol Bot",
    "settings/account" to "Akun & API Key",
    "settings/order-test" to "Uji order manual",
    "settings/strategy" to "Strategi",
    "settings/risk" to "Risiko",
    "settings/data" to "Data",
    "settings/battery" to "Panduan baterai",
    "settings/polling" to "Polling & Notifikasi",
    "settings/about" to "Tentang",
    "settings/guide" to "Panduan aplikasi",
    MARKET_ROUTE to "Detail pasar",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        topBar = {
            if (route != null && route in subScreens) {
                TopAppBar(
                    title = {
                        val title = if (route == MARKET_ROUTE) backStack?.arguments?.getString("symbol") else null
                        Text(title ?: subScreens.getValue(route), style = MaterialTheme.typography.titleLarge)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route ||
                            (tab.route == "settings" && route?.startsWith("settings/") == true) ||
                            (tab.route == "dashboard" && route == MARKET_ROUTE),
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                // Tab Setelan selalu membuka daftar, bukan sub-layar terakhir.
                                restoreState = tab.route != "settings"
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, maxLines = 1) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
            composable("dashboard") { DashboardScreen(onOpenMarket = { nav.navigate("market/$it") }) }
            composable(MARKET_ROUTE) { MarketDetailScreen() }
            composable("transactions") { TransactionsScreen() }
            composable("orders") { OrdersScreen() }
            composable("balances") { BalancesScreen() }
            composable("logs") { LogsScreen() }
            composable("settings") { SettingsScreen(onOpen = { nav.navigate(it) }) }
            composable("settings/control") { ControlScreen() }
            composable("settings/account") { AccountScreen() }
            composable("settings/order-test") { OrderTestScreen() }
            composable("settings/strategy") { StrategyScreen() }
            composable("settings/risk") { RiskScreen() }
            composable("settings/data") { DataScreen() }
            composable("settings/battery") { BatteryGuideScreen() }
            composable("settings/polling") { PollingScreen() }
            composable("settings/about") { AboutScreen() }
            composable("settings/guide") { GuideScreen() }
        }
    }
}

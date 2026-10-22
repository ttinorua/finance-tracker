package com.financetracker.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import com.financetracker.app.ui.screens.receipts.ReceiptFiles
import com.financetracker.app.ui.screens.receipts.ReceiptImportScreen
import com.financetracker.app.ui.screens.receipts.ReceiptImportViewModel
import com.financetracker.app.ui.screens.receipts.ReceiptNav
import com.financetracker.app.ui.screens.receipts.ReceiptScreen
import com.financetracker.app.ui.screens.receipts.ReceiptViewModel
import com.financetracker.app.ui.screens.receipts.ShoppingTab
import com.financetracker.app.ui.screens.receipts.ShoppingViewModel
import com.financetracker.app.ui.screens.receipts.StoreConnectScreen
import com.financetracker.app.ui.screens.receipts.StoreConnectViewModel
import com.financetracker.app.ui.screens.receipts.StoreConnectionsScreen
import com.financetracker.app.data.stores.Store
import com.financetracker.app.ui.screens.household.HouseholdSettingsScreen
import com.financetracker.app.ui.screens.household.HouseholdTransactionsScreen
import com.financetracker.app.ui.screens.household.HouseholdScreen
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import com.financetracker.app.ui.theme.LocalAppPalette
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.financetracker.app.data.backup.AutoBackupSettings
import com.financetracker.app.data.bank.SupportedBanks
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.enablebanking.EnableBankingService
import com.financetracker.app.ui.navigation.FloatingNavBar
import com.financetracker.app.ui.navigation.Screen
import com.financetracker.app.ui.screens.ai.AskAiScreen
import com.financetracker.app.ui.screens.ai.AskAiViewModel
import com.financetracker.app.ui.screens.dashboard.DashboardScreen
import com.financetracker.app.ui.screens.dashboard.DashboardTransactionsScreen
import com.financetracker.app.ui.screens.dashboard.DashboardTransactionsViewModel
import com.financetracker.app.ui.screens.dashboard.DashboardViewModel
import com.financetracker.app.ui.screens.importexport.ImportExportViewModel
import com.financetracker.app.ui.screens.overview.BreakdownScreen
import com.financetracker.app.ui.screens.overview.BreakdownViewModel
import com.financetracker.app.ui.screens.overview.CategoryOverviewScreen
import com.financetracker.app.ui.screens.overview.CategoryOverviewViewModel
import com.financetracker.app.ui.screens.overview.GroupTransactionsScreen
import com.financetracker.app.ui.screens.overview.GroupTransactionsViewModel
import com.financetracker.app.ui.screens.overview.TransactionsDrillDown
import com.financetracker.app.ui.screens.settings.EnableBankingViewModel
import com.financetracker.app.ui.screens.settings.SettingsScreen
import com.financetracker.app.ui.screens.settings.SettingsViewModel
import com.financetracker.app.ui.screens.transactions.TransactionsScreen
import com.financetracker.app.ui.screens.transactions.TransactionsViewModel
import com.financetracker.app.ui.screens.trends.TrendsScreen
import com.financetracker.app.ui.screens.trends.TrendsViewModel
import com.financetracker.app.ui.theme.PersonalFinanceTheme
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        // A shared receipt; not again when the screen is merely recreated (e.g. rotated).
        if (savedInstanceState == null) handleSharedReceipt(intent)

        val repository = (application as FinanceApp).repository

        setContent {
            PersonalFinanceTheme {
                val navController = rememberNavController()
                // Status and navigation bar icons follow the theme (light icons on dark themes).
                val darkTheme = LocalAppPalette.current.isDark
                LaunchedEffect(darkTheme) {
                    val style = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }

                // Receipts shared from other apps, and taps on transactions that have a receipt.
                LaunchedEffect(Unit) {
                    launch { repository.observeTransactionIdsWithReceipt().collect { ReceiptNav.setTransactionsWithReceipt(it) } }
                    ReceiptNav.events.collect { event ->
                        when (event) {
                            is ReceiptNav.Event.Import -> {
                                ReceiptNav.pendingImport = event
                                navController.navigate("receipt_import") {
                                    popUpTo("receipt_import") { inclusive = true }
                                }
                            }
                            is ReceiptNav.Event.OpenForTransaction ->
                                repository.getReceiptForTransaction(event.transactionId)?.let { navController.navigate("receipt/${it.id}") }
                        }
                    }
                }

                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                Scaffold(
                    bottomBar = {
                        // The chat has its own input along the bottom, so the bar steps aside there.
                        if (currentDestination?.route != "ask_ai") {
                            FloatingNavBar(
                                items = Screen.bottomNavItems,
                                isSelected = { screen -> currentDestination?.hierarchy?.any { it.route == screen.route } == true },
                                onSelect = { screen ->
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onOpenAi = { navController.navigate("ask_ai") { launchSingleTop = true } }
                            )
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        // The bottom space is taken here once, so screens (and the chat's keyboard
                        // handling) don't add the system navigation bar's height again.
                        modifier = Modifier
                            .padding(bottom = padding.calculateBottomPadding())
                            .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
                        // Screens are see-through (the theme's background is drawn behind them), so
                        // switch instantly rather than cross-fading two screens on top of each other.
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable(Screen.Dashboard.route) {
                            val vm: DashboardViewModel = viewModel(
                                factory = ViewModelFactory { DashboardViewModel(repository, applicationContext) }
                            )
                            DashboardScreen(
                                vm,
                                onOpenAskAi = { navController.navigate("ask_ai") },
                                onOpenTrends = { navController.navigate("trends") },
                                onOpenHousehold = { navController.navigate("household") },
                                onOpenBreakdown = { drillDown, upcoming -> navController.navigate(drillDown.drillRoute(upcoming)) },
                                onOpenTransactions = { type, categoryId, label, periodOption, customRange, includeAnticipated, accountId, uncategorizedOnly ->
                                    navController.navigate(
                                        dashboardTransactionsRoute(
                                            type, categoryId, label, periodOption, customRange, includeAnticipated, accountId, uncategorizedOnly
                                        )
                                    )
                                }
                            )
                        }
                        composable(
                            route = "dashboard_transactions/{type}/{categoryId}/{label}/{periodOption}/{from}/{to}/{includeAnticipated}/{accountId}/{uncategorizedOnly}",
                            arguments = listOf(
                                navArgument("type") { type = NavType.StringType },
                                navArgument("categoryId") { type = NavType.LongType },
                                navArgument("label") { type = NavType.StringType },
                                navArgument("periodOption") { type = NavType.StringType },
                                navArgument("from") { type = NavType.LongType },
                                navArgument("to") { type = NavType.LongType },
                                navArgument("includeAnticipated") { type = NavType.BoolType },
                                navArgument("accountId") { type = NavType.LongType },
                                navArgument("uncategorizedOnly") { type = NavType.BoolType }
                            )
                        ) { backStackEntry ->
                            val args = backStackEntry.arguments!!
                            val txType = args.getString("type")!!.let { if (it == "NONE") null else TransactionType.valueOf(it) }
                            val categoryId = args.getLong("categoryId").takeIf { it >= 0 }
                            val label = Uri.decode(args.getString("label")!!)
                            val periodOption = PeriodOption.valueOf(args.getString("periodOption")!!)
                            val from = args.getLong("from")
                            val to = args.getLong("to")
                            val includeAnticipated = args.getBoolean("includeAnticipated")
                            val accountId = args.getLong("accountId").takeIf { it >= 0 }
                            val uncategorizedOnly = args.getBoolean("uncategorizedOnly")
                            val customRange = if (periodOption == PeriodOption.CUSTOM && from >= 0 && to >= 0) {
                                from to to
                            } else {
                                null
                            }
                            val vm: DashboardTransactionsViewModel = viewModel(
                                factory = ViewModelFactory {
                                    DashboardTransactionsViewModel(
                                        repository,
                                        txType,
                                        categoryId,
                                        label,
                                        periodOption,
                                        customRange,
                                        includeAnticipated,
                                        accountId,
                                        uncategorizedOnly
                                    )
                                }
                            )
                            DashboardTransactionsScreen(vm, onClose = { navController.popBackStack() })
                        }
                        composable("trends") {
                            val vm: TrendsViewModel = viewModel(
                                factory = ViewModelFactory { TrendsViewModel(repository) }
                            )
                            TrendsScreen(
                                vm,
                                onBack = { navController.popBackStack() },
                                onOpenTransactions = { drillDown -> navController.navigate(drillDown.drillRoute()) },
                                onOpenAskAi = { navController.navigate("ask_ai") }
                            )
                        }
                        composable("household") {
                            val categories by repository.observeCategories().collectAsState(initial = emptyList())
                            HouseholdScreen(
                                mainCategories = categories.filter { it.type == TransactionType.EXPENSE }.map { it.mainCategory }.distinct(),
                                onBack = { navController.popBackStack() },
                                onOpenSettings = { navController.navigate("household_settings") },
                                onOpenTransactions = { main -> navController.navigate("household_tx/${Uri.encode(main ?: "*")}") }
                            )
                        }
                        composable("household_tx/{main}", arguments = listOf(navArgument("main") { type = NavType.StringType })) { entry ->
                            val main = Uri.decode(entry.arguments!!.getString("main")!!).takeIf { it != "*" }
                            HouseholdTransactionsScreen(main, onBack = { navController.popBackStack() })
                        }
                        composable("household_settings") {
                            val accounts by repository.observeAccounts().collectAsState(initial = emptyList())
                            HouseholdSettingsScreen(accounts, onBack = { navController.popBackStack() })
                        }
                        composable("ask_ai") {
                            val vm: AskAiViewModel = viewModel(
                                factory = ViewModelFactory { AskAiViewModel(repository) }
                            )
                            AskAiScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable(Screen.Transactions.route) {
                            val vm: TransactionsViewModel = viewModel(
                                factory = ViewModelFactory { TransactionsViewModel(repository) }
                            )
                            TransactionsScreen(vm, onOpenAskAi = { navController.navigate("ask_ai") })
                        }
                        composable(Screen.Overview.route) {
                            val vm: CategoryOverviewViewModel = viewModel(
                                factory = ViewModelFactory { CategoryOverviewViewModel(repository) }
                            )
                            CategoryOverviewScreen(
                                vm,
                                onOpenTransactions = { drillDown -> navController.navigate(drillDown.drillRoute()) },
                                onOpenTrends = { navController.navigate("trends") },
                                onOpenAskAi = { navController.navigate("ask_ai") },
                                shopping = { modifier, categoryFilter ->
                                    val shoppingVm: ShoppingViewModel = viewModel(factory = ViewModelFactory { ShoppingViewModel(repository) })
                                    ShoppingTab(
                                        shoppingVm,
                                        onOpenReceipt = { navController.navigate("receipt/$it") },
                                        modifier = modifier,
                                        onOpenStores = { navController.navigate("store_connections") },
                                        categoryFilter = categoryFilter
                                    )
                                }
                            )
                        }
                        composable("store_connections") {
                            StoreConnectionsScreen(
                                repository = repository,
                                onBack = { navController.popBackStack() },
                                onConnect = { store, another -> navController.navigate("store_connect/${store.id}/$another") }
                            )
                        }
                        composable(
                            "store_connect/{store}/{another}",
                            arguments = listOf(navArgument("store") { type = NavType.StringType }, navArgument("another") { type = NavType.BoolType })
                        ) { entry ->
                            val args = entry.arguments!!
                            val store = Store.byId(args.getString("store")!!) ?: Store.LIDL
                            val another = args.getBoolean("another")
                            val vm: StoreConnectViewModel = viewModel(factory = ViewModelFactory { StoreConnectViewModel(repository, applicationContext, store, another) })
                            StoreConnectScreen(vm, another = another, onClose = { navController.popBackStack() })
                        }
                        composable("receipt_import") {
                            val pending = remember { ReceiptNav.pendingImport.also { ReceiptNav.pendingImport = null } }
                            val vm: ReceiptImportViewModel = viewModel(
                                factory = ViewModelFactory {
                                    ReceiptImportViewModel(repository, applicationContext, pending?.files.orEmpty(), pending?.source ?: "share")
                                }
                            )
                            ReceiptImportScreen(
                                vm,
                                onClose = { navController.popBackStack() },
                                onSaved = { id ->
                                    navController.navigate("receipt/$id") { popUpTo("receipt_import") { inclusive = true } }
                                }
                            )
                        }
                        composable("receipt/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments!!.getLong("id")
                            val vm: ReceiptViewModel = viewModel(factory = ViewModelFactory { ReceiptViewModel(repository, id) })
                            ReceiptScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = TransactionsDrillDown.BREAKDOWN_ROUTE,
                            arguments = TransactionsDrillDown.breakdownArguments
                        ) { backStackEntry ->
                            val args = backStackEntry.arguments!!
                            val base = TransactionsDrillDown.from(args)
                            val main = TransactionsDrillDown.mainCategoryFrom(args)
                            val upcoming = args.getBoolean("upcoming")
                            val vm: BreakdownViewModel = viewModel(
                                factory = ViewModelFactory { BreakdownViewModel(repository, base, main, upcoming) }
                            )
                            BreakdownScreen(
                                vm,
                                base = base,
                                mainCategory = main,
                                onBack = { navController.popBackStack() },
                                onOpenMainCategories = {
                                    // Back up one step when that's where we came from; otherwise
                                    // (opened straight on a main category) open the main categories.
                                    if (navController.previousBackStackEntry?.destination?.route == TransactionsDrillDown.BREAKDOWN_ROUTE) {
                                        navController.popBackStack()
                                    } else if (main != null) {
                                        navController.navigate(base.acrossMainCategories(main).breakdownRoute(null, upcoming))
                                    }
                                },
                                onOpenMainCategory = { navController.navigate(base.breakdownRoute(it, upcoming)) },
                                onOpenTransactions = { navController.navigate(it.route()) },
                                onOpenUpcoming = {
                                    navController.navigate(
                                        dashboardTransactionsRoute(
                                            TransactionType.EXPENSE, null, "Upcoming and paid", base.periodOption, base.customRange,
                                            includeAnticipated = true, accountId = base.accountId, uncategorizedOnly = false
                                        )
                                    )
                                }
                            )
                        }
                        composable(
                            route = TransactionsDrillDown.ROUTE,
                            arguments = TransactionsDrillDown.arguments
                        ) { backStackEntry ->
                            val drillDown = TransactionsDrillDown.from(backStackEntry.arguments!!)
                            val vm: GroupTransactionsViewModel = viewModel(
                                factory = ViewModelFactory {
                                    GroupTransactionsViewModel(
                                        repository,
                                        drillDown.groupBy,
                                        drillDown.key,
                                        drillDown.periodOption,
                                        drillDown.customRange,
                                        drillDown.accountId,
                                        drillDown.type,
                                        drillDown.categoryFilter,
                                        drillDown.title
                                    )
                                }
                            )
                            GroupTransactionsScreen(vm, onClose = { navController.popBackStack() })
                        }
                        composable(Screen.Settings.route) {
                            val vm: SettingsViewModel = viewModel(
                                factory = ViewModelFactory { SettingsViewModel(repository, applicationContext) }
                            )
                            val importExportVm: ImportExportViewModel = viewModel(
                                factory = ViewModelFactory { ImportExportViewModel(repository, applicationContext) }
                            )
                            val enableBankingVm: EnableBankingViewModel = viewModel(
                                factory = ViewModelFactory { EnableBankingViewModel(repository, applicationContext) }
                            )
                            SettingsScreen(vm, importExportVm, enableBankingVm)
                        }
                    }
                }
            }
        }
    }

    private fun dashboardTransactionsRoute(
        type: TransactionType?,
        categoryId: Long?,
        label: String,
        periodOption: PeriodOption,
        customRange: Pair<Long, Long>?,
        includeAnticipated: Boolean,
        accountId: Long?,
        uncategorizedOnly: Boolean
    ): String = "dashboard_transactions/${type?.name ?: "NONE"}/${categoryId ?: -1L}/${Uri.encode(label)}/" +
        "${periodOption.name}/${customRange?.first ?: -1L}/${customRange?.second ?: -1L}/$includeAnticipated/" +
        "${accountId ?: -1L}/$uncategorizedOnly"

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        handleSharedReceipt(intent)
    }

    /** Handles the Enable Banking OAuth redirect (financetracker://enablebanking-callback?...),
     * which the browser opens after the user finishes MitID login on our GitHub Pages bridge
     * page. Any other intent (e.g. the normal launcher intent) is ignored. */
    private fun handleIncomingIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "financetracker" && data.host == "onedrive-auth") {
            handleOneDriveSignIn(data)
            return
        }
        if (data.scheme != "financetracker" || data.host != "enablebanking-callback") return

        val error = data.getQueryParameter("error")
        if (error != null) {
            val description = data.getQueryParameter("error_description") ?: error
            Toast.makeText(this, "Bank connection failed: $description", Toast.LENGTH_LONG).show()
            return
        }

        val code = data.getQueryParameter("code") ?: return
        val state = data.getQueryParameter("state")
        lifecycleScope.launch {
            EnableBankingService.completeAuth(code, state)
                .onSuccess { accounts ->
                    val bankName = accounts.firstOrNull()?.bankId?.let { SupportedBanks.byId(it).displayName } ?: "bank"
                    Toast.makeText(
                        this@MainActivity,
                        "Connected ${accounts.size} $bankName account(s)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { e ->
                    Toast.makeText(this@MainActivity, "Bank connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    /** A receipt (image or PDF) shared from a store app: copied in straight away, since the
     * permission to read it doesn't last, then read on the import screen. */
    private fun handleSharedReceipt(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Share the receipt as an image or PDF.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val files = ReceiptFiles.copyIn(applicationContext, uris)
            if (files.isEmpty()) {
                Toast.makeText(this@MainActivity, "Couldn't open the shared receipt.", Toast.LENGTH_LONG).show()
            } else {
                ReceiptNav.import(files, "share")
            }
        }
    }

    /** Finishes turning on the automatic backup to OneDrive once Microsoft's sign-in page sends
     * the browser back here (financetracker://onedrive-auth?code=...&state=...). */
    private fun handleOneDriveSignIn(data: Uri) {
        val code = data.getQueryParameter("code")
        if (code == null) {
            val reason = data.getQueryParameter("error_description") ?: data.getQueryParameter("error") ?: "cancelled"
            Toast.makeText(this, "OneDrive sign-in failed: $reason", Toast.LENGTH_LONG).show()
            return
        }
        val state = data.getQueryParameter("state")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AutoBackupSettings.completeOneDrive(applicationContext, code, state) }
            }
            val message = result.fold(
                onSuccess = { account -> "Weekly backup to OneDrive is on" + (account?.let { " ($it)" } ?: "") + "." },
                onFailure = { it.message ?: "OneDrive sign-in failed." }
            )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}

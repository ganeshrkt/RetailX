package com.example.retailx.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.retailx.core.datastore.AppPreferences
import com.example.retailx.core.designsystem.*
import com.example.retailx.core.model.PermissionCode
import com.example.retailx.core.model.RuntimeMode
import com.example.retailx.core.model.ThemeMode
import com.example.retailx.core.model.User
import com.example.retailx.feature.accounts.AccountsScreen
import com.example.retailx.feature.dashboard.DashboardScreen
import com.example.retailx.feature.inventory.InventoryScreen
import com.example.retailx.feature.login.LoginScreen
import com.example.retailx.feature.onboarding.OnboardingScreen
import com.example.retailx.feature.parties.PartiesScreen
import com.example.retailx.feature.pos.PosController
import com.example.retailx.feature.pos.PosScreen
import com.example.retailx.feature.products.ProductsScreen
import com.example.retailx.feature.purchases.PurchasesScreen
import com.example.retailx.feature.reports.ReportsScreen
import com.example.retailx.feature.sales.SalesScreen
import com.example.retailx.feature.settings.SettingsScreen
import com.example.retailx.feature.staff.StaffScreen
import com.example.retailx.lan.client.LanConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val requiredAnyPermissions: Set<PermissionCode>
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard, setOf(PermissionCode.REPORTS_VIEW)),
    POS("pos", "POS", Icons.Filled.PointOfSale, setOf(PermissionCode.POS_BILLING)),
    SALES("sales", "Sales", Icons.Filled.ReceiptLong, setOf(PermissionCode.SALES_VIEW)),
    PRODUCTS("products", "Products", Icons.Filled.Inventory2, setOf(PermissionCode.CATALOG_MANAGE)),
    INVENTORY("inventory", "Inventory", Icons.Filled.Warehouse, setOf(PermissionCode.INVENTORY_ADJUST)),
    PURCHASES("purchases", "Purchases", Icons.Filled.LocalShipping, setOf(PermissionCode.PURCHASE_MANAGE)),
    PARTIES("parties", "Parties", Icons.Filled.Groups, setOf(PermissionCode.PARTIES_MANAGE)),
    ACCOUNTS("accounts", "Accounts", Icons.Filled.AccountBalanceWallet, setOf(PermissionCode.ACCOUNTS_MANAGE, PermissionCode.CASH_REGISTER_MANAGE)),
    REPORTS("reports", "Reports", Icons.Filled.BarChart, setOf(PermissionCode.REPORTS_VIEW)),
    STAFF("staff", "Staff", Icons.Filled.Badge, setOf(PermissionCode.STAFF_MANAGE)),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, emptySet());

    fun isAllowedFor(user: User?, runtimeMode: RuntimeMode): Boolean {
        if (this == SETTINGS) return true
        if (runtimeMode == RuntimeMode.LAN_CLIENT) {
            return this == POS || this == SETTINGS
        }
        if (user == null) return false
        if (requiredAnyPermissions.isEmpty()) return true
        return requiredAnyPermissions.any { user.permissions.contains(it.code) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetailXAppRoot(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val prefs by container.preferencesStore.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
    val session by container.sessionManager.session.collectAsStateWithLifecycle()
    val store by container.storeAndAuthRepository.observeStoreSettings().collectAsStateWithLifecycle(initialValue = null)
    val lanHostStatus by container.lanHostServer.status.collectAsStateWithLifecycle()
    val lanClientState by container.lanApiClient.connectionState.collectAsStateWithLifecycle()

    var initialStoreChecked by remember { mutableStateOf(false) }
    var dbResetNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(dbResetNonce) {
        container.storeAndAuthRepository.getStoreSettings()
        initialStoreChecked = true
    }

    // Periodic session inactivity check
    LaunchedEffect(session?.loginAt, prefs.sessionTimeoutMinutes) {
        while (session != null) {
            delay(30_000L)
            container.sessionManager.checkTimeout(prefs.sessionTimeoutMinutes)
        }
    }

    RetailXTheme(themeMode = prefs.themeMode) {
        if (!initialStoreChecked) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@RetailXTheme
        }

        val isStoreConfigured = store?.isSetupComplete == true || prefs.runtimeMode == RuntimeMode.LAN_CLIENT

        when {
            !isStoreConfigured -> {
                OnboardingScreen(
                    storeRepository = container.storeAndAuthRepository,
                    preferencesStore = container.preferencesStore,
                    lanApiClient = container.lanApiClient,
                    onSetupComplete = { adminUser ->
                        container.sessionManager.setSession(adminUser, viaLan = false)
                    },
                    onLanPairedComplete = {
                        container.sessionManager.logout()
                    }
                )
            }

            session == null -> {
                LoginScreen(
                    storeSettings = store,
                    appPrefs = prefs,
                    authRepository = container.storeAndAuthRepository,
                    lanApiClient = container.lanApiClient,
                    onLoginSuccess = { user, viaLan ->
                        container.sessionManager.setSession(user, viaLan = viaLan)
                    },
                    onDisconnectLanClient = {
                        scope.launch {
                            container.preferencesStore.clearLanClientPairing()
                            container.lanApiClient.setStandalone()
                        }
                    }
                )
            }

            else -> {
                val activeUser = session!!.user
                val allowedRoutes = remember(activeUser, prefs.runtimeMode) {
                    AppRoute.entries.filter { it.isAllowedFor(activeUser, prefs.runtimeMode) }
                }
                val startDestination = remember(allowedRoutes) {
                    when {
                        allowedRoutes.contains(AppRoute.POS) && !allowedRoutes.contains(AppRoute.DASHBOARD) -> AppRoute.POS.route
                        allowedRoutes.contains(AppRoute.DASHBOARD) -> AppRoute.DASHBOARD.route
                        else -> allowedRoutes.firstOrNull()?.route ?: AppRoute.SETTINGS.route
                    }
                }
                val posController = remember(activeUser.id, dbResetNonce) {
                    PosController(container) { container.sessionManager.session.value?.user }
                }
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: startDestination
                var showMoreSheet by remember { mutableStateOf(false) }

                val navigateTo: (AppRoute) -> Unit = { dest ->
                    container.sessionManager.touchActivity()
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    container.sessionManager.touchActivity()
                                }
                            }
                        }
                ) {
                    val isWide = maxWidth >= 600.dp

                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = store?.name ?: "RetailX POS",
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val (badgeText, badgeTone) = when {
                                            prefs.runtimeMode == RuntimeMode.LAN_CLIENT ->
                                                lanClientState.label to if (lanClientState == LanConnectionState.CONNECTED) BadgeTone.INFO else BadgeTone.ERROR
                                            lanHostStatus.isRunning ->
                                                "LAN Host :${lanHostStatus.port}" to BadgeTone.SUCCESS
                                            else -> "Offline" to BadgeTone.NEUTRAL
                                        }
                                        StatusBadge(text = badgeText, tone = badgeTone)
                                    }
                                },
                                actions = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = "${activeUser.fullName} (${activeUser.roleName})",
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val nextTheme = when (prefs.themeMode) {
                                                    ThemeMode.SYSTEM -> ThemeMode.LIGHT
                                                    ThemeMode.LIGHT -> ThemeMode.DARK
                                                    ThemeMode.DARK -> ThemeMode.SYSTEM
                                                }
                                                scope.launch { container.preferencesStore.setThemeMode(nextTheme) }
                                            },
                                            modifier = Modifier.testTag("topbar_theme_toggle")
                                        ) {
                                            val icon = when (prefs.themeMode) {
                                                ThemeMode.DARK -> Icons.Filled.DarkMode
                                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                                            }
                                            Icon(icon, contentDescription = "Switch Theme (${prefs.themeMode.label})")
                                        }
                                        IconButton(
                                            onClick = { container.sessionManager.logout() },
                                            modifier = Modifier.testTag("topbar_logout_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out")
                                        }
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            if (!isWide) {
                                val primaryMobile = allowedRoutes.take(4)
                                val hasOverflow = allowedRoutes.size > 4
                                NavigationBar {
                                    primaryMobile.forEach { dest ->
                                        NavigationBarItem(
                                            selected = currentRoute == dest.route,
                                            onClick = { navigateTo(dest) },
                                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                                            label = { Text(dest.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            modifier = Modifier.testTag("nav_${dest.route}")
                                        )
                                    }
                                    if (hasOverflow) {
                                        val isOverflowActive = allowedRoutes.drop(4).any { it.route == currentRoute }
                                        NavigationBarItem(
                                            selected = isOverflowActive,
                                            onClick = { showMoreSheet = true },
                                            icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "More Modules") },
                                            label = { Text("More") },
                                            modifier = Modifier.testTag("nav_more")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (isWide) {
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(allowedRoutes, key = { it.route }) { dest ->
                                            NavigationRailItem(
                                                selected = currentRoute == dest.route,
                                                onClick = { navigateTo(dest) },
                                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                                label = { Text(dest.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                modifier = Modifier.testTag("nav_${dest.route}")
                                            )
                                        }
                                    }
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                NavHost(
                                    navController = navController,
                                    startDestination = startDestination
                                ) {
                                    composable(AppRoute.DASHBOARD.route) {
                                        DashboardScreen(
                                            container = container,
                                            onNavigateToPos = { navigateTo(AppRoute.POS) },
                                            onNavigateToInventory = { navigateTo(AppRoute.INVENTORY) },
                                            onNavigateToSales = { navigateTo(AppRoute.SALES) }
                                        )
                                    }
                                    composable(AppRoute.POS.route) {
                                        PosScreen(
                                            controller = posController,
                                            currentUser = activeUser,
                                            pdfGenerator = container.pdfGenerator
                                        )
                                    }
                                    composable(AppRoute.SALES.route) {
                                        SalesScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.PRODUCTS.route) {
                                        ProductsScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.INVENTORY.route) {
                                        InventoryScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.PURCHASES.route) {
                                        PurchasesScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.PARTIES.route) {
                                        PartiesScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.ACCOUNTS.route) {
                                        AccountsScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.REPORTS.route) {
                                        ReportsScreen(container = container)
                                    }
                                    composable(AppRoute.STAFF.route) {
                                        StaffScreen(container = container, currentUser = activeUser)
                                    }
                                    composable(AppRoute.SETTINGS.route) {
                                        SettingsScreen(
                                            container = container,
                                            currentUser = activeUser,
                                            onSessionRestoredOrDisconnected = {
                                                dbResetNonce++
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showMoreSheet) {
                        ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("All Modules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                                allowedRoutes.drop(4).forEach { dest ->
                                    ListItem(
                                        headlineContent = { Text(dest.label) },
                                        leadingContent = { Icon(dest.icon, contentDescription = null) },
                                        modifier = Modifier
                                            .clickable {
                                                showMoreSheet = false
                                                navigateTo(dest)
                                            }
                                            .testTag("nav_sheet_${dest.route}")
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

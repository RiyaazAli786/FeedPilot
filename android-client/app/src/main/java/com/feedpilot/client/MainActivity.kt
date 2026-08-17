package com.feedpilot.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.feedpilot.client.data.repository.ThemeMode
import com.feedpilot.client.feature.accounts.AddAccountScreen
import com.feedpilot.client.feature.connect.VerifyCodeScreen
import com.feedpilot.client.feature.followers.FollowersScreen
import com.feedpilot.client.feature.handles.HandleScreen
import com.feedpilot.client.feature.history.HistoryScreen
import com.feedpilot.client.feature.leaderboard.LeaderboardScreen
import com.feedpilot.client.feature.login.AccountLoginScreen
import com.feedpilot.client.feature.more.MoreScreen
import com.feedpilot.client.feature.orders.OrdersScreen
import com.feedpilot.client.feature.posts.PostsScreen
import com.feedpilot.client.feature.settings.SettingsScreen
import com.feedpilot.client.feature.splash.SplashScreen
import com.feedpilot.client.feature.tasks.TasksScreen
import com.feedpilot.client.feature.upgrade.UpgradeScreen
import com.feedpilot.client.feature.wallet.TransferScreen
import com.feedpilot.client.feature.wallet.WithdrawScreen
import com.feedpilot.client.ui.navigation.BottomTab
import com.feedpilot.client.ui.navigation.Routes
import com.feedpilot.client.ui.theme.AppTheme
import com.feedpilot.client.ui.theme.FeedPilotTheme
import dagger.hilt.android.AndroidEntryPoint

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.feedpilot.client.feature.start.StartScreen
import com.feedpilot.client.feature.guard.AppGuardBannerDialog
import com.feedpilot.client.feature.updates.LaunchUpdateDialog
import kotlin.math.abs
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* runner works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent { FeedPilotRoot() }
    }

    /** The task runner posts an ongoing notification; on Android 13+ that needs user consent. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun FeedPilotRoot(rootViewModel: RootViewModel = hiltViewModel()) {
    val themeMode by rootViewModel.themeMode.collectAsStateWithLifecycle()

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val gate by rootViewModel.gate.collectAsStateWithLifecycle()
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()

    FeedPilotTheme(darkTheme = darkTheme) {
        val context = LocalContext.current
        val background = MaterialTheme.colorScheme.background
        LaunchedEffect(darkTheme, background) {
            val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        LaunchUpdateDialog()
        AppGuardBannerDialog()
        when (gate) {
            AuthGate.Ready -> MainScreenLayout(hasAccounts = hasAccounts)
            AuthGate.Checking -> AuthCheckingScreen()
            AuthGate.Offline -> AuthOfflineScreen(onRetry = rootViewModel::authenticate)
        }
    }
}

@Composable
private fun AuthCheckingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
    ) {
        // Middle Section: Glassy Card + App Emblem + App Name in Premium Typography + Glassy Ring Loader
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Glassy Container with 3D App Icon
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    AppTheme.brand.orange.copy(alpha = 0.4f)
                ),
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier.padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "FeedPilot Logo",
                        modifier = Modifier.size(80.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // App Name in Premium Font & Typography
            Text(
                text = "FeedPilot",
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(24.dp))

            // Glassy Loader Ring Container
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.brand.orange.copy(alpha = 0.35f)),
                shadowElevation = 3.dp
            ) {
                Box(
                    modifier = Modifier.padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = AppTheme.brand.orange,
                        strokeWidth = 3.5.dp
                    )
                }
            }
        }

        // Bottom Section: Printed Version Badge (Red Box Location)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AuthOfflineScreen(onRetry: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            onRetry()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("FeedPilot is opening", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Server connection is unavailable right now. You can retry, or the app will reconnect automatically.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.brand.orange)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 32.dp, vertical = 12.dp)
            ) {
                Text("Retry", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black)
            }
        }
    }
}

/** Screens that are part of linking an account, so an empty account list must not redirect off them. */
private val LOGIN_ROUTES = setOf(
    Routes.SPLASH,
    Routes.START,
    Routes.ADD_ACCOUNT,
    Routes.ACCOUNT_LOGIN,
    Routes.WEB_LOGIN,
    Routes.VERIFY_CODE
)

@Composable
private fun MainScreenLayout(hasAccounts: Boolean?) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val onTab = currentRoute?.hierarchy?.any { dest ->
        BottomTab.entries.any { it.route == dest.route }
    } == true

    LaunchedEffect(hasAccounts, currentRoute?.route) {
        val route = currentRoute?.route ?: return@LaunchedEffect
        when {
            // Only a list that has been read and is empty means "no account". While it is still
            // null nothing is known yet, and redirecting on that threw signed-in users out.
            hasAccounts == false && route !in LOGIN_ROUTES ->
                navController.navigate(Routes.START) { popUpTo(0) { inclusive = true } }

            // The account can also show up after the splash already handed off to Start — the
            // rows are read off disk asynchronously. Move on instead of stranding the user on a
            // login screen for an account that is linked.
            hasAccounts == true && route == Routes.START ->
                navController.navigate(Routes.TASKS) { popUpTo(0) { inclusive = true } }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .weight(1f)
                .swipeBetweenBottomTabs(navController, currentRoute?.route)
        ) {
            NavHost(navController = navController, startDestination = Routes.SPLASH) {
                composable(Routes.SPLASH) {
                    var splashComplete by remember { mutableStateOf(false) }

                    // Hold the splash until the account list is actually known, otherwise the
                    // hand-off races the disk read and sends a signed-in user to Start. If the
                    // read stalls, fall through to Start rather than sitting here forever — the
                    // effect re-runs the moment the real value lands.
                    LaunchedEffect(splashComplete, hasAccounts) {
                        if (!splashComplete) return@LaunchedEffect
                        if (hasAccounts == null) {
                            delay(1_500)
                            navController.navigate(Routes.START) {
                                popUpTo(Routes.SPLASH) { inclusive = true }
                            }
                            return@LaunchedEffect
                        }
                        val target = if (hasAccounts == true) Routes.TASKS else Routes.START
                        navController.navigate(target) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }

                    SplashScreen(onSplashComplete = { splashComplete = true })
                }
                composable(Routes.START) {
                    StartScreen(
                        onNavigateToLogin = {
                            navController.navigate(Routes.ADD_ACCOUNT)
                        }
                    )
                }
                composable(Routes.TASKS) {
                    TasksScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onUpgrade = { navController.navigate(Routes.UPGRADE) },
                        onAddAccount = { navController.navigate(Routes.ADD_ACCOUNT) },
                        onLeaderboard = { navController.navigate(Routes.LEADERBOARD) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) },
                        onOpenSession = { accountId -> navController.navigate(Routes.sessionViewer(accountId)) }
                    )
                }
                composable(
                    Routes.SESSION_VIEWER,
                    arguments = listOf(androidx.navigation.navArgument("accountId") {
                        type = androidx.navigation.NavType.StringType
                    })
                ) { backStackEntry ->
                    val accountId = backStackEntry.arguments?.getString("accountId").orEmpty()
                    com.feedpilot.client.feature.accounts.SessionViewerScreen(
                        accountId = accountId,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.FOLLOWERS) {
                    FollowersScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) }
                    )
                }
                composable(Routes.POSTS) {
                    PostsScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) }
                    )
                }
                composable(Routes.HANDLES) {
                    HandleScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) }
                    )
                }
                composable(Routes.ORDERS) {
                    OrdersScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onHistory = { navController.navigate(Routes.HISTORY) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) }
                    )
                }
                composable(Routes.MORE) {
                    MoreScreen(
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onWithdraw = { navController.navigate(Routes.WITHDRAW) },
                        onReferral = { navController.navigate(Routes.REFERRAL) }
                    )
                }
                composable(Routes.REFERRAL) {
                    com.feedpilot.client.feature.referral.ReferralScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.WITHDRAW) {
                    WithdrawScreen(
                        onBack = { navController.popBackStack() },
                        onTransfer = { navController.navigate(Routes.TRANSFER) }
                    )
                }
                composable(Routes.TRANSFER) {
                    TransferScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onLogin = { navController.navigate(Routes.ADD_ACCOUNT) },
                        onHistory = { navController.navigate(Routes.HISTORY) },
                        onTransfer = { navController.navigate(Routes.TRANSFER) },
                        onReferral = { navController.navigate(Routes.REFERRAL) }
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.LEADERBOARD) {
                    LeaderboardScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.UPGRADE) {
                    UpgradeScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.ADD_ACCOUNT) {
                    AddAccountScreen(
                        onBack = { navController.popBackStack() },
                        onWebLogin = { navController.navigate(Routes.WEB_LOGIN) },
                        onAccountAdded = {
                            navController.navigate(Routes.TASKS) {
                                popUpTo(Routes.START) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Routes.WEB_LOGIN) {
                    val addAccountViewModel: com.feedpilot.client.feature.accounts.AddAccountViewModel = hiltViewModel()
                    val addState by addAccountViewModel.state.collectAsStateWithLifecycle()

                    // Only leave the screen once the account is actually stored. Navigating as
                    // soon as the session arrived meant a failed link still landed on Tasks with
                    // no account and no explanation.
                    LaunchedEffect(addState.saved) {
                        if (addState.saved) {
                            navController.navigate(Routes.TASKS) {
                                popUpTo(Routes.START) { inclusive = true }
                            }
                        }
                    }

                    com.feedpilot.client.feature.login.WebLoginScreen(
                        onBack = { navController.popBackStack() },
                        onSessionCaptured = { username, cookies ->
                            addAccountViewModel.saveWebSession(cookies, username)
                        },
                        errorMessage = addState.error
                    )
                }
                composable(Routes.ACCOUNT_LOGIN) {
                    AccountLoginScreen(
                        onBack = { navController.popBackStack() },
                        onContinueToVerify = { navController.navigate(Routes.VERIFY_CODE) }
                    )
                }
                composable(Routes.VERIFY_CODE) {
                    VerifyCodeScreen(
                        destination = "your email",
                        onBack = { navController.popBackStack() },
                        onVerified = { navController.popBackStack() }
                    )
                }
            }
        }

        if (onTab) {
            BottomNavBar(navController, currentRoute?.route)
        }
    }
}

@Composable
private fun BottomNavBar(navController: NavHostController, currentRoute: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(AppTheme.brand.navBar)
            .navigationBarsPadding()
            .padding(vertical = 10.dp)
    ) {
        BottomTab.entries.forEach { tab ->
            val selected = currentRoute == tab.route
            val color = if (selected) AppTheme.brand.onNavSelected else AppTheme.brand.onNavUnselected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickableTab {
                        if (!selected) {
                            navController.navigate(tab.route) {
                                // The graph's *technical* start destination is Splash, but Splash
                                // is removed from the back stack (popUpTo(SPLASH){inclusive=true})
                                // the moment it hands off. Once that happens, popUpTo(startDestination)
                                // references a destination that no longer exists on the stack —
                                // Navigation Compose treats that as a no-op rather than an error, so
                                // every tab switch was just appending to the stack forever instead of
                                // trimming it. That's what made back navigation walk through the
                                // entire tab-switch history (and every pushed screen along the way)
                                // instead of just exiting once back at the home tab. Tasks is the
                                // real, permanent home for the bottom nav — it's never removed once
                                // reached, so anchoring here actually works.
                                popUpTo(Routes.TASKS) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = color)
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private fun Modifier.clickableTab(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun Modifier.swipeBetweenBottomTabs(
    navController: NavHostController,
    currentRoute: String?
): Modifier {
    val tabIndex = BottomTab.entries.indexOfFirst { it.route == currentRoute }
    if (tabIndex == -1) return this

    return this.pointerInput(currentRoute) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                totalDrag += dragAmount
                change.consume()
            },
            onDragEnd = {
                val threshold = size.width * 0.18f
                if (abs(totalDrag) <= threshold) return@detectHorizontalDragGestures

                val targetIndex = if (totalDrag < 0f) tabIndex + 1 else tabIndex - 1
                BottomTab.entries.getOrNull(targetIndex)?.let { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(Routes.TASKS) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            onDragCancel = { totalDrag = 0f }
        )
    }
}

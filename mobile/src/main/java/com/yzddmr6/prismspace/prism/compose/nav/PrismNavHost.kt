package com.yzddmr6.prismspace.prism.compose.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.controller.SystemAppSelectionClient
import com.yzddmr6.prismspace.prism.compose.screen.FilesScreen
import com.yzddmr6.prismspace.prism.compose.screen.HomeScreen
import com.yzddmr6.prismspace.prism.compose.screen.SettingsScreen
import com.yzddmr6.prismspace.prism.compose.screen.SpaceScreen
import com.yzddmr6.prismspace.prism.compose.screen.SystemAppPickerScreen
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.space.spaceUsabilityFromState
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.SystemAppPickerViewModel
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * The ONE canonical "switch to a top-level tab" operation. Every entry point (the bottom bar AND any
 * in-screen CTA like Home's "克隆一个应用") must go through this — a bare navigate(route) from a screen
 * pushes a duplicate destination outside the saved-state graph and strands the start tab (the 首页-stuck
 * bug). launchSingleTop + popUpTo(start){saveState} + restoreState is the standard tab pattern.
 */
fun NavHostController.navigateToTab(route: String) {
    // A picker page must never be captured by the tab saveState below (and restored later).
    popSystemAppPickers()
    navigate(route) {
        launchSingleTop = true
        popUpTo(graph.findStartDestination().id) { saveState = true }
        restoreState = true
    }
}

/** Waits (bounded) for a usable dual space; [userId] null = whichever managed space becomes healthy. */
private suspend fun awaitUsableSpace(context: android.content.Context, userId: Int?): Int? {
    val repo = SpaceStateRepository(context)
    repeat(SPACE_WAIT_ATTEMPTS) {
        runCatching { repo.refresh("system_app_picker_prompt") }
        val state = repo.currentState()
        val candidate = userId ?: (state as? SpaceState.Healthy)?.userId
        if (candidate != null && spaceUsabilityFromState(state, candidate) == SpaceUsability.Usable) return candidate
        delay(SPACE_WAIT_POLL_MS)
    }
    return null
}

/** A managed space PrismSpace owns right now (never a deleted or foreign profile). */
private fun isManagedSpace(userId: Int): Boolean = runCatching {
    Users.getProfilesManagedByPrism().any { it.toId() == userId }
}.getOrDefault(false)

/** Navigation before NavHost has set the graph throws; effects can run first after a recreation. */
private suspend fun awaitGraph(navController: NavHostController) {
    repeat(GRAPH_WAIT_FRAMES) {
        if (runCatching { navController.graph }.isSuccess) return
        delay(16)
    }
}

private const val GRAPH_WAIT_FRAMES = 120
private const val SPACE_WAIT_ATTEMPTS = 14
private const val SPACE_WAIT_POLL_MS = 1_500L

@Composable
fun PrismNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Hide the 4-tab nav while the Space screen is in multi-select (its batch bar owns the screen).
    val multiSelectActive by AppLaunchSignals.multiSelectActive.collectAsState()
    val showBottomBar = currentRoute in PrismRoutes.topLevel && !multiSelectActive

    val snackbarHostState = remember { SnackbarHostState() }
    val feedback by AppFeedbackBus.feedback.collectAsState()
    LaunchedEffect(feedback) {
        val f = feedback
        if (f != null) {
            if (f.message.isNotBlank()) snackbarHostState.showSnackbar(f.message)
            AppFeedbackBus.consume()
        }
    }
    LaunchedEffect(navController) {
        // "去启用" from the clone install-method selector → jump to Settings (run-mode row is at the top).
        AppLaunchSignals.openRunMode.collect {
            navController.navigateToTab(PrismRoutes.SETTINGS)
        }
    }
    LaunchedEffect(navController) {
        // Space screen「添加系统应用」→ the system-app selection page. Each request is consumed once;
        // it is dropped when its space no longer belongs to PrismSpace.
        AppLaunchSignals.openSystemAppPicker.collect { request ->
            if (isManagedSpace(request.userId)) {
                awaitGraph(navController)
                navController.openSystemAppPicker(request.userId, request.origin)
            } else DiagnosticLog.i("Prism.SysAppPicker", "picker_request_dropped u=${request.userId} reason=not_managed")
        }
    }
    val context = LocalContext.current
    LaunchedEffect(navController) {
        // A space was just created: ask once, but only while the profile still reports Pending.
        SystemAppPickerPrompt.expected(context).collect { expectation ->
            if (expectation == null) return@collect
            val userId = awaitUsableSpace(context, expectation.userId) ?: return@collect   // Keep the mark; retry next start.
            when (val status = withContext(Dispatchers.IO) { SystemAppSelectionClient.readStatus(context, userId) }) {
                is ProfileBridgeResult.Value -> if (status.value == SelectionStatus.Pending) {
                    awaitGraph(navController)
                    navController.openSystemAppPicker(userId, SYSTEM_APP_PICKER_ORIGIN_SETUP)
                } else SystemAppPickerPrompt.clear(context)
                else -> DiagnosticLog.i("Prism.SysAppPicker", "picker_not_ready u=$userId usability=bridge:${status.javaClass.simpleName}")
            }
        }
    }
    LaunchedEffect(navController) {
        // Settings「系统应用」→ switch to the Space tab; the screen itself opens the system-apps
        // view off the same nonce.
        // drop(1): the nonce already present when this (possibly recreated) host starts was handled
        // by an earlier host; replaying it would navigate again, possibly before the graph is set.
        AppLaunchSignals.openSpaceSystemApps.drop(1).collect { nonce ->
            if (nonce > 0) {
                awaitGraph(navController)
                navController.navigateToTab(PrismRoutes.SPACE)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                PrismBottomBar(
                    currentRoute = currentRoute,
                    onNavigate   = { route -> navController.navigateToTab(route) },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                val isError = feedback?.isError == true
                Snackbar(
                    snackbarData = data,
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                                     else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
                                   else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController  = navController,
                startDestination = PrismRoutes.HOME,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(PrismRoutes.HOME)     { HomeScreen(navController) }
                composable(PrismRoutes.SPACE)    { SpaceScreen() }
                composable(PrismRoutes.FILES)    { FilesScreen() }
                composable(PrismRoutes.SETTINGS) { SettingsScreen() }
                composable(
                    PrismRoutes.SYSTEM_APP_PICKER,
                    arguments = listOf(
                        navArgument(SystemAppPickerViewModel.ARG_USER_ID) { type = NavType.IntType },
                        navArgument(SystemAppPickerViewModel.ARG_ORIGIN) {
                            type = NavType.StringType
                            defaultValue = SYSTEM_APP_PICKER_ORIGIN_SPACE
                        },
                    ),
                ) { entry ->
                    val origin = entry.arguments?.getString(SystemAppPickerViewModel.ARG_ORIGIN) ?: SYSTEM_APP_PICKER_ORIGIN_SPACE
                    SystemAppPickerScreen(onFinished = { navController.exitSystemAppPicker(origin) })
                }
            }
        }
    }
}

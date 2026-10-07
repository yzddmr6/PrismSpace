package com.yzddmr6.prismspace.controller

import com.yzddmr6.prismspace.prism.compose.vm.PrismMode

/**
 * Single source of truth for which rung of PrismAppClones.cloneApp() fires.
 * [installerUsable] and [shizukuPermissionGranted] are lazy so unavailable paths do
 * not perform permission or package-manager probes until their route is reached.
 */
enum class CloneRoute { PARENT_INSTALLER, SYSTEM_ENABLE, SHIZUKU, ROOT, FILE_SYNC }

data class CloneRuntimeReadiness(val shizukuReady: Boolean, val rootReady: Boolean)

data class CloneRoutePlan(
	val route: CloneRoute,
	val requestedEnhancedRoute: CloneRoute? = null,
) {
	val usedNormalFallback: Boolean get() = route == CloneRoute.FILE_SYNC && requestedEnhancedRoute != null
}

/**
 * Four-method clone model for a non-system user app cloned from main space to dual space:
 *  - ROOT      : `pm install-existing` via su (auto, needs root granted).
 *  - SHIZUKU   : installExistingPackage via privileged worker (auto, needs Shizuku authorized).
 *  - FILE_SYNC : transfer the APK into the dual space, user installs manually (no-privilege fallback).
 * The package-scheme installer route is unreachable for user apps under Android 16 managed
 * profiles, so normal mode uses file sync and foreground user confirmation instead.
 */
/** Batch clone method: the configured one, or normal preparation when the caller forbids privileged installs. */
fun batchCloneMode(preferred: PrismMode, forceNormalPreparation: Boolean): Int = when {
	forceNormalPreparation -> PrismAppClones.MODE_INSTALLER
	preferred == PrismMode.Root -> PrismAppClones.MODE_ROOT
	preferred == PrismMode.Shizuku -> PrismAppClones.MODE_SHIZUKU
	else -> PrismAppClones.MODE_INSTALLER
}

fun planCloneRoute(
	isParentProfileTarget: Boolean,
	isSourceSystemApp: Boolean,
	mode: Int,                                  // @PrismAppClones.AppCloneMode
	installerUsable: () -> Boolean,
	readiness: CloneRuntimeReadiness,
): CloneRoutePlan = when {
	isParentProfileTarget && installerUsable() -> CloneRoutePlan(CloneRoute.PARENT_INSTALLER)
	isSourceSystemApp -> CloneRoutePlan(CloneRoute.SYSTEM_ENABLE)
	mode == PrismAppClones.MODE_ROOT && readiness.rootReady -> CloneRoutePlan(CloneRoute.ROOT, CloneRoute.ROOT)
	mode == PrismAppClones.MODE_SHIZUKU && readiness.shizukuReady -> CloneRoutePlan(CloneRoute.SHIZUKU, CloneRoute.SHIZUKU)
	mode == PrismAppClones.MODE_ROOT -> CloneRoutePlan(CloneRoute.FILE_SYNC, CloneRoute.ROOT)
	mode == PrismAppClones.MODE_SHIZUKU -> CloneRoutePlan(CloneRoute.FILE_SYNC, CloneRoute.SHIZUKU)
	else -> CloneRoutePlan(CloneRoute.FILE_SYNC)
}

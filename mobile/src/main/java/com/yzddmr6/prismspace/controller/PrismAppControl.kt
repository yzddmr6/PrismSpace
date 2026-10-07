package com.yzddmr6.prismspace.controller

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.yzddmr6.prismspace.util.Dialogs
import com.yzddmr6.prismspace.util.Apps
import com.yzddmr6.prismspace.analytics.Analytics.Param.CONTENT
import com.yzddmr6.prismspace.analytics.Analytics.Param.ITEM_CATEGORY
import com.yzddmr6.prismspace.analytics.Analytics.Param.ITEM_ID
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.EnsureAppFreeToLaunch
import com.yzddmr6.prismspace.bridge.ProfileCommand
import com.yzddmr6.prismspace.bridge.SetAppFrozen
import com.yzddmr6.prismspace.bridge.SetPackageSuspended
import com.yzddmr6.prismspace.bridge.SetPackagesFrozen
import com.yzddmr6.prismspace.bridge.SetPackagesSuspended
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.engine.LaunchResult
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.model.interactive
import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability
import com.yzddmr6.prismspace.prism.compose.vm.currentLaunchability
import com.yzddmr6.prismspace.prism.compose.vm.launchFeedback
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation
import com.yzddmr6.prismspace.shuttle.DEFAULT_SYNC_TIMEOUT_MS
import com.yzddmr6.prismspace.util.Activities
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.IntentCompat
import com.yzddmr6.prismspace.util.OwnerUser
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Toasts
import com.yzddmr6.prismspace.util.Users.Companion.toId
import org.jetbrains.annotations.NotNull

object PrismAppControl {

	// Non-system clones are uninstalled only through the profile-routed queue (issue #6): the old
	// user-0 ACTION_UNINSTALL_PACKAGE + EXTRA_USER path is gone with no fallback. System packages
	// leave a dual space through the system app policy (SystemAppSelectionClient), never here.

	/** Records a clone-uninstall attempt with the real classification, emitted only after the
	 *  profile-side launch outcome is known. */
	@JvmStatic fun logUninstallLaunchOutcome(packageName: String, system: Boolean, launched: Boolean, failureReason: String?) {
		analytics().event("action_uninstall")
			.with(ITEM_ID, packageName)
			.with(ITEM_CATEGORY, uninstallItemCategory(system))
			.with(CONTENT, uninstallLaunchContent(launched, failureReason))
			.send()
	}

	@JvmStatic fun launch(context: Context, app: PrismAppInfo) {
		analytics().event("action_launch").with(ITEM_ID, app.packageName).send()
		// System-app search intentionally includes packages without a launcher activity. Never thaw
		// one of those packages for an action that cannot succeed: doing so changes the user's freeze
		// state and then reports only a launch failure. Same verdict as the list row and the sheet.
		if (currentLaunchability(app) == AppLaunchability.NoLauncherEntry) {
			DiagnosticLog.i(TAG, "launch_refused pkg=${app.packageName} state=NoLauncherEntry")
			Toast.makeText(context, prismResolver(context)(R.string.lz_app_no_launcher_entry, emptyArray()), Toast.LENGTH_LONG).show()
			return
		}
		// Suspended counts as frozen too (hybrid freeze): ensureAppFreeToLaunch lifts both hide and
		// suspend, but we must route here when EITHER is set — a suspended app won't launch otherwise.
		if (app.isHidden || app.isSuspended) unfreezeAndLaunch(context, app)
		else toastLaunch(context, PrismManager.launchApp(context, app.packageName, app.user), app.label.toString(), app.packageName)
	}

	private fun unfreezeAndLaunch(context: Context, app: PrismAppInfo) {
		val pkg = app.packageName
		val ready = when (val result = runProfileBridgeOperation(
			context,
			TAG,
			"unfreeze before launch pkg=$pkg",
			target = BridgeTargets.profile(app.user.toId()),
			timeoutMs = DEFAULT_SYNC_TIMEOUT_MS,
			command = EnsureAppFreeToLaunch(pkg),
		)) {
			is ProfileBridgeResult.Value -> result.value
			else -> return toastBridgeFailure(context, result)
		}
		if (ready == null) return toastLaunch(context, LaunchResult.Unknown("empty_unfreeze_result"), app.label.toString(), pkg)
		// The bridge reports a failure string, but DPM can also report success while a
		// system-imposed suspension stays set — verify the flag either way, then fall back
		// to the privileged shell before declaring the launch impossible.
		if (ready.isNotEmpty() || CloneSuspendRecovery.isSuspended(context, app.user, pkg) == true) {
			if (!CloneSuspendRecovery.privilegedUnsuspend(context, app.user, pkg))
				return toastLaunch(
					context,
					LaunchResult.Unknown(ready.ifEmpty { "still_suspended" }),
					app.label.toString(),
					pkg,
				)
			DiagnosticLog.i(TAG, "unfreeze before launch recovered via privileged unsuspend pkg=$pkg")
		}
		toastLaunch(context, PrismManager.launchApp(context, pkg, app.user), Apps.of(context).getAppName(pkg).toString(), pkg)
	}

	private fun toastLaunch(context: Context, result: LaunchResult, label: String, pkg: String) {
		if (result is LaunchResult.Ok) return
		val fb = launchFeedback(result, label, prismResolver(context))
		Toast.makeText(context, fb.message, Toast.LENGTH_LONG).show()
		val category = (result as? LaunchResult.Unknown)?.reason?.takeIf { it.isNotBlank() }
			?: result::class.simpleName ?: "Unknown"
		analytics().event("app_launch_error").with(ITEM_ID, pkg)
			.with(ITEM_CATEGORY, category).send()
	}

	@JvmStatic fun launchSystemAppSettings(app: PrismAppInfo) {    // Stock app info activity requires the target app not hidden.
		if (unfreezeIfNeeded(app))
			app.context().getSystemService(LauncherApps::class.java)!!.startAppDetailsActivity(ComponentName(app.packageName, ""), app.user, null, null)
	}

	@JvmStatic fun launchExternalAppSettings(vm: AndroidViewModel, app: @NotNull PrismAppInfo) {
		val context = app.context()
		val intent = Intent(IntentCompat.ACTION_SHOW_APP_INFO).setPackage(context.packageName)
				.putExtra(IntentCompat.EXTRA_PACKAGE_NAME, app.packageName).putExtra(Intent.EXTRA_USER, app.user)
		val resolve = context.packageManager.resolveActivity(intent, 0) ?: return
		// Should never happen as module "installer" is always bundled with "mobile".
		intent.component = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
		vm.interactive(context) { if (unfreezeIfNeeded(app)) Activities.startActivity(context, intent) }
	}

	/** @return true if not frozen (neither hidden nor suspended) or successfully unfrozen, false otherwise. */
	private fun unfreezeIfNeeded(app: PrismAppInfo): Boolean {
		return if (! app.isHidden && ! app.isSuspended) true else unfreeze(app) == true
	}

	@JvmStatic fun freeze(app: PrismAppInfo): Boolean {
		val pkg = app.packageName
		// Critical packages are kept available by provisioning convergence (enabled + unhidden + unsuspended);
		// a freeze would only leave them half-usable until the next convergence, so it is refused outright.
		if (app.isCritical) {
			DiagnosticLog.i(TAG, "freeze refused for critical pkg=$pkg")
			return false
		}
		return runAppControl(app.context(), app.user, "freeze pkg=$pkg", SetAppFrozen(pkg, true)) ?: false
	}

	@JvmStatic fun unfreeze(app: PrismAppInfo) = unfreeze(app.context(), app.user, app.packageName)
	private fun unfreeze(context: Context, profile: UserHandle, pkg: String): Boolean? {
		val viaBridge = runAppControl(context, profile, "unfreeze pkg=$pkg", SetAppFrozen(pkg, false))
		// A suspension imposed by the system/shell survives the profile owner's DPM unsuspend —
		// and worse, DPM can report success while the flag stays set (proven on-device). Never
		// trust the call result; verify the flag. Bridge-down stays null for honest reporting.
		if (viaBridge == true && CloneSuspendRecovery.isSuspended(context, profile, pkg) == false) return true
		if (CloneSuspendRecovery.privilegedUnsuspend(context, profile, pkg)) {
			DiagnosticLog.i(TAG, "unfreeze pkg=$pkg recovered via privileged unsuspend")
			return true
		}
		return viaBridge
	}

	@OwnerUser @ProfileUser internal fun setAppFrozenLocally(context: Context, pkg: String, hidden: Boolean): Boolean {
		val policies = DevicePolicies(context)
		// Same hide+suspend hybrid as the whole-space freeze. Only toast on genuine failure.
		if (applyFrozenWithFallback(policies, pkg, hidden)) return true
		val activeAdmins = policies.manager.activeAdmins
		if (activeAdmins != null && activeAdmins.any { pkg == it.packageName })
			Toasts.showLong(context, R.string.toast_error_freezing_active_admin)
		else Toasts.showLong(context, if (hidden) R.string.toast_error_freeze_failure else R.string.toast_error_unfreeze_failure)
		return false
	}

	@JvmStatic fun setSuspended(app: PrismAppInfo, suspended: Boolean): Boolean {
		val pkg = app.packageName
		val viaBridge = runAppControl(
			app.context(), app.user, "set suspended pkg=$pkg suspended=$suspended",
			SetPackageSuspended(pkg, suspended),
		) == true
		if (suspended) return viaBridge
		// Same cross-suspender gap as unfreeze, including the false-success case: verify the flag.
		if (viaBridge && CloneSuspendRecovery.isSuspended(app.context(), app.user, pkg) == false) return true
		return CloneSuspendRecovery.privilegedUnsuspend(app.context(), app.user, pkg)
	}
	fun setPackagesSuspended(context: Context, pkgs: Array<String>, suspended: Boolean): Array<String>
			= DevicePolicies(context).invoke(DevicePolicyManager::setPackagesSuspended, pkgs, suspended)

	/** Bulk suspend/restore every app of one dual space, routed through the profile bridge so the
	 * DPM call runs as profile owner inside the work profile. The low-level
	 * [setPackagesSuspended] helper requires a profile context.
	 * @return packages that could not be updated, or null if the profile is not ready. */
	@JvmStatic fun setSpaceSuspended(apps: List<PrismAppInfo>, suspended: Boolean): Array<String>? {
		if (apps.isEmpty()) return emptyArray()
		val pkgs = apps.map { it.packageName }.toTypedArray()
		return runAppControl(
			apps.first().context(), apps.first().user,
			"set space suspended count=${pkgs.size} suspended=$suspended",
			SetPackagesSuspended(pkgs.toList(), suspended),
		)
	}

	/** Whole-space freeze: hide every user clone of one dual space, routed through
	 * the profile bridge so the DPM call runs as profile owner inside the work profile. It uses the same
	 * freeze mechanism as per-app freeze so badge and recovery behavior stay consistent; unfreeze
	 * also lifts any lingering suspended flag.
	 *  @return packages that could not be updated, or null if the profile is not ready. */
	@JvmStatic fun setSpaceFrozen(apps: List<PrismAppInfo>, frozen: Boolean): Array<String>? {
		if (apps.isEmpty()) return emptyArray()
		val pkgs = apps.map { it.packageName }.toTypedArray()
		return runAppControl(
			apps.first().context(), apps.first().user,
			"set space frozen count=${pkgs.size} frozen=$frozen",
			SetPackagesFrozen(pkgs.toList(), frozen),
		)
	}

	/** Freeze/unfreeze [pkg] resiliently across OEM quirks.
	 * Strategy:
	 * - freeze: prefer hide for the clean "app vanishes" UX; if hide does not take effect, fall
	 *   back to suspend through a different enforcement path.
	 * - unfreeze: always clear both hide and suspend, since either could have frozen it.
	 * setPackagesSuspended returns the packages it could not update; empty means success.
	 * Runs inside the profile process (profile-owner DPM). */
	@OwnerUser @ProfileUser private fun applyFrozenWithFallback(policies: DevicePolicies, pkg: String, frozen: Boolean): Boolean {
		val hideOk = policies.setApplicationHidden(pkg, frozen) ||
				policies.invoke(DevicePolicyManager::isApplicationHidden, pkg) == frozen
		return if (frozen) {
			if (hideOk) true
			else policies.invoke(DevicePolicyManager::setPackagesSuspended, arrayOf(pkg), true).isEmpty()
		} else {
			val unsuspendOk = policies.invoke(DevicePolicyManager::setPackagesSuspended, arrayOf(pkg), false).isEmpty()
			hideOk && unsuspendOk
		}
	}

	@OwnerUser @ProfileUser internal fun setPackagesFrozenLocally(
		context: Context,
		packageNames: List<String>,
		frozen: Boolean,
	): Array<String> {
		val policies = DevicePolicies(context)
		return packageNames.filter { pkg -> !applyFrozenWithFallback(policies, pkg, frozen) }.toTypedArray()
	}

	private fun <T> runAppControl(
		context: Context,
		profile: UserHandle,
		operation: String,
		command: ProfileCommand<T>,
	): T? = when (val result = runProfileBridgeOperation(
		context,
		TAG,
		operation,
		target = BridgeTargets.profile(profile.toId()),
		command = command,
	)) {
			is ProfileBridgeResult.Value -> result.value
			else -> null.also { toastBridgeFailure(context, result) }
		}

	private fun toastBridgeFailure(context: Context, result: ProfileBridgeResult<*>) {
		Toasts.showLong(
			context,
			profileBridgeFailureMessage(context, result, context.getString(R.string.prompt_space_not_ready)),
		)
	}

	private const val TAG = "Prism.AppControl"
}

/** Real app classification for uninstall diagnostics — never a hardcoded constant. */
internal fun uninstallItemCategory(system: Boolean) = if (system) "system" else "user"

/** Launch outcome carried by the uninstall event, recorded after the launch result is known. */
internal fun uninstallLaunchContent(launched: Boolean, failureReason: String?) =
	if (launched) "submitted" else "failed:${failureReason?.takeIf { it.isNotBlank() } ?: "unknown"}"

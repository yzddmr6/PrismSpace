package com.yzddmr6.prismspace.controller

import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.app.PendingIntent
import android.content.*
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.*
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.Build.VERSION_CODES.P
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import androidx.annotation.IntDef
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.Dialogs
import com.yzddmr6.prismspace.util.Apps
import com.yzddmr6.prismspace.PrismNameManager
import com.yzddmr6.prismspace.analytics.Analytics
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.clone.AppClonesBottomSheet
import com.yzddmr6.prismspace.clone.CloneModeOption
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.CapabilityRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import com.yzddmr6.prismspace.controller.PrismAppControl.launchSystemAppSettings
import com.yzddmr6.prismspace.controller.PrismAppControl.unfreezeInitiallyFrozenSystemApp
import com.yzddmr6.prismspace.data.PrismAppInfo
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.data.helper.hidden
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.data.helper.isSystem
import com.yzddmr6.prismspace.data.helper.suspended
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.help.PrismHelp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.model.interactive
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.ui.ModelBottomSheetFragment
import com.yzddmr6.prismspace.util.Activities
import com.yzddmr6.prismspace.util.*
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users.Companion.isParentProfile
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import eu.chainfire.libsuperuser.Shell
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.InstallSourcePermissionHelper
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.prism.service.TransferHistoryStore
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.Shizuku.removeRequestPermissionResultListener
import java.util.*
import java.util.stream.Collectors
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.TYPE

 /**
 * Controller for complex procedures of PrismSpace.
 *
 * Refactored by Oasis on 2018-9-30.
 */
class PrismAppClones(val activity: FragmentActivity, val vm: AndroidViewModel, val app: PrismAppInfo) {

	fun request() {
		val names = PrismNameManager.getAllNames(context)
		check(names.isNotEmpty()) { "No PrismSpace" }
		// Clone is one-way 主→双 only: never list the parent (main) profile as a copy target.
		// You can't clone an app onto the space it already lives in.
		val targets: MutableMap<UserHandle, String> = LinkedHashMap(names)

		val spaceCount = SpaceRepositoryProvider.get(context).cloneTargetSpaceCount()
		val shouldShowBadge: Boolean = spaceCount > 2
		val icons: Map<UserHandle, Drawable> = targets.entries.stream().collect(Collectors.toMap({ obj: Map.Entry<UserHandle, String> -> obj.key }) { e: Map.Entry<UserHandle, String> ->
			val user = e.key
			// 主空间 = person (ic_portrait); 双开空间 = the apps-grid glyph used by the Space tab.
			// A house icon would read as "home/main" and clash with the main-space row.
			val res = if (user.isParentProfile()) R.drawable.ic_portrait_24dp else R.drawable.ic_prism_apps_24
			val drawable: Drawable = context.getDrawable(res)!!
			val dark = (context.resources.configuration.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
			drawable.setTint(context.getColor(if (dark) android.R.color.white else android.R.color.black))
			if (shouldShowBadge) Users.getUserBadgedIcon(context, drawable, user) else drawable })

		// REQUEST_INSTALL_PACKAGES is disallowed on Google Play Store, thus removed in the Google Play packaging.
		val isShizukuAvailable = try { Shizuku.getVersion() >= 11 } catch (e: RuntimeException) { false }
		val isShizukuReady = isShizukuAvailable && Shizuku.checkSelfPermission() == PERMISSION_GRANTED

		val fragment = ModelBottomSheetFragment()
		val alp = PrismAppListProvider.getInstance(context)
		val dialog = AppClonesBottomSheet(targets, icons, { user -> alp.isInstalled(pkg, user) }) { target, mode ->
			DiagnosticLog.i(TAG, "Clone requested pkg=$pkg targetUser=${target.toId()} mode=$mode")
			// The normal follow-up procedure goes here
			makeAppAvailable(target, mode)
			fragment.dismiss() }

		// Root availability follows the chosen run mode (set in Settings, which already granted su) — we do
		// NOT probe su here, to avoid a root prompt on every clone. Shizuku/Play use non-prompting checks.
		val selectedRunMode = CapabilityRepositoryProvider.get(context).selectedMode.value
		val isRootReady = selectedRunMode == PrismMode.Root
		// Run mode is authoritative: Shizuku is selectable only when the user actually chose Shizuku
		// mode (and it's connected). In 普通模式 the row stays visible but greyed with a 「去启用」 jump to
		// run-mode settings (discovery funnel), so 普通模式 can no longer silently use Shizuku. (Root is
		// already mode-gated via isRootReady.)
		val isShizukuModeReady = selectedRunMode == PrismMode.Shizuku && isShizukuReady
		// i18n: option labels were hardcoded Chinese (showed Chinese in an English UI). Resolve via the
		// app's chosen locale so the install-method dialog is fully localized.
		val loc = PrismLocale.wrap(context)
		val options = listOf(
			CloneModeOption(MODE_INSTALLER, loc.getString(R.string.lz_app_method_filesync_title),
				loc.getString(R.string.lz_app_method_filesync_summary), available = true, showEnableGuide = false),
			CloneModeOption(MODE_SHIZUKU, loc.getString(R.string.lz_app_method_shizuku_title),
				if (isShizukuModeReady) loc.getString(R.string.lz_app_method_shizuku_summary_ready)
				else if (selectedRunMode != PrismMode.Shizuku) loc.getString(R.string.lz_app_method_shizuku_summary_wrong_mode)
				else if (! isShizukuAvailable) loc.getString(R.string.lz_app_method_shizuku_summary_not_connected)
				else loc.getString(R.string.lz_app_method_shizuku_summary_waiting),
				available = isShizukuModeReady, showEnableGuide = true),
			CloneModeOption(MODE_ROOT, loc.getString(R.string.lz_app_method_root_title),
				if (isRootReady) loc.getString(R.string.lz_app_method_root_summary_ready)
				else loc.getString(R.string.lz_app_method_root_summary_not_enabled),
				available = isRootReady, showEnableGuide = true),
		)
		// Default method respects the selected run mode. Fall back to the always-available file-sync if the
		// mode's privileged method isn't ready yet.
		val defaultMode = when (selectedRunMode) {
			PrismMode.Root -> if (isRootReady) MODE_ROOT else MODE_INSTALLER
			PrismMode.Shizuku -> if (isShizukuReady) MODE_SHIZUKU else MODE_INSTALLER
			else -> MODE_INSTALLER
		}

		fragment.show(activity) {
			val mode = remember { mutableStateOf(defaultMode) }
			dialog.compose(options, mode) { fragment.dismiss(); AppLaunchSignals.signalOpenRunMode() }
		}
	}

	/**
	 * Headless clone for the batch flow: clone into the single dual space using the install
	 * method implied by the user's configured run mode — no per-app selector sheet. The batch caller
	 * confirms once up front; running install method/capability fallback is still handled by
	 * [cloneRoute] inside [cloneApp] (e.g. Shizuku-selected-but-not-granted degrades to file sync).
	 *
	 * Mode follows [CapabilityRepository.selectedMode] — the single source of truth — rather than
	 * re-detecting here, so it can never disagree with what Settings shows.
	 */
	fun requestSilently() {
		val target = PrismNameManager.getAllNames(context).keys.firstOrNull() ?: return
		val mode = when (CapabilityRepositoryProvider.get(context).selectedMode.value) {
			PrismMode.Root -> MODE_ROOT
			PrismMode.Shizuku -> MODE_SHIZUKU
			else -> MODE_INSTALLER   // 普通模式 → 文件同步
		}
		makeAppAvailable(target, mode)
	}

	/** Either by unfreezing initially frozen (system) app, enabling disabled system app, or clone user app. */
	private fun makeAppAvailable(profile: UserHandle, mode: Int) {
		val target = PrismAppListProvider.getInstance(context)[pkg, profile]
		if (target != null && target.isHiddenSysPrismAppTreatedAsDisabled) {   // Frozen system app shown as disabled, just unfreeze it.
			if (unfreezeInitiallyFrozenSystemApp(target) == true)
				feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, app.label))
		} else if (target != null && target.isInstalled && !target.enabled) {  // Disabled app may be shown as "removed"
			launchSystemAppSettings(target)
			feedback(PrismLocale.wrap(context).getString(R.string.toast_enable_disabled_system_app))
		} else vm.interactive(context) {
			cloneApp(app, profile, mode)
		}
	}

	private suspend fun cloneApp(source: PrismAppInfo, target: UserHandle, mode: @AppCloneMode Int) {
		val context = source.context(); val pkg = source.packageName
		DiagnosticLog.i(TAG, "cloneApp start pkg=$pkg targetUser=${target.toId()} mode=$mode system=${source.isSystem}")
		// Record this as a user-initiated clone so the dual list/count recognize it as a 分身 even when
		// the package is itself a system app (e.g. Chrome). Third-party clones don't need this — they're
		// recognized structurally — but recording is harmless and keeps the rule uniform. Visibility is
		// still gated by "actually installed in the dual space", so a failed clone won't show a ghost row.
		UserCloneRegistry.add(context, pkg)
		val route = cloneRoute(target.isParentProfile(), source.isSystem, mode,
				{ isInstallerUsable() }, { Shizuku.checkSelfPermission() == PERMISSION_GRANTED }, { mode == MODE_ROOT })
		DiagnosticLog.i(TAG, "cloneApp route pkg=$pkg targetUser=${target.toId()} route=$route")
			when (route) {
				CloneRoute.PARENT_INSTALLER -> {
					@Suppress("DEPRECATION") // Only works in parent profile due to a bug in AOSP.
					activity.startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.fromParts("package", pkg, null)), 1)
					return
				}

				CloneRoute.ROOT -> {
					analytics().event("clone_root").with(Analytics.Param.ITEM_ID, pkg).send()
					val ok = withContext(Dispatchers.IO) {
						val out = Shell.SU.run("pm install-existing --user ${target.toId()} $pkg")
						out != null && out.any { it.contains("installed for user", ignoreCase = true) }
					}
					if (ok) {
						PrismAppListProvider.getInstance(context).refreshPackage(pkg, target, true)
						feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, source.label))
					} else {
						feedback(PrismLocale.wrap(context).getString(R.string.toast_clone_root_unavailable), isError = true)
					}
					return
				}

				CloneRoute.FILE_SYNC -> {
					cloneViaFileSync(context, source)
					return
				}

				CloneRoute.SYSTEM_ENABLE -> {
					analytics().event("clone_sys").with(Analytics.Param.ITEM_ID, pkg).send()
					val enabled = when (val result = runProfileBridgeOperation(
						context,
						TAG,
						"enable system app pkg=$pkg",
						target = target,
					) { DevicePolicies(this).enableSystemApp(pkg) }) {
						is ProfileBridgeResult.Value -> result.value == true
						else -> {
							feedback(
								profileBridgeFailureMessage(context, result, PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, source.label)),
								isError = true,
							)
							return
						}
					}
					if (enabled) feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, source.label))
					else feedback(PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, source.label), isError = true)
					return
				}

				CloneRoute.SHIZUKU -> {
					val component = ComponentName(context, PrivilegedRemoteWorker::class.java)
					val shizukuServiceTag = "clone-$pkg-${SystemClock.uptimeMillis()}"
					val args = UserServiceArgs(component).daemon(false).processNameSuffix(pkg).tag(shizukuServiceTag)
					val done = java.util.concurrent.atomic.AtomicBoolean(false)
					val main = Handler(Looper.getMainLooper())
					fun fail() = feedback(PrismLocale.wrap(context).getString(R.string.lz_app_clone_shizuku_failed), isError = true)
					lateinit var conn: ServiceConnection
					conn = object : ServiceConnection {
						override fun onServiceConnected(name: ComponentName, service: IBinder) {
							DiagnosticLog.i(TAG, "Shizuku service connected pkg=$pkg targetUser=${target.toId()} name=$name")
							if (!done.compareAndSet(false, true)) return
							main.removeCallbacksAndMessages(null)
							vm.viewModelScope.launch {
								val result = withContext(Dispatchers.IO) {
									val data = Parcel.obtain().apply { writeString(pkg); writeInt(target.toId()) }
									val reply = Parcel.obtain()
									try {
										service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
										reply.readInt()
									} catch (e: RemoteException) {
										DiagnosticLog.e(TAG, "Shizuku transact failed for $pkg", e)
										-1
									} finally {
										data.recycle()
										reply.recycle()
										runCatching { Shizuku.unbindUserService(args, conn, true) }
									}
								}
								DiagnosticLog.i(TAG, "Shizuku clone result pkg=$pkg targetUser=${target.toId()} result=$result")
								if (result == 1) {
									PrismAppListProvider.getInstance(context).refreshPackage(pkg, target, true)
									feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, source.label))
								} else {
									fail()
								}
							}
						}

						override fun onServiceDisconnected(name: ComponentName?) {
							DiagnosticLog.i(TAG, "Shizuku service disconnected before completion pkg=$pkg targetUser=${target.toId()} name=$name")
							if (done.compareAndSet(false, true)) {
								main.removeCallbacksAndMessages(null)
								fail()
							}
						}
					}
					main.postDelayed({
						if (done.compareAndSet(false, true)) {
							runCatching { Shizuku.unbindUserService(args, conn, true) }
							fail()
						}
					}, 20_000)
					try {
						DiagnosticLog.i(TAG, "Binding Shizuku service pkg=$pkg targetUser=${target.toId()}")
						Shizuku.bindUserService(args, conn)
					} catch (e: Throwable) {
						DiagnosticLog.e(TAG, "Shizuku bindUserService failed for $pkg", e)
						if (done.compareAndSet(false, true)) {
							main.removeCallbacksAndMessages(null)
							fail()
						}
					}
					return
				}
			}
	}


		/**
		 * 普通模式 / no privilege: transfer the parent app's FULL APK set (base + ALL splits) into
		 * the dual space's Download/PrismSpace, then guide the user to the profile-side foreground
		 * system-installer path.
	 *
		 * Why not auto-install here: a non-affiliated profile owner cannot call installExistingPackage
		 * (AOSP SecurityException — needs affiliation, impossible without a device owner), and launching the
		 * system App Installer for the profile from the main space is hard-blocked by Android 15 BAL
		 * (background-activity-start: the cross-process PendingIntent's creator can't opt in from a
		 * background profile process. So no-privilege install into a work
		 * profile is an OS limitation, not an app bug. We therefore copy the complete app
		 * and open a profile-side entry where the user can confirm installation
		 * with Android's normal package installer.
		 */
		private fun cloneViaFileSync(context: Context, source: PrismAppInfo) {
			analytics().event("clone_file_sync").with(Analytics.Param.ITEM_ID, pkg).send()
			val appInfo = source as ApplicationInfo
			// base + every split so split apps install as a complete package set.
			val apks = buildList {
				appInfo.publicSourceDir?.takeIf { it.isNotEmpty() }?.let { add(java.io.File(it)) }
				@Suppress("DEPRECATION") appInfo.splitSourceDirs?.forEach { add(java.io.File(it)) }
			}
			if (apks.isEmpty()) {
				feedback(PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, source.label), isError = true)
				return
			}
			feedback(PrismLocale.wrap(context).getString(R.string.toast_clone_file_sync_transferring))
			vm.viewModelScope.launch {
				val bridge = FileBridgeService()
				suspend fun importOnce() = withContext(Dispatchers.IO) {
					bridge.importApksToProfile(context, apks, source.label.toString(), pkg)
				}

				var result = importOnce()
				if (!result.success && result.failureReason == FileTransferFailureReason.SpaceInactive) {
					val profile = Users.profile
					val activated = if (profile != null && SDK_INT >= P) {
						feedback(PrismLocale.wrap(context).getString(R.string.prompt_activating_space), isError = false)
						runCatching { Users.requestQuietModeDisabled(context, profile) }
							.onFailure { DiagnosticLog.e(TAG, "file sync clone activation failed pkg=$pkg user=${profile.toId()}", it) }
							.getOrDefault(false)
					} else {
						false
					}
					if (activated) {
						DiagnosticLog.i(TAG, "file sync clone retry after activation pkg=$pkg")
						result = importOnce()
					}
				}

				if (!result.success) {
					if (result.failureReason == FileTransferFailureReason.BridgeNotReady) {
						val profile = Users.profile
						if (profile != null && ProfileEntryLauncher.start(activity, profile)) {
							feedback(PrismLocale.wrap(context).getString(R.string.fb_opened_profile_entry_retry), isError = false)
						} else {
							feedback(result.message, isError = true)
						}
					} else {
						feedback(result.message, isError = true)
					}
					return@launch
				}

				// Record the outgoing half in the main-space history as "label-package";
				// the dual half is recorded inside importApksToProfile.
				TransferHistoryStore.record(
					context, source.label.toString(),
					PrismLocale.wrap(context).getString(R.string.lz_app_clone_to_dual_space), false, packageName = pkg)
				Dialogs.buildAlert(activity, R.string.dialog_title_clone_file_sync, R.string.dialog_clone_file_sync_done)
					.setPositiveButton(R.string.lz_app_filesync_open_install_entry) { _, _ ->
						val openResult = FileBridgeService().openProfileInstallEntry(activity)
						if (!openResult.success) feedback(openResult.message, isError = true)
				}
				.setNeutralButton(R.string.lz_app_filesync_allow_file_manager) { _, _ ->
					val openResult = FileBridgeService().openProfileInstallSourceSettings(
						activity,
						InstallSourcePermissionHelper.SYSTEM_FILE_MANAGER_PACKAGE,
					)
					if (!openResult.success) feedback(openResult.message, isError = true)
				}
				.setNegativeButton(android.R.string.ok, null).show()
			}
		}

		// Clone results surface through the unified Snackbar bus hosted by MainActivity.
		private fun feedback(message: String, isError: Boolean = false) =
			AppFeedbackBus.emit(ActionFeedback(message, isError))

	private fun isInstallerUsable() = ModuleContext(context).forDeclaredPermission(REQUEST_INSTALL_PACKAGES) != null




	companion object {

		@IntDef(MODE_INSTALLER, MODE_SHIZUKU, MODE_ROOT) @Target(TYPE) @Retention(SOURCE)
		annotation class AppCloneMode
		const val MODE_INSTALLER = 0    // 普通模式: copy full app + guide to profile-side system installer
		const val MODE_SHIZUKU = 2
		const val MODE_ROOT = 3




	}

	private val pkg = app.packageName
	private val context = app.context()
}

private const val TAG = "Prism.AC"

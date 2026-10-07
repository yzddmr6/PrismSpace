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
import com.yzddmr6.prismspace.bridge.BridgeTargets
import androidx.annotation.IntDef
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
import com.yzddmr6.prismspace.prism.compose.theme.PrismTheme
import com.yzddmr6.prismspace.clone.ClonePreparationSheet
import androidx.compose.runtime.mutableStateOf
import com.yzddmr6.prismspace.clone.CloneConfirmSheet
import com.yzddmr6.prismspace.prism.compose.nav.AppLaunchSignals
import com.yzddmr6.prismspace.prism.compose.vm.ActionFeedback
import com.yzddmr6.prismspace.prism.compose.vm.AppFeedbackBus
import com.yzddmr6.prismspace.prism.compose.vm.BatchCloneResult
import com.yzddmr6.prismspace.prism.compose.vm.CapabilityRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import com.yzddmr6.prismspace.prism.compose.vm.prismModeLabelRes
import com.yzddmr6.prismspace.prism.compose.vm.ShizukuUtil
import com.yzddmr6.prismspace.controller.PrismAppControl.launchSystemAppSettings
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import eu.chainfire.libsuperuser.Shell
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.FileTransferResult
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.prism.transfer.TransferKind
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferPaths
import com.yzddmr6.prismspace.prism.transfer.TransferRole
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
class PrismAppClones(
	val activity: FragmentActivity,
	val vm: AndroidViewModel,
	val app: PrismAppInfo,
	private val onCloneStateChanged: () -> Unit = {},
) {

	fun request(): CloneRequestOutcome {
		val names = PrismNameManager.getAllNames(context)
		if (names.isEmpty()) {
			feedback(PrismLocale.wrap(context).getString(R.string.fb_need_create_space), isError = true)
			return CloneRequestOutcome.Unavailable
		}
		// Clone is one-way 主→双 only: never list the parent (main) profile as a copy target.
		// You can't clone an app onto the space it already lives in.
		val targets: MutableMap<UserHandle, String> = LinkedHashMap(names)

		val spaceCount = SpaceRepositoryProvider.get(context).cloneTargetSpaceCount()
		val shouldShowBadge: Boolean = spaceCount > 2
		val icons: Map<UserHandle, Drawable> = targets.entries.stream().collect(Collectors.toMap({ obj: Map.Entry<UserHandle, String> -> obj.key }) { e ->
			val user = e.key
			// 主空间 = person (ic_portrait); 双开空间 = the apps-grid glyph used by the Space tab.
			// A house icon would read as "home/main" and clash with the main-space row.
			val res = if (user.isParentProfile()) R.drawable.ic_portrait_24dp else R.drawable.ic_prism_apps_24
			val drawable: Drawable = context.getDrawable(res)!!
			val dark = (context.resources.configuration.uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
			drawable.setTint(context.getColor(if (dark) android.R.color.white else android.R.color.black))
			if (shouldShowBadge) Users.getUserBadgedIcon(context, drawable, user) else drawable })

		// The install method is a global preference (Settings → 添加分身方式), NOT asked per add.
		// The confirm sheet shows the EFFECTIVE route (capability fallback included) so the CTA
		// copy always matches what will actually happen.
		// Stale-window note: this snapshot is taken at sheet-open; if capabilities change while the
		// sheet is up, the displayed route can lag — but execution is unaffected because cloneApp
		// re-reads runtimeSnapshot() and re-plans the route at run time (the display never drives
		// the actual route).
		val capabilityRepo = CapabilityRepositoryProvider.get(context)
		val runtime = capabilityRepo.runtimeSnapshot()
		val configuredMode = when (runtime.preferredMode) {
			PrismMode.Root -> MODE_ROOT
			PrismMode.Shizuku -> MODE_SHIZUKU
			else -> MODE_INSTALLER
		}
		val effectiveRoute = planCloneRoute(
			false,
			app.isSystem,
			configuredMode,
			{ isInstallerUsable() },
			CloneRuntimeReadiness(runtime.shizukuReady, runtime.rootReady),
		).route
		val loc = PrismLocale.wrap(context)
		val (methodTitle, methodSummary) = when (effectiveRoute) {
			CloneRoute.ROOT -> loc.getString(R.string.lz_app_method_root_title) to
				loc.getString(R.string.lz_app_method_root_summary_ready)
			CloneRoute.SHIZUKU -> loc.getString(R.string.lz_app_method_shizuku_title) to
				loc.getString(R.string.lz_app_method_shizuku_summary_ready)
			CloneRoute.SYSTEM_ENABLE -> loc.getString(R.string.lz_system_apps_title) to
				loc.getString(R.string.lz_clone_confirm_body_system)
			else -> loc.getString(R.string.lz_app_method_filesync_title) to
				loc.getString(R.string.lz_app_method_filesync_summary)
		}

		val fragment = ModelBottomSheetFragment()
		val alp = PrismAppListProvider.getInstance(context)
		val sheet = CloneConfirmSheet(
			appLabel = app.label.toString(),
			methodTitle = methodTitle,
			methodSummary = methodSummary,
			route = effectiveRoute,
			targets = targets,
			icons = icons,
			isCloned = { user -> alp.isInstalled(pkg, user) },
			onChangeMethod = { fragment.dismiss(); AppLaunchSignals.signalOpenRunMode() },
			onConfirm = { target ->
				DiagnosticLog.i(TAG, "Clone confirmed pkg=$pkg targetUser=${target.toId()} mode=$configuredMode route=$effectiveRoute")
				makeAppAvailable(target, configuredMode)
				fragment.dismiss()
			},
		)
		fragment.show(activity) { PrismTheme { sheet.compose() } }
		return CloneRequestOutcome.Started
	}

	/**
	 * Batch clone with a REAL per-package outcome: normal mode stages the complete APK set and
	 * reports [BatchCloneResult.Prepared] (never "cloned" — the user still confirms in the dual
	 * space's system installer); a ready enhanced route reports [BatchCloneResult.Installed] only
	 * after the install actually completes. Emits no UI; the batch caller summarizes once.
	 *
	 * @param forceNormalPreparation always stage for a user-confirmed install (the selection page's
	 *        preinstalled group), even when Root/Shizuku is the configured method: never a silent install.
	 */
	suspend internal fun requestForBatch(forceNormalPreparation: Boolean = false): BatchCloneResult {
		val target = PrismNameManager.getAllNames(context).keys.firstOrNull() ?: return BatchCloneResult.Failed()
		val runtime = CapabilityRepositoryProvider.get(context).runtimeSnapshot()
		val mode = batchCloneMode(runtime.preferredMode, forceNormalPreparation)   // 普通模式 → 文件同步
		val plan = planCloneRoute(
			target.isParentProfile(),
			app.isSystem,
			mode,
			{ isInstallerUsable() },
			CloneRuntimeReadiness(runtime.shizukuReady, runtime.rootReady),
		)
		DiagnosticLog.i(
			TAG,
			"batch clone route pkg=$pkg targetUser=${target.toId()} requestedMode=$mode " +
				"actual=${plan.route} fallback=${plan.usedNormalFallback}",
		)
		return when (plan.route) {
			CloneRoute.FILE_SYNC -> {
				// Same diagnostics event as the interactive file-sync path (cloneViaFileSync).
				analytics().event("clone_file_sync").with(Analytics.Param.ITEM_ID, pkg).send()
				val apks = fullApkSet(app)
				if (apks.isEmpty()) BatchCloneResult.Failed()
				else {
					// Batch never activates quiet mode per package; the batch driver owns the
					// single per-run activation attempt and the retry of this package.
					val result = stageApkSetToProfile(context, app, apks, allowActivationRetry = false)
					when {
						result.success -> BatchCloneResult.Prepared
						result.failureReason == FileTransferFailureReason.SpaceInactive ->
							BatchCloneResult.Failed(needsActivation = true)
						else -> BatchCloneResult.Failed()
					}
				}
			}
			CloneRoute.ROOT ->
				if (installExistingViaRoot(context, target).installed) BatchCloneResult.Installed else BatchCloneResult.Failed()
			CloneRoute.SHIZUKU ->
				if (awaitShizukuClone(context, target).resultCode == 1) BatchCloneResult.Installed else BatchCloneResult.Failed()
			// System-app and parent-installer routes are unreachable from the batch domain
			// (selection excludes system apps; the target is always the dual space). Fail closed.
			else -> BatchCloneResult.Failed()
		}
	}

	/** base + every split so split apps install as a complete package set. */
	private fun fullApkSet(source: PrismAppInfo): List<java.io.File> {
		val appInfo = source as ApplicationInfo
		return buildList {
			appInfo.publicSourceDir?.takeIf { it.isNotEmpty() }?.let { add(java.io.File(it)) }
			@Suppress("DEPRECATION") appInfo.splitSourceDirs?.forEach { add(java.io.File(it)) }
		}
	}

	/** Headless staging core shared by single and batch clone: copies the complete APK set into the
	 *  dual space and, only on real success, records the pending-install marker plus the outgoing
	 *  transfer history (the dual half is recorded inside importApksToProfile). UI presentation
	 *  stays with the caller.
	 *  @param allowActivationRetry the interactive path retries once after a quiet-mode activation
	 *  prompt; the batch path passes false — activation is a per-batch budget owned by the driver. */
	private suspend fun stageApkSetToProfile(
		context: Context,
		source: PrismAppInfo,
		apks: List<java.io.File>,
		onActivating: () -> Unit = {},
		allowActivationRetry: Boolean = true,
	): FileTransferResult {
		val bridge = FileBridgeService()
		suspend fun importOnce() = withContext(Dispatchers.IO) {
			bridge.importApksToProfile(context, apks, source.label.toString(), pkg)
		}

		var result = importOnce()
		if (allowActivationRetry && !result.success && result.failureReason == FileTransferFailureReason.SpaceInactive) {
			val profile = Users.profile
			val activated = if (profile != null && SDK_INT >= P) {
				onActivating()
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
		if (result.success) {
			ClonePreparationStore.add(context, pkg)
			// The outgoing half in the main-space ledger, titled "label-package"; the dual half is
			// recorded where the suite is published (MobileFileBridgePort.importApkSet).
			TransferLedger.upsertApkSuite(context, TransferLedgerRecord(
				id = UUID.randomUUID().toString(), displayName = source.label.toString(),
				mime = "application/vnd.android.package-archive", sizeBytes = null, contentUri = result.targetUri,
				relativePath = TransferPaths.DOWNLOAD_LOCATION, direction = TransferDirection.ToProfile,
				role = TransferRole.Sent, kind = TransferKind.ApkSuite, packageName = pkg, apkUris = emptyList(),
				timeMillis = System.currentTimeMillis(), legacy = false))
		}
		return result
	}

	private data class RootInstallResult(val installed: Boolean, val rootAvailable: Boolean)

	/** @return [RootInstallResult.installed] only when `pm install-existing` actually reported
	 *  success for the target user; [RootInstallResult.rootAvailable] distinguishes "su missing"
	 *  from "install failed". */
	private suspend fun installExistingViaRoot(context: Context, target: UserHandle): RootInstallResult {
		analytics().event("clone_root").with(Analytics.Param.ITEM_ID, pkg).send()
		val output = withContext(Dispatchers.IO) {
			Shell.SU.run("pm install-existing --user ${target.toId()} $pkg")
		}
		val rootAvailable = !output.isNullOrEmpty()
		val capabilityRepo = CapabilityRepositoryProvider.get(context)
		if (rootAvailable) capabilityRepo.markRootReady() else capabilityRepo.markRootUnavailable()
		return RootInstallResult(
			installed = output?.any { it.contains("installed for user", ignoreCase = true) } == true,
			rootAvailable = rootAvailable,
		)
	}

	/** The privileged-worker install transaction; resultCode 1 = installed for the target user. */
	private suspend fun transactPrivilegedClone(service: IBinder, target: UserHandle): PrivilegedCloneReply =
		withContext(Dispatchers.IO) {
			val data = Parcel.obtain().apply { writeString(pkg); writeInt(target.toId()) }
			val reply = Parcel.obtain()
			try {
				service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
				PrivilegedCloneReply(reply.readInt(), reply.readString(), reply.readString())
			} catch (e: Throwable) {
				DiagnosticLog.e(TAG, "Shizuku transact failed for $pkg", e)
				PrivilegedCloneReply(-1, e.javaClass.name, e.message)
			} finally {
				data.recycle()
				reply.recycle()
			}
		}

	/** Headless Shizuku clone for the batch flow: binds the privileged worker and AWAITS the real
	 *  install result (20s timeout, same contract as the interactive path), then unbinds. */
	private suspend fun awaitShizukuClone(context: Context, target: UserHandle): PrivilegedCloneReply =
		withContext(Dispatchers.Main) {
			val component = ComponentName(context, PrivilegedRemoteWorker::class.java)
			val shizukuServiceTag = "batch-clone-$pkg-${SystemClock.uptimeMillis()}"
			val args = UserServiceArgs(component).daemon(false).processNameSuffix(pkg).tag(shizukuServiceTag)
			val capabilityRepo = CapabilityRepositoryProvider.get(context)
			suspendCancellableCoroutine { cont ->
				val main = Handler(Looper.getMainLooper())
				val done = java.util.concurrent.atomic.AtomicBoolean(false)
				lateinit var conn: ServiceConnection
				fun finish(reply: PrivilegedCloneReply) {
					if (!done.compareAndSet(false, true)) return
					main.removeCallbacksAndMessages(null)
					runCatching { Shizuku.unbindUserService(args, conn, true) }
					if (cont.isActive) cont.resume(reply)
				}
				conn = object : ServiceConnection {
					override fun onServiceConnected(name: ComponentName, service: IBinder) {
						DiagnosticLog.i(TAG, "Shizuku batch service connected pkg=$pkg targetUser=${target.toId()} name=$name")
						vm.viewModelScope.launch {
							val result = transactPrivilegedClone(service, target)
							DiagnosticLog.i(
								TAG,
								"Shizuku batch clone result pkg=$pkg targetUser=${target.toId()} code=${result.resultCode} " +
									"exceptionClass=${result.exceptionClass} message=${result.message}",
							)
							if (result.resultCode == 1) capabilityRepo.markShizukuReady()
							else if (!ShizukuUtil.isAuthorized()) capabilityRepo.markShizukuUnavailable()
							finish(result)
						}
					}

					override fun onServiceDisconnected(name: ComponentName?) {
						DiagnosticLog.w(TAG, "Shizuku batch service disconnected before completion pkg=$pkg")
						capabilityRepo.markShizukuUnavailable()
						finish(PrivilegedCloneReply(-1, "ServiceDisconnected", "service_disconnected"))
					}
				}
				main.postDelayed({
					capabilityRepo.markShizukuUnavailable()
					finish(PrivilegedCloneReply(-1, "Timeout", "service_timeout"))
				}, 20_000)
				cont.invokeOnCancellation { finish(PrivilegedCloneReply(-1, "Cancelled", "cancelled")) }
				try {
					DiagnosticLog.i(TAG, "Binding Shizuku batch service pkg=$pkg targetUser=${target.toId()}")
					Shizuku.bindUserService(args, conn)
				} catch (e: Throwable) {
					DiagnosticLog.e(TAG, "Shizuku batch bindUserService failed for $pkg", e)
					capabilityRepo.markShizukuUnavailable()
					finish(PrivilegedCloneReply(-1, e.javaClass.simpleName, e.message))
				}
			}
		}

	/** Either by unfreezing initially frozen (system) app, enabling disabled system app, or clone user app. */
	private fun makeAppAvailable(profile: UserHandle, mode: Int) {
		val target = PrismAppListProvider.getInstance(context)[pkg, profile]
		if (target != null && target.isHiddenSysPrismAppTreatedAsDisabled) {   // Kept out by the system app policy: add it back.
			vm.interactive(context) { enableSystemAppViaPolicy(profile) }
		} else if (target != null && target.isInstalled && !target.enabled) {  // Disabled app may be shown as "removed"
			launchSystemAppSettings(target)
			feedback(PrismLocale.wrap(context).getString(R.string.toast_enable_disabled_system_app))
		} else vm.interactive(context) {
			cloneApp(app, profile, mode)
		}
	}

	/** System apps are added through the dual space's system app policy (enableSystemApp inside the
	 *  profile, no APK copy, no privilege). Only a verified "available" result counts as cloned. */
	private suspend fun enableSystemAppViaPolicy(target: UserHandle) {
		val result = withContext(Dispatchers.IO) { SystemAppSelectionClient.setAvailable(context, target.toId(), pkg, available = true) }
		val report = (result as? ProfileBridgeResult.Value)?.value
		if (report == null) {
			feedback(
				profileBridgeFailureMessage(context, result, PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, app.label)),
				isError = true,
			)
			return
		}
		if (pkg in report.available) {
			onCloneStateChanged()
			feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, app.label))
		} else feedback(PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, app.label), isError = true)
	}

	private suspend fun cloneApp(source: PrismAppInfo, target: UserHandle, mode: @AppCloneMode Int) {
		val context = source.context(); val pkg = source.packageName
		DiagnosticLog.i(TAG, "cloneApp start pkg=$pkg targetUser=${target.toId()} mode=$mode system=${source.isSystem}")
		val capabilityRepo = CapabilityRepositoryProvider.get(context)
		val runtime = capabilityRepo.runtimeSnapshot()
		val plan = planCloneRoute(
			target.isParentProfile(),
			source.isSystem,
			mode,
			{ isInstallerUsable() },
			CloneRuntimeReadiness(runtime.shizukuReady, runtime.rootReady),
		)
		DiagnosticLog.i(
			TAG,
			"cloneApp route pkg=$pkg targetUser=${target.toId()} requestedMode=$mode " +
				"actual=${plan.route} fallback=${plan.usedNormalFallback}",
		)
			when (plan.route) {
				CloneRoute.PARENT_INSTALLER -> {
					@Suppress("DEPRECATION") // Only works in parent profile due to a bug in AOSP.
					activity.startActivityForResult(Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.fromParts("package", pkg, null)), 1)
					return
				}

				CloneRoute.ROOT -> {
					val result = installExistingViaRoot(context, target)
					if (result.installed) {
						// install-existing restores nothing else: a system/policy-imposed suspend or
						// hide on the fresh clone would leave it instantly unlaunchable. Clear it now,
						// while the clone flow is still on the call stack. Best-effort; su was just
						// used, so probing it here raises no new prompt.
						CloneSuspendRecovery.ensureUnsuspended(context, target, pkg, allowSuProbe = true)
						PrismAppListProvider.getInstance(context).refreshPackage(pkg, target, true)
						onCloneStateChanged()
						feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, source.label))
					} else {
						val localized = PrismLocale.wrap(context)
						feedback(
							if (result.rootAvailable) localized.getString(R.string.toast_cannot_clone, source.label)
							else localized.getString(R.string.toast_clone_root_unavailable),
							isError = true,
						)
					}
					return
				}

				CloneRoute.FILE_SYNC -> {
					val fallbackFrom = when (plan.requestedEnhancedRoute) {
						CloneRoute.ROOT -> PrismMode.Root
						CloneRoute.SHIZUKU -> PrismMode.Shizuku
						else -> null
					}
					cloneViaFileSync(context, source, fallbackFrom)
					return
				}

				CloneRoute.SYSTEM_ENABLE -> {
					analytics().event("clone_sys").with(Analytics.Param.ITEM_ID, pkg).send()
					enableSystemAppViaPolicy(target)
					return
				}

				CloneRoute.SHIZUKU -> {
					val component = ComponentName(context, PrivilegedRemoteWorker::class.java)
					val shizukuServiceTag = "clone-$pkg-${SystemClock.uptimeMillis()}"
					val args = UserServiceArgs(component).daemon(false).processNameSuffix(pkg).tag(shizukuServiceTag)
					val done = java.util.concurrent.atomic.AtomicBoolean(false)
					val main = Handler(Looper.getMainLooper())
					fun fail(reason: String) = feedback(
						PrismLocale.wrap(context).getString(R.string.lz_app_clone_shizuku_failed, reason),
						isError = true,
					)
					lateinit var conn: ServiceConnection
					conn = object : ServiceConnection {
						override fun onServiceConnected(name: ComponentName, service: IBinder) {
							DiagnosticLog.i(TAG, "Shizuku service connected pkg=$pkg targetUser=${target.toId()} name=$name")
							if (!done.compareAndSet(false, true)) return
							main.removeCallbacksAndMessages(null)
							vm.viewModelScope.launch {
								val result = try {
									transactPrivilegedClone(service, target)
								} finally {
									runCatching { Shizuku.unbindUserService(args, conn, true) }
								}
								DiagnosticLog.i(
									TAG,
									"Shizuku clone result pkg=$pkg targetUser=${target.toId()} code=${result.resultCode} " +
										"exceptionClass=${result.exceptionClass} message=${result.message}",
								)
								if (result.resultCode == 1) {
									capabilityRepo.markShizukuReady()
									PrismAppListProvider.getInstance(context).refreshPackage(pkg, target, true)
									onCloneStateChanged()
									feedback(PrismLocale.wrap(context).getString(R.string.toast_successfully_cloned, source.label))
								} else {
									if (!ShizukuUtil.isAuthorized()) capabilityRepo.markShizukuUnavailable()
									fail(result.userFacingReason())
								}
							}
						}

						override fun onServiceDisconnected(name: ComponentName?) {
							DiagnosticLog.i(TAG, "Shizuku service disconnected before completion pkg=$pkg targetUser=${target.toId()} name=$name")
							if (done.compareAndSet(false, true)) {
								capabilityRepo.markShizukuUnavailable()
								main.removeCallbacksAndMessages(null)
								fail("service_disconnected")
							}
						}
					}
					main.postDelayed({
						if (done.compareAndSet(false, true)) {
							capabilityRepo.markShizukuUnavailable()
							runCatching { Shizuku.unbindUserService(args, conn, true) }
							fail("service_timeout")
						}
					}, 20_000)
					try {
						DiagnosticLog.i(TAG, "Binding Shizuku service pkg=$pkg targetUser=${target.toId()}")
						Shizuku.bindUserService(args, conn)
					} catch (e: Throwable) {
						DiagnosticLog.e(TAG, "Shizuku bindUserService failed for $pkg", e)
						if (done.compareAndSet(false, true)) {
							capabilityRepo.markShizukuUnavailable()
							main.removeCallbacksAndMessages(null)
							fail(e.javaClass.simpleName.ifBlank { "bind_failed" })
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
		private fun cloneViaFileSync(context: Context, source: PrismAppInfo, fallbackFrom: PrismMode?) {
			analytics().event("clone_file_sync").with(Analytics.Param.ITEM_ID, pkg).send()
			val apks = fullApkSet(source)
			if (apks.isEmpty()) {
				feedback(PrismLocale.wrap(context).getString(R.string.toast_cannot_clone, source.label), isError = true)
				return
			}
			val localized = PrismLocale.wrap(context)
			feedback(if (fallbackFrom == null) {
				localized.getString(R.string.toast_clone_file_sync_transferring)
			} else {
				localized.getString(
					R.string.toast_clone_file_sync_fallback,
					localized.getString(prismModeLabelRes(fallbackFrom)),
				)
			})
			val prepared = mutableStateOf(false)
			val preparationError = mutableStateOf<String?>(null)
			val progressSheet = ModelBottomSheetFragment()
			progressSheet.show(activity) {
			    PrismTheme {
			        ClonePreparationSheet(source.label.toString(), prepared.value, preparationError.value,
			            onInstall = {
			                val openResult = FileBridgeService().openProfileInstallEntry(activity)
			                if (openResult.success) progressSheet.dismiss()
			                else feedback(openResult.message, isError = true)
			            }, onDismiss = { progressSheet.dismiss() })
			    }
			}
			vm.viewModelScope.launch {
				val result = stageApkSetToProfile(context, source, apks, onActivating = {
					feedback(PrismLocale.wrap(context).getString(R.string.prompt_activating_space), isError = false)
				})

				if (!result.success) {
					preparationError.value = result.message
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
				onCloneStateChanged()

				prepared.value = true
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

internal data class PrivilegedCloneReply(
	val resultCode: Int,
	val exceptionClass: String?,
	val message: String?,
) {
	fun userFacingReason(): String {
		val type = exceptionClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
		val detail = message?.replace(Regex("\\s+"), " ")?.trim()?.take(120)?.takeIf { it.isNotBlank() }
		return listOfNotNull(type, detail).joinToString(": ").ifBlank { "result_$resultCode" }
	}
}

private const val TAG = "Prism.AC"

enum class CloneRequestOutcome { Started, Unavailable }

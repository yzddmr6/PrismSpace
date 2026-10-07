package com.yzddmr6.prismspace.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.EXTRA_USER
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.content.pm.PackageManager.MATCH_SYSTEM_ONLY
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.ArrayMap
import android.util.Log
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.bridge.AppListPort
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.DEFAULT_PROFILE_APP_PAGE_SIZE
import com.yzddmr6.prismspace.bridge.ProfileAppEntry
import com.yzddmr6.prismspace.bridge.ProfileAppPage
import com.yzddmr6.prismspace.bridge.ProfileAppPageAccumulator
import com.yzddmr6.prismspace.bridge.QueryProfileAppsPage
import com.yzddmr6.prismspace.bridge.clampProfileAppPageSize
import com.yzddmr6.prismspace.common.app.AppInfo
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.common.app.AppListProvider
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.provisioning.SystemAppListSnapshot
import com.yzddmr6.prismspace.provisioning.SystemAppPolicyRuntime
import com.yzddmr6.prismspace.provisioning.SystemAppsManager
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.isParentProfile
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

/**
 * PrismSpace-specific [AppListProvider]
 *
 * Created by Oasis on 2016/8/10.
 */
class PrismAppListProvider : AppListProvider<PrismAppInfo>() {

	override fun onStartLoadingApps(apps: MutableMap<String, PrismAppInfo>) {
		super.onStartLoadingApps(apps)
		// Some HyperOS builds omit system packages from the general query and expose launchable ones
		// only through ResolveInfo snapshots whose flags are incomplete. Re-querying the system set
		// explicitly makes PackageManager's ApplicationInfo the authoritative classification source.
		context().packageManager
			.getInstalledApplications(PM_FLAGS_APP_INFO or MATCH_SYSTEM_ONLY)
			.forEach { info -> apps[info.packageName] = createEntry(info, apps[info.packageName]) }
	}

	operator fun get(pkg: String, profile: UserHandle): PrismAppInfo? {
		return if (profile.isParentProfile()) super.get(pkg) else loadAppsInProfileIfNotYet(profile)[pkg]
	}

	fun addPlaceholder(pkg: String, profile: UserHandle) {
		if (get(pkg, profile) != null) return
		val info = getApplicationInfoIncludingUninstalled(pkg, profile) ?: return

		Log.i(TAG, "Add placeholder for $pkg in profile ${profile.toId()}")
		val app = PrismAppInfo(this, profile, info, null)
		mPrismAppMap[profile]!![pkg] = app
		notifyUpdate(setOf(app))
	}

	fun removePlaceholder(pkg: String, profile: UserHandle) {
		val appsInProfile = mPrismAppMap[profile] ?: return
		val app = appsInProfile[pkg] ?: return
		if (! app.isPlaceHolder) return
		Log.i(TAG, "Remove placeholder for $pkg in profile ${profile.toId()}")
		appsInProfile.remove(pkg)
		notifyUpdate(setOf(app))
	}

	fun isInstalled(pkg: String, profile: UserHandle) = get(pkg, profile)?.run { installed && shouldShowAsEnabled() } == true

	fun isExclusive(app: PrismAppInfo): Boolean {
		if (app.user.isParentProfile() && ! Users.hasProfile()) return true
		return Users.getProfilesManagedByPrism().asSequence().plus(Users.parentProfile).minus(app.user).all { profile ->
			! isInstalled(app.packageName, profile) }
	}

	override fun createEntry(current: ApplicationInfo, last: PrismAppInfo?): PrismAppInfo {
		return PrismAppInfo(this, UserHandles.getUserHandleForUid(current.uid), current, last)
	}

	fun createEntryWithLabel(current: ApplicationInfo, last: PrismAppInfo?, label: CharSequence): PrismAppInfo {
		return PrismAppInfo(this, UserHandles.getUserHandleForUid(current.uid), current, last, label)
	}

	override fun onAppLabelUpdate(pkg: String, label: String) {
		super.onAppLabelUpdate(pkg, label)
		Users.getProfilesManagedByPrism().forEach { profile ->
			val appsInProfile = mPrismAppMap[profile] ?: return@forEach
			val entry = appsInProfile[pkg] ?: return@forEach
			Log.d(TAG, "Label updated for $pkg in profile ${profile.toId()}: $label")
			val newEntry = PrismAppInfo(this, Users.profile, entry, null)
			appsInProfile[pkg] = newEntry
			notifyUpdate(setOf(newEntry))
		}
	}

	fun installedApps(profile: UserHandle): Collection<PrismAppInfo>
	= if (profile.isParentProfile()) installedAppsInOwnerUser() else loadAppsInProfileIfNotYet(profile).values

	private fun loadAppsInProfileIfNotYet(profile: UserHandle): Map<String, PrismAppInfo>
	= if (! Users.isProfileManagedByPrism(context(), profile)) emptyMap() else mPrismAppMap.getOrPut(profile) { refresh(profile) }

	private fun initializeMonitor() {
		Log.d(TAG, "Initializing monitor...")

		mLauncherApps.registerCallback(mCallback, Handler(Looper.getMainLooper()))

		context().registerReceiver(object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) { @Suppress("DEPRECATION")
				val profile = intent.getParcelableExtra<UserHandle>(EXTRA_USER) ?: return
				Log.i(TAG, "Profile removed: ${profile.toId()}")
				mPrismAppMap[profile]?.clear()
			}
		}, IntentFilter(Intent.ACTION_MANAGED_PROFILE_REMOVED))
	}

	private fun refresh(profile: UserHandle): ArrayMap<String, PrismAppInfo> {
		Log.d(TAG, "Refresh apps in PrismSpace ${profile.toId()}")
		// On recent Android builds, MATCH_UNINSTALLED_PACKAGES or MATCH_ALL in the parent user
		// no longer returns frozen apps in PrismSpace.
		var snapshotsByPackage: Map<String, ProfileAppEntry> = emptyMap()
		val appsInProfile = if (profile != Users.current()) {
			when (val outcome = queryProfileApps(profile)) {
				is ShuttleOutcome.Value -> outcome.value?.also { entries ->
					snapshotsByPackage = entries.associateBy(ProfileAppEntry::packageName)
					// Policy state is profile-side truth; the parent only caches it per snapshot.
					mPolicyHidden[profile] = entries.filter(ProfileAppEntry::policyHidden).mapTo(HashSet(), ProfileAppEntry::packageName)
					mLauncherEntries[profile] = entries.filter(ProfileAppEntry::launcherEntry).mapTo(HashSet(), ProfileAppEntry::packageName)
					mEntryActions[profile] = entries.mapNotNull { e -> e.entryAction?.let { e.packageName to it } }.toMap()
				}?.asSequence()?.map(ProfileAppEntry::toApplicationInfo)
				is ShuttleOutcome.NotReady -> null.also {
					Log.w(TAG, "Unable to refresh profile apps user=${profile.toId()}: shuttle not ready ${outcome.cause}")
				}
				ShuttleOutcome.TimedOut -> null.also {
					Log.w(TAG, "Unable to refresh profile apps user=${profile.toId()}: shuttle timed out")
				}
				is ShuttleOutcome.Failed -> null.also {
					Log.w(TAG, "Unable to refresh profile apps user=${profile.toId()}", outcome.error)
				}
				is ShuttleOutcome.Skipped -> null.also {
					Log.w(TAG, "Unable to refresh profile apps user=${profile.toId()}: ${outcome.reason}")
				}
			}
		} else (context().packageManager.getInstalledApplications(PM_FLAGS_APP_INFO)
				+ context().queryLaunchableAppsInCurrentUser()).asSequence()
		// Mix visible apps from LauncherApps with invisible frozen apps from PackageManager.
		// Collect all visible (unfrozen) apps first in one API call, to reduce later getAppInfo() calls.
		val visibleOrKnownApps = mLauncherApps.getActivityList(null, profile).asSequence()
			.map { it.applicationInfo }.associateBy { it.packageName }.let { visible ->
				installedAppsInOwnerUser().asSequence().mapNotNull { visible[it.packageName] ?: getAppInfo(it.packageName, profile) }}
		val appsInProfileList = appsInProfile?.toList().orEmpty()
		val visibleOrKnownAppList = visibleOrKnownApps.toList()
		val apps = mergeAppsByPackage(appsInProfileList.asSequence(), visibleOrKnownAppList.asSequence())
		return apps.associateByTo(ArrayMap(), ApplicationInfo::packageName) { info ->
			PrismAppInfo(this, profile, info, null).also { app ->
				snapshotsByPackage[info.packageName]?.let { snapshot ->
					app.setHidden(snapshot.hidden)
				}
			}
		}
	}

	private fun queryProfileApps(profile: UserHandle): ShuttleOutcome<List<ProfileAppEntry>> {
		val target = BridgeTargets.profile(profile.toId())
			?: return ShuttleOutcome.Skipped("profile_missing")
		val accumulator = ProfileAppPageAccumulator()
		var pageIndex = 0
		do {
			when (val outcome = Bridge.inProfile(context(), target).execute(
				QueryProfileAppsPage(pageIndex, DEFAULT_PROFILE_APP_PAGE_SIZE),
			)) {
				is ShuttleOutcome.Value -> {
					val page = outcome.value ?: return ShuttleOutcome.Failed(
						IllegalStateException("Profile app page returned null"),
					)
					if (!accumulator.accept(page)) return ShuttleOutcome.Value(accumulator.entries)
					pageIndex++
				}
				is ShuttleOutcome.NotReady -> return outcome
				ShuttleOutcome.TimedOut -> return ShuttleOutcome.TimedOut
				is ShuttleOutcome.Failed -> return outcome
				is ShuttleOutcome.Skipped -> return outcome
			}
		} while (pageIndex < MAX_PROFILE_APP_PAGES)
		return ShuttleOutcome.Failed(IllegalStateException("Profile app pagination exceeded $MAX_PROFILE_APP_PAGES pages"))
	}

	private fun getAppInfo(pkg: String, profile: UserHandle): ApplicationInfo? {
		// Use getApplicationInfoIncludingUninstalled() to include frozen packages and then exclude non-installed packages.
		return getApplicationInfoIncludingUninstalled(pkg, profile)?.takeIf { it.installed }
	}
	private fun getApplicationInfoIncludingUninstalled(pkg: String, profile: UserHandle): ApplicationInfo? {
		return LauncherAppsCompat.getApplicationInfoNoThrows(mLauncherApps, pkg, MATCH_UNINSTALLED_PACKAGES, profile)
	}

	private fun Context.queryLaunchableAppsInCurrentUser(): List<ApplicationInfo> {
		val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
		return packageManager.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.applicationInfo }
	}

	fun refreshPackage(pkg: String, profile: UserHandle, add: Boolean) {
		Log.d(TAG, "Update: " + pkg + if (add) " for pkg add" else " for pkg change")
		val info = getAppInfo(pkg, profile)
		val appsInProfile = mPrismAppMap[profile] ?: return
		if (info == null) {
			appsInProfile.remove(pkg)?.also { notifyRemoval(setOf(it)) }
			return }
		val last = appsInProfile[pkg]
		val app = PrismAppInfo(this, profile, info, last?.takeIf { it.isPlaceHolder })
		if (add && app.isHidden) {
			Log.w(TAG, "Correct the flag for unhidden package: $pkg")
			app.isHidden = false }
		appsInProfile[pkg] = app
		notifyUpdate(setOf(app))
	}

	/** Whether the profile-side system app policy targets [pkg] as unavailable in [profile]. */
	fun isPolicyHidden(profile: UserHandle, pkg: String): Boolean = mPolicyHidden[profile]?.contains(pkg) == true

	/** Enabled launcher entry from the last profile snapshot; null when no snapshot covers [profile]. */
	fun snapshotLauncherEntry(profile: UserHandle, pkg: String): Boolean? = mLauncherEntries[profile]?.contains(pkg)

	/** Profile-side action that opens [pkg]'s UI when it has no launcher entry (e.g. HyperOS Settings). */
	fun snapshotEntryAction(profile: UserHandle, pkg: String): String? = mEntryActions[profile]?.get(pkg)

	/** Records the outcome of a system app policy change, then re-reads the touched packages. */
	fun applyPolicyResult(profile: UserHandle, available: Collection<String>, unavailable: Collection<String>) {
		mPolicyHidden.compute(profile) { _, current -> (current.orEmpty() - available.toSet() + unavailable).toHashSet() }
		(available + unavailable).forEach { pkg -> refreshPackage(pkg, profile, false) }
	}

	/** Freezing or disabling a critical app may cause malfunction to other apps or the whole system.  */
	fun isCritical(pkg: String): Boolean {
		return mCriticalSystemPackages.contains(pkg)
	}

	private val mCallback: LauncherApps.Callback = object : LauncherApps.Callback() {

		override fun onPackageRemoved(pkg: String, profile: UserHandle) {
			val appsInProfile = mPrismAppMap[profile] ?: return
			val app = appsInProfile[pkg] ?: return Unit.also { Log.e(TAG, "Removed package not found in PrismSpace: $pkg") }
			if (app.isHidden) return  // The removal callback is triggered by freezing.
			val info = getAppInfo(pkg, profile)
			if (info != null && info.flags and ApplicationInfo.FLAG_INSTALLED != 0) {    // Frozen
				val newInfo = PrismAppInfo(this@PrismAppListProvider, profile, info, appsInProfile[pkg])
				if (!newInfo.isHidden) {
					Log.w(TAG, "Correct the flag for hidden package: $pkg")
					newInfo.isHidden = true
				}
				appsInProfile[pkg] = newInfo
				notifyUpdate(setOf(newInfo))
			} else {    // Uninstalled in profile
				val removedApp = appsInProfile.remove(pkg)
				if (removedApp != null) notifyRemoval(setOf(removedApp))
			}
		}

		override fun onPackageAdded(pkg: String, user: UserHandle) {
			refreshPackage(pkg, user, true)
		}

		override fun onPackageChanged(pkg: String, user: UserHandle) {
			refreshPackage(pkg, user, false)
		}

		override fun onPackagesSuspended(pkgs: Array<out String>, user: UserHandle) = pkgs.forEach { pkg ->
			refreshPackage(pkg, user, false)
		}

		override fun onPackagesUnsuspended(pkgs: Array<out String>, user: UserHandle) = pkgs.forEach { pkg ->
			refreshPackage(pkg, user, false)
		}

		override fun onPackagesAvailable(pkgs: Array<String>, user: UserHandle, replacing: Boolean) { Log.e(TAG, "onPackagesAvailable() is unsupported") }
		override fun onPackagesUnavailable(pkgs: Array<String>, user: UserHandle, replacing: Boolean) { Log.e(TAG, "onPackagesUnavailable() is unsupported") }
	}

	private val mPrismAppMap by lazy { initializeMonitor(); ArrayMap<UserHandle, MutableMap<String, PrismAppInfo>>() }
	private val mLauncherApps by lazy { context().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps }
	private val mPolicyHidden = ConcurrentHashMap<UserHandle, Set<String>>()
	private val mLauncherEntries = ConcurrentHashMap<UserHandle, Set<String>>()
	private val mEntryActions = ConcurrentHashMap<UserHandle, Map<String, String>>()
	private val mCriticalSystemPackages by lazy { SystemAppsManager.detectCriticalSystemPackages(context().packageManager) }

	companion object {
		@JvmStatic fun getInstance(context: Context): PrismAppListProvider = AppListProvider.getInstance(context)
		@JvmStatic fun excludeSelf(context: Context) = exclude(context.packageName)
		private fun exclude(pkg: String) = Predicate { app: PrismAppInfo -> pkg != app.packageName }
		private const val MAX_PROFILE_APP_PAGES = 1_000
	}
}

internal object MobileAppListPort : AppListPort {
    override fun queryProfileApps(context: Context, pageIndex: Int, pageSize: Int): ProfileAppPage {
        val effectiveSize = clampProfileAppPageSize(pageSize)
        val effectiveIndex = pageIndex.coerceAtLeast(0)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installedFlags = MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS
        val apps = (context.packageManager.getInstalledApplications(installedFlags) +
            // HyperOS may omit non-launchable system packages from the general query even with
            // QUERY_ALL_PACKAGES. An explicit system query is the platform-level contract for this view.
            context.packageManager.getInstalledApplications(installedFlags or MATCH_SYSTEM_ONLY) +
            context.packageManager.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.applicationInfo })
            .associateBy { it.packageName }
            .values
            .sortedBy { it.packageName }
        val startLong = effectiveIndex.toLong() * effectiveSize
        if (startLong >= apps.size) return ProfileAppPage(emptyList(), false)
        val start = startLong.toInt()
        val end = minOf(start + effectiveSize, apps.size)
        // The policy is evaluated once per page, not per entry.
        val policy = runCatching { SystemAppPolicyRuntime.listSnapshot(context) }.getOrElse { error ->
            DiagnosticLog.w(TAG, "system app policy snapshot unavailable", error)
            SystemAppListSnapshot.EMPTY
        }
        return ProfileAppPage(
            apps.subList(start, end).map { it.toProfileAppEntry(context, policy) },
            hasMore = end < apps.size,
        )
    }

    private fun ApplicationInfo.toProfileAppEntry(context: Context, policy: SystemAppListSnapshot): ProfileAppEntry {
        val hidden = AppInfo.isHidden(this) ?: runCatching {
            !context.getSystemService(LauncherApps::class.java).isPackageEnabled(packageName, Users.current())
        }.getOrDefault(false)
        return ProfileAppEntry(
            packageName = packageName,
            uid = uid,
            flags = flags,
            hidden = hidden,
            enabled = enabled,
            targetSdkVersion = targetSdkVersion,
            label = runCatching { loadLabel(context.packageManager).toString() }.getOrDefault(packageName),
            iconResource = icon,
            sourceDir = sourceDir,
            publicSourceDir = publicSourceDir,
            splitSourceDirs = splitSourceDirs?.toList().orEmpty(),
            launcherEntry = packageName in policy.enabledLauncherPackages,
            policyHidden = packageName in policy.policyHidden,
            entryAction = policy.entryActions[packageName],
        )
    }
}

private fun ProfileAppEntry.toApplicationInfo() = ApplicationInfo().also { info ->
    info.packageName = packageName
    info.uid = uid
    info.flags = flags
    info.enabled = enabled
    info.targetSdkVersion = targetSdkVersion
    info.nonLocalizedLabel = label
    info.icon = iconResource
    info.sourceDir = sourceDir
    info.publicSourceDir = publicSourceDir
    info.splitSourceDirs = splitSourceDirs.toTypedArray().takeIf { it.isNotEmpty() }
}

private const val TAG = "Prism.ALP"

internal fun mergeAppsByPackage(
	profileApps: Sequence<ApplicationInfo>, visibleOrKnownApps: Sequence<ApplicationInfo>
): Sequence<ApplicationInfo> {
	val merged = LinkedHashMap<String, ApplicationInfo>()
	visibleOrKnownApps.forEach { merged[it.packageName] = it }
	profileApps.forEach { merged[it.packageName] = it }
	return merged.values.asSequence()
}

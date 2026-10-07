package com.yzddmr6.prismspace.util

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.*
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.home.HomeRole
import com.yzddmr6.prismspace.util.PseudoContentProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume

/**
 * Utility class for user-related helpers. Only works within the process where this provider is declared to be running.
 *
 * Created by Oasis on 2016/9/25.
 */
class Users : PseudoContentProvider() {

	override fun onCreate(): Boolean {
		Log.v(TAG, "onCreate()")
		val priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
		val filter = IntentFilters.forActions(
			Intent.ACTION_MANAGED_PROFILE_ADDED,
			Intent.ACTION_MANAGED_PROFILE_REMOVED,
			Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
			Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
			Intent.ACTION_USER_UNLOCKED,
			Intent.ACTION_MY_PACKAGE_REPLACED,
			DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED,
		).inPriority(priority)
		if (SDK_INT >= TIRAMISU) {
			context().registerReceiver(mProfileChangeObserver, filter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			@Suppress("UnspecifiedRegisterReceiverFlag")
			context().registerReceiver(mProfileChangeObserver, filter)
		}
		refreshUsers(context())
		return true
	}

	private val mProfileChangeObserver: BroadcastReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
		val user = intent.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
		val action = intent.action ?: return
		Log.i(TAG, "User state changed action=$action user=${user?.toId() ?: NULL_ID}")
		refreshUsers(context)
		notifyChanged(action)
	}}

	companion object {
		const val ACTION_USER_INFO_CHANGED = "android.intent.action.USER_INFO_CHANGED"  // Hidden in Intent
		const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"                // Hidden in Intent

		@JvmField var profile: UserHandle? = null // The first profile managed by PrismSpace (semi-immutable, until profile is created or destroyed)
		@JvmStatic lateinit var parentProfile: UserHandle; private set
		@JvmStatic fun hasProfile() = profile != null

		private val CURRENT: UserHandle = Process.myUserHandle()
		private val CURRENT_ID = CURRENT.toId()
		private val changeListeners = CopyOnWriteArraySet<(String) -> Unit>()
		@JvmStatic fun current() = CURRENT
		@JvmStatic fun currentId() = CURRENT_ID
		const val NULL_ID = -10000

		/** Reuses the provider's single persistent receiver as the process-wide invalidation source. */
		@JvmStatic fun addChangeListener(listener: (String) -> Unit) {
			changeListeners += listener
		}

		private fun notifyChanged(action: String) {
			changeListeners.forEach { it(action) }
		}

		/** This method should not be called under normal circumstance.  */
		@JvmStatic fun refreshUsers(context: Context) {
			mDebugBuild = context.applicationInfo.flags and FLAG_DEBUGGABLE != 0
			val um = context.getSystemService<UserManager>()!!
			// User ids are opaque Android identifiers. Profiles such as Samsung Secure Folder and
			// vendor clone users legitimately use ids >= 100, so classification must use evidence.
			val profiles = um.userProfiles.orEmpty()
			sProfileCount = profiles.size
			val profilesByPrism = ArrayList<UserHandle>(profiles.size)
			parentProfile = profiles.firstOrNull() ?: CURRENT
			sCurrentProfileManagedByPrism = false
			if (parentProfile == CURRENT) {      // Running in parent profile
				val uiModule = Modules.getMainLaunchActivity(context).packageName
				val la = context.getSystemService<LauncherApps>()!!
				val activityInOwner = la.getActivityList(uiModule, CURRENT).firstOrNull()?.name
				if (activityInOwner == null) Log.w(TAG, "Main launcher activity is unavailable; ownership marker cannot be read")
				for (profile in profiles.filterNot { it == parentProfile }) {
					val marked = activityInOwner != null && la.getActivityList(uiModule, profile).orEmpty()
						.any { activity -> activity.name != activityInOwner }
					if (marked) profilesByPrism.add(profile).also {
						Log.i(TAG, "Profile managed by PrismSpace: ${profile.toId()}")
					} else Log.i(TAG, "Profile not managed by PrismSpace: ${profile.toId()}")
				}
			} else {
				sCurrentProfileManagedByPrism = runCatching { DevicePolicies(context).isProfileOwner }.getOrDefault(false)
				for (user in profiles.filterNot { it == parentProfile })
					if (user != CURRENT) Log.w(TAG, "Skip sibling profile (may not managed by PrismSpace): ${user.toId()}")
					else if (sCurrentProfileManagedByPrism) profilesByPrism.add(user).also {
						Log.i(TAG, "Profile managed by PrismSpace: ${user.toId()}")
					} else Log.w(TAG, "Current profile is not managed by PrismSpace: ${user.toId()}")
			}

			profile = profilesByPrism.lastOrNull()

			profilesByPrism.sortWith(Comparator.comparing { um.getSerialNumberForUser(it) })
			sProfilesManagedByPrism = profilesByPrism
		}

			fun isProfileRunning(context: Context, user: UserHandle): Boolean {
				if (CURRENT == user) return true
				val um = context.getSystemService<UserManager>()!!
				if (SDK_INT >= N_MR1)
					try { return um.isUserRunning(user) }
					catch (e: RuntimeException) { Log.w(TAG, "Error checking running state for user ${user.toId()}") }
				return ! isProfileQuietModeEnabled(context, user)
			}

			fun isProfileQuietModeEnabled(context: Context, user: UserHandle): Boolean {
				if (CURRENT == user) return false
				return try {
					context.getSystemService<UserManager>()!!.isQuietModeEnabled(user)
				} catch (e: RuntimeException) {
					Log.w(TAG, "Error checking quiet mode for user ${user.toId()}", e)
					false
				}
			}

			fun isProfileAvailable(context: Context, user: UserHandle): Boolean =
				isProfileRunning(context, user) && ! isProfileQuietModeEnabled(context, user)

		/**
		 * Read-only: is there a vendor CLONE profile (e.g. XSpace) in this profile group? Callable from
		 * both spaces; blocking binder calls, so call off the main thread. Kept out of [refreshUsers] and
		 * space classification on purpose: it only drives an explanatory notice.
		 */
		@JvmStatic fun vendorCloneProfilePresence(context: Context): CloneProfilePresence {
			if (SDK_INT < VANILLA_ICE_CREAM) return VendorCloneProfiles.presence(SDK_INT, emptyList())
				.also { logClonePresence(it, 0, 0) }
			val profiles = runCatching { context.getSystemService<UserManager>()!!.userProfiles.orEmpty() }.getOrDefault(emptyList())
			val launcherApps = context.getSystemService<LauncherApps>()
			val types = profiles.map { profile -> runCatching { launcherApps?.getLauncherUserInfo(profile)?.userType }.getOrNull() }
			return VendorCloneProfiles.presence(SDK_INT, types)
				.also { logClonePresence(it, profiles.size, types.count { type -> type == null }) }
		}

		@Volatile private var lastClonePresenceLog: String? = null

		/** Logged only when the value changes within this process. */
		private fun logClonePresence(presence: CloneProfilePresence, profiles: Int, unreadable: Int) {
			val line = "clone_profile presence=$presence sdk=$SDK_INT profiles=$profiles unreadable=$unreadable"
			if (line == lastClonePresenceLog) return
			lastClonePresenceLog = line
			DiagnosticLog.i(TAG, line)
		}

		@JvmStatic fun isSystemUser() = CURRENT_ID == 0
		@JvmStatic fun isParentProfile() = CURRENT_ID == parentProfile.toId()
		@JvmStatic fun UserHandle?.isParentProfile() = this == parentProfile
		@JvmStatic fun isParentProfile(userId: Int) = userId == parentProfile.toId()
		@JvmStatic fun isCurrentProfileManagedByPrism() = sCurrentProfileManagedByPrism

		@OwnerUser @JvmStatic fun isProfileManagedByPrism(context: Context, user: UserHandle): Boolean {
			ensureParentProfile()
			if (user.isParentProfile()) {
				if (isParentProfile()) return DevicePolicies(context).isProfileOwner
				throw IllegalArgumentException("Not working for profile parent user") }
			return isProfileManagedByPrism(user)
		}

		@OwnerUser @JvmStatic fun isProfileManagedByPrism(user: UserHandle): Boolean {
			ensureParentProfile()
			return ! user.isParentProfile() && sProfilesManagedByPrism.contains(user)
		}

		/** Excluding parent profile */
		@OwnerUser @JvmStatic fun getProfilesManagedByPrism() = sProfilesManagedByPrism.also { ensureParentProfile() }
		/** Including parent profile and profiles not managed by PrismSpace (probably created by other DPC in non-primary user. */
		fun getProfileCount() = sProfileCount
		@JvmStatic fun UserHandle.toId() = hashCode()
		@JvmStatic fun isSameApp(uid1: Int, uid2: Int) = getAppId(uid1) == getAppId(uid2)
		private fun getAppId(uid: Int) = uid % PER_USER_RANGE
		private fun ensureParentProfile() = check(! mDebugBuild || isParentProfile()) { "Not called in owner user" }

		fun getUserBadgedIcon(context: Context, icon: Drawable, user: UserHandle) =
			try { context.packageManager.getUserBadgedIcon(icon, user) }
			catch (e: SecurityException) {    // (Mostly "Vivo" devices before Android Q) "SecurityException: You need MANAGE_USERS permission to: check if specified user a managed profile outside your profile group"
				icon.also { if (SDK_INT >= Q) analytics().logAndReport(TAG, "Error getting user badged icon", e) }}

		/** @return Whether the request is successful, false may indicate failure or timeout. */
			@RequiresApi(P) suspend fun requestQuietModeDisabled(context: Context, profile: UserHandle,
			                                                     timeout: Long = ACTIVATION_TIMEOUT) = coroutineScope {
				val um = context.getSystemService<UserManager>()!!
				if (isProfileAvailable(context, profile) && um.isUserUnlocked(profile)) {
					Log.i(TAG, "PrismSpace ${profile.toId()} is already available")
					return@coroutineScope true
				}
				val intent = waitForBroadcast(context, Intent.ACTION_MANAGED_PROFILE_AVAILABLE, timeout) {
					launch {
						Log.i(TAG, "Activating PrismSpace ${profile.toId()}...")
						val activating = runCatching {
							if (DevicePolicies(context).isProfileOrDeviceOwnerOnCallingUser) {
								HomeRole.runWithHomeRole(context) { um.requestQuietModeEnabled(false, profile) }
							} else {
								// A normal parent app cannot change DPM preferred activities. Launching
								// its profile entry lets Android present the work-profile activation UI.
								val launcher = context.getSystemService<LauncherApps>()!!
								val entry = launcher.getActivityList(context.packageName, profile).firstOrNull()
								if (entry == null) false else {
									launcher.startMainActivity(entry.componentName, profile, null, null)
									true
								}
							}
						}.onFailure { e ->
							Log.e(TAG, "Failed to request quiet mode disabled for user ${profile.toId()}", e)
						}.getOrDefault(false)
						if (! activating) it.resume(null)
						Log.i(TAG, "Waiting for PrismSpace ${profile.toId()} to be ready...") }}
			val user = intent?.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
				val ready = user == profile && isProfileAvailable(context, profile)
				Log.i(TAG, "PrismSpace ${profile.toId()} activation confirmed=$ready")
				return@coroutineScope ready
		}

		private const val ACTIVATION_TIMEOUT: Long = 15_000		// May need to wait for user credential

		private var mDebugBuild = false
		private var sProfileCount: Int = 0
		@Volatile private var sCurrentProfileManagedByPrism = false
		private var sProfilesManagedByPrism: List<UserHandle> = emptyList() // Also safe if initialized in another process.
		private const val PER_USER_RANGE = 100000
		private const val TAG = "Prism.Users"
	}
}

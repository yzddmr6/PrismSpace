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
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.home.HomeRole
import com.yzddmr6.prismspace.util.PseudoContentProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
		context().registerReceiver(mProfileChangeObserver,
			IntentFilters.forActions(Intent.ACTION_MANAGED_PROFILE_ADDED,  // ACTION_MANAGED_PROFILE_ADDED is sent by DevicePolicyManagerService.setProfileEnabled()
				Intent.ACTION_MANAGED_PROFILE_REMOVED,
				DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED // ACTION_PROFILE_OWNER_CHANGED is sent after "dpm set-profile-owner ..."
			).inPriority(priority))
		refreshUsers(context())
		return true
	}

	private val mProfileChangeObserver: BroadcastReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
		val added = intent.action == Intent.ACTION_MANAGED_PROFILE_ADDED
		val user = intent.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
		Log.i(TAG, (if (added) "Profile added: " else "Profile removed: ") + (user?.toId()?.toString() ?: "null"))
		refreshUsers(context)
	}}

	companion object {
		const val ACTION_USER_INFO_CHANGED = "android.intent.action.USER_INFO_CHANGED"  // Hidden in Intent
		const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"                // Hidden in Intent

		@JvmField var profile: UserHandle? = null // The first profile managed by PrismSpace (semi-immutable, until profile is created or destroyed)
		@JvmStatic lateinit var parentProfile: UserHandle; private set
		@JvmStatic fun hasProfile() = profile != null

		private val CURRENT: UserHandle = Process.myUserHandle()
		private val CURRENT_ID = CURRENT.toId()
		@JvmStatic fun current() = CURRENT
		@JvmStatic fun currentId() = CURRENT_ID
		const val NULL_ID = -10000

		/** This method should not be called under normal circumstance.  */
		@JvmStatic fun refreshUsers(context: Context) {
			mDebugBuild = context.applicationInfo.flags and FLAG_DEBUGGABLE != 0
			val um = context.getSystemService<UserManager>()!!
			val profiles = um.userProfiles.filter { profile -> (profile.toId() < 100).also {	// "Secure Folder" on Samsung devices uses user ID 150.
				if (! it) Log.w(TAG, "Skip profile ${profile.toId()} (most probably not normal profile)") }}
			sProfileCount = profiles.size
			val profilesByPrism = ArrayList<UserHandle>(profiles.size - 1)
			parentProfile = profiles[0]
			if (parentProfile == CURRENT) {      // Running in parent profile
				val uiModule = Modules.getMainLaunchActivity(context).packageName
				val la = context.getSystemService<LauncherApps>()!!
				val activityInOwner = la.getActivityList(uiModule, CURRENT)[0].name
				for (profile in profiles.drop(1)/* skip parent */)
					for (activity in la.getActivityList(uiModule, profile))
						// Separate "PrismSpace Settings" launcher activity is enabled, only if profile is managed by PrismSpace.
						if (activity.name == activityInOwner) Log.i(TAG, "Profile not managed by PrismSpace: ${profile.toId()}")
						else profilesByPrism.add(profile).also { Log.i(TAG, "Profile managed by PrismSpace: ${profile.toId()}") }
			} else for (user in profiles.drop(1)/* skip parent */)
				if (user != CURRENT) Log.w(TAG, "Skip sibling profile (may not managed by PrismSpace): ${user.toId()}")
				else profilesByPrism.add(user).also { Log.i(TAG, "Profile managed by PrismSpace: ${user.toId()}") }

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

		@JvmStatic fun isSystemUser() = CURRENT_ID == 0
		@JvmStatic fun isParentProfile() = CURRENT_ID == parentProfile.toId()
		@JvmStatic fun UserHandle?.isParentProfile() = this == parentProfile
		@JvmStatic fun isParentProfile(userId: Int) = userId == parentProfile.toId()

		@OwnerUser @JvmStatic fun isProfileManagedByPrism(context: Context, user: UserHandle): Boolean {
			ensureParentProfile()
			if (user.isParentProfile()) {
				if (isParentProfile()) return DevicePolicies(context).isProfileOwner
				throw IllegalArgumentException("Not working for profile parent user") }
			return sProfilesManagedByPrism.contains(user)
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
							HomeRole.runWithHomeRole(context) {
								um.requestQuietModeEnabled(false, profile) }
						}.onFailure { e ->
							Log.e(TAG, "Failed to request quiet mode disabled for user ${profile.toId()}", e)
						}.getOrDefault(false)
						if (! activating) it.resume(null)
						Log.i(TAG, "Waiting for PrismSpace ${profile.toId()} to be ready...") }}
			val user = intent?.getParcelableExtra<UserHandle>(Intent.EXTRA_USER)
			Log.i(TAG, "PrismSpace ${user?.toId()} is ready")
			return@coroutineScope user != null
		}

		private const val ACTIVATION_TIMEOUT: Long = 15_000		// May need to wait for user credential

		private var mDebugBuild = false
		private var sProfileCount: Int = 0
		private lateinit var sProfilesManagedByPrism: List<UserHandle> //  class is accidentally used in other process.
		private const val PER_USER_RANGE = 100000
		private const val TAG = "Prism.Users"
	}
}

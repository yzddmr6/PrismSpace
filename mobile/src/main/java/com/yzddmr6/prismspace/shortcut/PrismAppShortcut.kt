package com.yzddmr6.prismspace.shortcut

import com.yzddmr6.prismspace.analytics.DiagnosticLog
import android.app.Activity
import android.app.ActivityManager
import android.app.Service
import android.content.*
import android.content.Intent.*
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.Q
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Apps
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.data.helper.hidden
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.data.helper.user
import com.yzddmr6.prismspace.data.helper.userId
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.CancelProfileShortcutLaunch
import com.yzddmr6.prismspace.bridge.EnsureAppFreeToLaunch
import com.yzddmr6.prismspace.bridge.PrepareProfileShortcutLaunch
import com.yzddmr6.prismspace.bridge.QueryDynamicShortcutLabelEnabled
import com.yzddmr6.prismspace.bridge.RefreshShortcutInParent
import com.yzddmr6.prismspace.bridge.RemoveShortcutsInParent
import com.yzddmr6.prismspace.bridge.ShortcutPort
import com.yzddmr6.prismspace.bridge.UpdateAllShortcutsInProfile
import com.yzddmr6.prismspace.bridge.isProfileProvisioningComplete
import com.yzddmr6.prismspace.settings.PrismSettings
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.LifecycleActivity
import com.yzddmr6.prismspace.util.OwnerUser
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Toasts
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.launch
import java.net.URISyntaxException

object PrismAppShortcut {

	const val ACTION_LAUNCH_CLONE = "com.yzddmr6.prismspace.action.LAUNCH_CLONE"
	private const val ACTION_LAUNCH_APP = "com.yzddmr6.prismspace.action.LAUNCH_APP"
	private const val SCHEME_PACKAGE = "package"            // Introduced in PrismSpace 2.8 (deprecated)
	private const val SCHEME_ANDROID_APP = "android-app"    // Introduced in PrismSpace 5.0 (deprecated)
	private const val SCHEME_APP = "app"                    // Introduced in PrismSpace 5.3 (replacing "android-app" used before to avoid shortcut intent corruption after reboot)
	private const val MAX_SHORTCUT_TEXT_LENGTH = 8_192
	private const val MAX_SHORTCUT_CATEGORY_COUNT = 16
	private const val PROFILE_LAUNCH_PREFS = "profile_shortcut_launch"
	private const val KEY_PENDING = "pending"
	private const val KEY_PACKAGE = "package"
	private const val KEY_ACTION = "action"
	private const val KEY_DATA = "data"
	private const val KEY_CATEGORIES = "categories"

	@OwnerUser @JvmStatic fun requestPin(context: Context, app: ApplicationInfo) {
		val dynamic = isDynamicLabelEnabled(context)
		// The shortcut belongs to the desktop where the user requested it: the parent user. Creating
		// it through the profile's ShortcutManager only registers a profile-local dynamic shortcut;
		// launchers such as HyperOS then cannot pin it onto the parent desktop. The shortcut intent
		// already carries the validated profile id and crosses the boundary only when it is tapped.
		requestPinAsUser(context, app, dynamic)
	}

	/** @return true if launcher supports shortcut pinning, false for failure, or null if legacy shortcut installation broadcast is sent. */
	@OwnerUser @ProfileUser @JvmStatic fun requestPinAsUser(context: Context, app: ApplicationInfo, dynamic: Boolean) {
		if (SDK_INT < O) return requestLegacyPin(context, app)

		val sm: ShortcutManager = context.getSystemService() ?: return showToastForShortcutFailure(context)
		val info = buildShortcutInfo(context, app, dynamic)
		try { sm.addDynamicShortcuts(listOf(info)) }
		catch (e: RuntimeException) { Log.e(TAG, "Error adding dynamic shortcut", e) }

			try { sm.requestPinShortcut(info, null) }
		catch (e: RuntimeException) { showToastForShortcutFailure(context); analytics().report(e) }
	}

	@OwnerUser @ProfileUser private fun remove(context: Context, pkg: String) {
		val id = getShortcutId(pkg, Users.current().toId())
		ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id))

		if (! Users.isParentProfile()) {
			val profileId = Users.currentId()
			BridgeTargets.parent()?.let { target ->
				Bridge.inParent(context, target).execute(RemoveShortcutsInParent(pkg, profileId))
			}
		}
	}

	@OwnerUser @ProfileUser @RequiresApi(O) fun updateIfNeeded(context: Context, app: ApplicationInfo, isCrossProfile: Boolean) {
		val sm: ShortcutManager = context.getSystemService() ?: return
		val id = getShortcutId(app.packageName, app.userId, isCrossProfile)
		sm.pinnedShortcuts.firstOrNull { it.id == id } ?: sm.dynamicShortcuts.firstOrNull { it.id == id } ?: return // Ensure existence
		update(context, app, dynamic = true)
	}

	@OwnerUser @RequiresApi(O) fun updateAllInActiveProfiles(context: Context) {
		val dynamic = isDynamicLabelEnabled(context)
		updateAll(context, dynamic)
		Users.getProfilesManagedByPrism().forEach {
			BridgeTargets.profile(it.toId())?.let { target ->
				Bridge.inProfile(context, target).execute(UpdateAllShortcutsInProfile(dynamic))
			}}
	}

	@RequiresApi(O) fun updateAll(context: Context, dynamic: Boolean) {
		Log.i(TAG, "Updating all pinned shortcuts...")
		val sm: ShortcutManager = context.getSystemService() ?: return
		val la: LauncherApps = context.getSystemService() ?: return
		sm.pinnedShortcuts.plus(sm.dynamicShortcuts).distinctBy { it.id }.forEach { shortcut ->
			val parsed = parseShortcutId(shortcut.id)?.takeIf { it.size <= 2 } ?: return@forEach
			val pkg = parsed[0]
			val profileId = try { parsed.getOrNull(1)?.toInt() } catch (e: NumberFormatException) { return@forEach }
			val profile = profileId?.let { UserHandles.of(it) } ?: Users.current()
			val app = try { la.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES, profile).takeIf { it.installed } }
				catch (e: NameNotFoundException) { null }
				?: return@forEach sm.removeDynamicShortcuts(listOf(getShortcutId(pkg, profile.toId())))
			update(context, app, dynamic) }
	}

	@OwnerUser @RequiresApi(O) private fun update(context: Context, app: ApplicationInfo, dynamic: Boolean) {
		Log.i(TAG, "Updating shortcut for ${app.packageName} in profile ${app.userId}")
		val shortcut = buildShortcutInfo(context, app, dynamic)
		context.getSystemService<ShortcutManager>()?.updateShortcuts(listOf(shortcut))
	}

	private fun buildLabel(context: Context, app: ApplicationInfo, dynamic: Boolean)
			= app.loadLabel(context.packageManager).let { buildLabelPrefix(context, app, dynamic)?.plus(it) ?: it }

	private fun buildLabelPrefix(context: Context, app: ApplicationInfo, dynamic: Boolean) = when {
		SDK_INT < O -> @Suppress("DEPRECATION") android.preference.PreferenceManager.getDefaultSharedPreferences(context)
				.getString(context.getString(R.string.key_launch_shortcut_prefix), context.getString(R.string.default_launch_shortcut_prefix))
		dynamic -> getDynamicPrefix(context, app)
		else -> null }

	private fun getDynamicPrefix(context: Context, app: ApplicationInfo)
			= if (app.hidden) context.getString(R.string.default_launch_shortcut_prefix) else null

	@RequiresApi(O) private fun buildShortcutInfo(context: Context, app: ApplicationInfo, dynamic: Boolean): ShortcutInfo {
		val pkg = app.packageName; val userId = app.userId; val isCrossProfile = isCrossProfile(userId)
		val shortcutId = getShortcutId(pkg, userId, isCrossProfile)
		val label = buildLabel(context, app, dynamic)
		val intent = buildShortcutIntent(context, pkg, userId)
		val drawable = getAppIconDrawable(context, context.getSystemService()!!, app)
		val sm = context.getSystemService<ShortcutManager>()!!
		return ShortcutInfo.Builder(context, shortcutId).setIntent(intent).setShortLabel(label).apply {
			setIcon(Icon.createWithAdaptiveBitmap(drawable.toBitmap(sm.iconMaxWidth, sm.iconMaxHeight)))
			if (SDK_INT >= Q) setLocusId(LocusId(shortcutId))
		}.build()
	}

	private fun buildShortcutIntent(context: Context, pkg: String, userId: Int) = Intent(ACTION_LAUNCH_APP, Uri.Builder()
			.scheme(SCHEME_APP).encodedAuthority(if (Users.isParentProfile(userId)) pkg else "$userId@$pkg").build())
			.addCategory(CATEGORY_LAUNCHER).setPackage(context.packageName)

	private const val SHORTCUT_ID_PREFIX = "launch:"    // launch:<pkg>[@<user ID>]
	internal fun getShortcutId(pkg: String, userId: Int, isCrossProfile: Boolean = isCrossProfile(userId))
			= "$SHORTCUT_ID_PREFIX$pkg".let { if (isCrossProfile) it.plus("@$userId") else it }
	private fun parseShortcutId(id: String)
			= id.takeIf { it.startsWith(SHORTCUT_ID_PREFIX) }?.substring(SHORTCUT_ID_PREFIX.length)?.split('@')

	@RequiresApi(O) private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
		val eif = AdaptiveIconDrawable.getExtraInsetFraction()
		return Bitmap.createBitmap(((1 + 2 * eif) * width).toInt(), ((1 + 2 * eif) * height).toInt(), ARGB_8888).also { bitmap ->
			setBounds((width * eif).toInt(), (height * eif).toInt(), (width * (1 + eif)).toInt(), (height * (1 + eif)).toInt())
			draw(Canvas(bitmap).apply { drawColor(Color.WHITE) }) }
	}

	private fun requestLegacyPin(context: Context, app: ApplicationInfo) {
		val label = buildLabel(context, app, dynamic = false)
		val intent = buildShortcutIntent(context, app.packageName, app.userId)
		val am: ActivityManager = context.getSystemService()!!; val size = am.launcherLargeIconSize
		val bitmap = getAppIconDrawable(context, am, app).let { drawable ->
			Bitmap.createBitmap(size, size, ARGB_8888).also { bitmap -> drawable.draw(Canvas(bitmap)) }}
		val shortcut = ShortcutInfoCompat.Builder(context, ""/* unused */).setIntent(intent).setShortLabel(label)
				.setIcon(IconCompat.createWithBitmap(bitmap)).build()
		if (! ShortcutManagerCompat.requestPinShortcut(context, shortcut, null))
			showToastForShortcutFailure(context)
	}

	private fun getAppIconDrawable(context: Context, am: ActivityManager, app: ApplicationInfo): Drawable
			= getAppIconLargeDrawable(context, am, app) ?: app.loadIcon(context.packageManager)   // Fallback to default density icon

	private fun getAppIconLargeDrawable(context: Context, am: ActivityManager, app: ApplicationInfo): Drawable?
			= if (app.icon == 0) null else try { context.packageManager.getResourcesForApplication(app)
				.getDrawableForDensity(app.icon, am.launcherLargeIconDensity, null) }
			catch (_: NameNotFoundException) { null } catch (_: Resources.NotFoundException) { null }

	private fun showToastForShortcutFailure(context: Context)
			= Toast.makeText(context, R.string.toast_shortcut_failed, LENGTH_LONG).show()

	private fun isCrossProfile(userId: Int) = userId != Users.current().toId()

	@ProfileUser @RequiresApi(O) class ShortcutSyncService: Service() {

		private val mPackageObserver = object: BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
			// A new profile cannot have pinned shortcuts yet. Package broadcasts emitted while Android
			// is still provisioning it also arrive before the parent can resolve this profile.
			if (! isProfileProvisioningComplete(context)) return
			val pkg = intent.data?.schemeSpecificPart ?: return
			if (intent.getBooleanExtra(EXTRA_REPLACING, false)) return  // Ignore package removal during replacing
			Log.d(TAG, "Package event: $intent")
			if (intent.`package` == context.packageName) return         // Skip duplicate broadcast directly sent to installer (that's us).

			val info = try { context.packageManager.getApplicationInfo(pkg, MATCH_UNINSTALLED_PACKAGES) }
			catch (e: NameNotFoundException) { return remove(context, pkg) }    // Actual package uninstall

			updateIfNeeded(context, info, false)

			if (! Users.isParentProfile()) { // For cross-profile shortcut
				val profile = Users.current()
				BridgeTargets.parent()?.let { target ->
					Bridge.inParent(context, target).execute(RefreshShortcutInParent(pkg, profile.toId()))
				}
			}
		}}

		override fun onCreate() {
			registerReceiver(mPackageObserver, IntentFilter(ACTION_PACKAGE_REMOVED).apply {
				addAction(ACTION_PACKAGE_ADDED); addDataScheme("package") })
		}

		override fun onDestroy() = unregisterReceiver(mPackageObserver)
		override fun onBind(intent: Intent?) = Binder()
	}

	fun isDynamicLabelEnabled(context: Context) = PrismSettings(context).DynamicShortcutLabel().enabled

	class ShortcutLauncher: LifecycleActivity() {

		companion object {

			/** @return Whether to finish the launchpad activity */
			@OwnerUser private fun prepareAndLaunch(activity: LifecycleActivity, pkg: String, intent: Intent? = null,
													profile: UserHandle = Users.current()): Boolean =
				doPrepareAndLaunch(activity, pkg, intent, profile) ?: true.also {
					Toast.makeText(activity, activity.getString(R.string.toast_app_launch_failure, Apps.of(activity).getAppName(pkg)), LENGTH_LONG).show() }

			/** @return Whether to finish the launchpad activity, or null for failure. */
			@OwnerUser private fun doPrepareAndLaunch(activity: LifecycleActivity, pkg: String, intent: Intent?,
			                                          profile: UserHandle): Boolean? {
				val context = activity.applicationContext; val la = LauncherAppsCompat(context)
				val app = la.getApplicationInfoNoThrows(pkg, MATCH_UNINSTALLED_PACKAGES, profile) ?: return null

				val um = context.getSystemService<UserManager>()!!
				if (SDK_INT < P || um.isUserUnlocked(profile))      // Quiet mode was introduced in Android P
					return true.also { shuttleAndLaunch(activity, pkg, intent, profile, app.hidden) }

				if (! app.hidden) la.get().run {    // Use LauncherApps to start non-frozen app within profile in Quiet Mode
					val component = getActivityList(pkg, profile).getOrNull(0)?.componentName ?: return true.also {
						// Same verdict as the list: no enabled launcher entry means "no UI", said out loud.
						DiagnosticLog.i(TAG, "launch_refused pkg=$pkg state=NoLauncherEntry")
						Toasts.showLong(context, R.string.lz_app_no_launcher_entry) }
					startMainActivity(component, profile, null, null)
					return true }

				// PrismSpace is currently locked (deactivated), unlock it before launching shortcut
				if (! DevicePolicies(context).isProfileOwner)   // Activating PrismSpace (to unfreeze app) requires managed MainSpace.
					return true.also { Toasts.showLong(context, R.string.prompt_activate_space_first) }
				val toast = Toast.makeText(context, R.string.prompt_activating_space, LENGTH_LONG).apply { show() }
				return false.also { activity.lifecycleScope.launch {    // Do not finish the activity to keep coroutine running.
					when(Users.requestQuietModeDisabled(context, profile)) {
						true -> {
							toast.cancel()    // Cancel as soon as shortcut is ready to launch
							Log.i(TAG, "Launching shortcut...")
							shuttleAndLaunch(activity, pkg, intent, profile, app.hidden) }
						false ->
							Toasts.showLong(context, R.string.prompt_activate_space_first) }
					activity.finish() }}
				}

				private fun shuttleAndLaunch(activity: Activity, pkg: String, intent: Intent?, profile: UserHandle, frozen: Boolean) {
					val context: Context = activity
					if (profile == Users.current()) {
						if (frozen) PrismManager.ensureAppFreeToLaunch(context, pkg)
						if (!launch(context, pkg, intent)) Toast.makeText(
							context,
							context.getString(R.string.toast_app_launch_failure, Apps.of(context).getAppName(pkg)),
							LENGTH_LONG,
						).show()
						return
					}
					val target = BridgeTargets.profile(profile.toId())
					// A home-screen tap gives this parent-side Activity foreground-launch authority. Android
					// 16 blocks the profile provider process from starting an Activity after the Binder hop,
					// so keep only the state mutation across the boundary and launch from this visible side.
					if (intent == null) {
						if (frozen) {
							val unfreeze = if (target == null) ProfileBridgeResult.SpaceMissing else
								ProfileBridgeResult.from(
									Bridge.inProfile(context, target).execute(EnsureAppFreeToLaunch(pkg)),
								)
							when (unfreeze) {
								is ProfileBridgeResult.Value -> if (!unfreeze.value.isNullOrEmpty()) {
									Toast.makeText(
										context,
										context.getString(R.string.toast_app_launch_failure, Apps.of(context).getAppName(pkg)),
										LENGTH_LONG,
									).show()
									return
								}
								else -> {
									Toasts.showLong(
										context,
										profileBridgeFailureMessage(context, unfreeze, context.getString(R.string.prompt_space_not_ready)),
									)
									return
								}
							}
						}
						if (PrismManager.launchApp(context, pkg, profile) !is com.yzddmr6.prismspace.engine.LaunchResult.Ok) {
							Toast.makeText(
								context,
								context.getString(R.string.toast_app_launch_failure, Apps.of(context).getAppName(pkg)),
								LENGTH_LONG,
							).show()
						}
						return
					}
					if (frozen) {
						val unfreeze = if (target == null) ProfileBridgeResult.SpaceMissing else
							ProfileBridgeResult.from(Bridge.inProfile(context, target).execute(EnsureAppFreeToLaunch(pkg)))
						if (unfreeze !is ProfileBridgeResult.Value || !unfreeze.value.isNullOrEmpty()) {
							Toasts.showLong(context, profileBridgeFailureMessage(
								context,
								unfreeze,
								context.getString(R.string.toast_app_launch_failure, Apps.of(context).getAppName(pkg)),
							))
							return
						}
					}
					val prepared = if (target == null) ProfileBridgeResult.SpaceMissing else ProfileBridgeResult.from(
						Bridge.inProfile(context, target).execute(PrepareProfileShortcutLaunch(
							pkg,
							intent.action,
							intent.dataString,
							intent.categories.orEmpty().toList(),
						)),
					)
					if (prepared is ProfileBridgeResult.Value && prepared.value == true && ProfileEntryLauncher.start(context, profile)) return
					if (target != null) Bridge.inProfile(context, target).execute(CancelProfileShortcutLaunch)
					showLaunchFailure(context, pkg)
				}

				private fun showLaunchFailure(context: Context, pkg: String) = Toast.makeText(
					context,
					context.getString(R.string.toast_app_launch_failure, Apps.of(context).getAppName(pkg)),
					LENGTH_LONG,
				).show()

			private fun launch(context: Context, pkg: String, intent: Intent?): Boolean {
				if (intent == null) return PrismManager.launchApp(context, pkg, Users.current()) is com.yzddmr6.prismspace.engine.LaunchResult.Ok
				// Entrance activity may not contain CATEGORY_DEFAULT, component must be set in launch intent.
				val resolve = context.packageManager.resolveActivity(intent, 0)
					?: return false.also { Log.w(TAG, "Unable to launch $pkg in profile ${Users.currentId()}: $intent") }
				Log.i(TAG, "Launching $pkg in profile ${Users.currentId()}...")
				intent.component = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
				return try { context.startActivity(intent.addFlags(FLAG_ACTIVITY_NEW_TASK)); true } catch (e: ActivityNotFoundException) {
					false.also { Log.e(TAG, "Error launching $pkg in profile ${Users.currentId()}: $intent") }}
			}
		}

		/** @return Whether to finish the launchpad activity */
		private fun launch(uri: Uri): Boolean = when(uri.scheme) {
			// Log the parsed boundary fields, not the full URI (historical deep links can contain
			// user data). This makes shortcut routing failures distinguishable without leaking it.
			SCHEME_PACKAGE /* legacy */     -> prepareAndLaunch(this, uri.schemeSpecificPart)
			SCHEME_ANDROID_APP /* legacy */ -> launchForAndroidAppScheme(uri)
			SCHEME_APP -> launchForAndroidAppScheme(uri.buildUpon().scheme(SCHEME_ANDROID_APP).build())
			else -> true.also { showInvalidShortcutToast() }
		}.also { Log.i(TAG, "shortcut parsed scheme=${uri.scheme} host=${uri.host} user=${uri.userInfo ?: "current"}") }

		private fun launchForAndroidAppScheme(uri: Uri): Boolean {
			val parsed = try { parseUri(uri.toString(), URI_ANDROID_APP_SCHEME) }
			catch (e: URISyntaxException) { showInvalidShortcutToast(); return false }
			val intent = if (! uri.encodedPath.isNullOrEmpty() || uri.encodedFragment != null) parsed else null // Null for pure app launch

			// Intent.parseUri() normalizes android-app authorities and may strip user-info from
			// Intent.package. The shortcut URI is the source of truth for the validated user id.
			val pkg = uri.host ?: return false.also { showInvalidShortcutToast() }
			intent?.setPackage(pkg)
			val profileId = uri.userInfo
			if (profileId.isNullOrEmpty()) return prepareAndLaunch(this, pkg, intent)

			val user = try { UserHandles.of(profileId.toInt()) }
			catch (e: NumberFormatException) { showInvalidShortcutToast(); return false }

			return prepareAndLaunch(this, pkg, intent, user)
		}

		private fun showInvalidShortcutToast() = Toast.makeText(this, R.string.prompt_invalid_shortcut, LENGTH_LONG).show()

		override fun onNewIntent(intent: Intent) {
			var finishNow = true
			try {
				val action = intent.action
				if (ACTION_LAUNCH_APP != action && ACTION_LAUNCH_CLONE != action) return
				finishNow = launch(intent.data ?: return)
			} finally { if (finishNow) finish() }
		}

		override fun onCreate(savedInstanceState: Bundle?) = super.onCreate(savedInstanceState).also { onNewIntent(intent) }
	}

	internal fun launchPendingInProfile(activity: Activity): Boolean {
		val request = consumeProfileLaunch(activity) ?: return false
		return true.also {
			runCatching {
				val target = Intent(request.action, request.dataUri?.let(Uri::parse)).setPackage(request.packageName)
				request.categories.forEach(target::addCategory)
				val resolved = activity.packageManager.resolveActivity(target, 0)?.activityInfo
					?.takeIf { it.packageName == request.packageName }
					?: error("Unable to resolve shortcut target package=${request.packageName}")
				target.component = ComponentName(resolved.packageName, resolved.name)
				activity.startActivity(target.addFlags(FLAG_ACTIVITY_NEW_TASK))
			}.onFailure {
				Log.e(TAG, "Unable to launch validated profile shortcut", it)
				Toasts.showLong(
					activity,
					activity.getString(
						R.string.toast_app_launch_failure,
						Apps.of(activity).getAppName(request.packageName),
					),
				)
			}
		}
	}

	internal fun saveProfileLaunch(
		context: Context,
		packageName: String,
		action: String?,
		dataUri: String?,
		categories: List<String>,
	): Boolean {
		if (!profileLaunchFieldsValid(packageName, action, dataUri, categories)) return false
		return context.getSharedPreferences(PROFILE_LAUNCH_PREFS, Context.MODE_PRIVATE).edit()
			.putBoolean(KEY_PENDING, true)
			.putString(KEY_PACKAGE, packageName)
			.putString(KEY_ACTION, action)
			.putString(KEY_DATA, dataUri)
			.putStringSet(KEY_CATEGORIES, categories.toSet())
			.commit()
	}

	internal fun cancelProfileLaunch(context: Context) {
		context.getSharedPreferences(PROFILE_LAUNCH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
	}

	private fun consumeProfileLaunch(context: Context): PendingProfileLaunch? {
		val preferences = context.getSharedPreferences(PROFILE_LAUNCH_PREFS, Context.MODE_PRIVATE)
		if (!preferences.getBoolean(KEY_PENDING, false)) return null
		val packageName = preferences.getString(KEY_PACKAGE, null).orEmpty()
		val action = preferences.getString(KEY_ACTION, null)
		val dataUri = preferences.getString(KEY_DATA, null)
		val categories = preferences.getStringSet(KEY_CATEGORIES, emptySet()).orEmpty().toList()
		preferences.edit().clear().commit()
		return PendingProfileLaunch(packageName, action, dataUri, categories)
			.takeIf { profileLaunchFieldsValid(it.packageName, it.action, it.dataUri, it.categories) }
	}

	internal fun profileLaunchFieldsValid(
		packageName: String,
		action: String?,
		dataUri: String?,
		categories: List<String>,
	): Boolean = packageName.isNotBlank() && packageName.length <= 255 &&
		(action?.length ?: 0) <= 255 && (dataUri?.length ?: 0) <= MAX_SHORTCUT_TEXT_LENGTH &&
		categories.size <= MAX_SHORTCUT_CATEGORY_COUNT && categories.all { it.length <= 255 }

	private data class PendingProfileLaunch(
		val packageName: String,
		val action: String?,
		val dataUri: String?,
		val categories: List<String>,
	)
}

class ShortcutsUpdater: BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (SDK_INT >= O) try { // It's safe to not check the action, as the update is idempotent and does not depends on intent.
			Users.refreshUsers(context)     // Ensure Users.parentProfile is initialized
			val target = BridgeTargets.parent()
				?: return Unit.also { Log.w(TAG, "Failed to resolve parent target for shortcut refresh.") }
			val dynamic = when (val outcome = Bridge.inParent(context, target).execute(QueryDynamicShortcutLabelEnabled)) {
				is com.yzddmr6.prismspace.shuttle.ShuttleOutcome.Value -> outcome.value
					?: return Unit.also { Log.w(TAG, "Dynamic shortcut label query returned no value.") }
				else -> return Unit.also { Log.w(TAG, "Failed to query setting DynamicShortcutLabel across profile.") }
			}
			PrismAppShortcut.updateAll(context, dynamic)
		} catch (e: IllegalStateException) { return }   // User is locked
	}
}

internal object MobileShortcutPort : ShortcutPort {
	override fun prepareProfileLaunch(
		context: Context,
		packageName: String,
		action: String?,
		dataUri: String?,
		categories: List<String>,
	) = PrismAppShortcut.saveProfileLaunch(context, packageName, action, dataUri, categories)

	override fun cancelProfileLaunch(context: Context) = PrismAppShortcut.cancelProfileLaunch(context)

	override fun updateAll(context: Context, dynamicLabel: Boolean): Boolean {
		if (SDK_INT >= O) PrismAppShortcut.updateAll(context, dynamicLabel)
		return true
	}

	override fun removeInParent(context: Context, packageName: String, profileUserId: Int): Boolean {
		requireNotNull(BridgeTargets.profile(profileUserId)) { "Unmanaged profile $profileUserId" }
		ShortcutManagerCompat.removeLongLivedShortcuts(
			context,
			listOf(PrismAppShortcut.getShortcutId(packageName, profileUserId, isCrossProfile = true)),
		)
		return true
	}

	override fun refreshInParent(context: Context, packageName: String, profileUserId: Int): Boolean {
		if (SDK_INT < O) return true
		requireNotNull(BridgeTargets.profile(profileUserId)) { "Unmanaged profile $profileUserId" }
		val profile = UserHandles.of(profileUserId)
		val app = LauncherAppsCompat(context)
			.getApplicationInfoNoThrows(packageName, MATCH_UNINSTALLED_PACKAGES, profile)
		if (app != null && PrismAppShortcut.isDynamicLabelEnabled(context)) {
			PrismAppShortcut.updateIfNeeded(context, app, true)
		}
		return true
	}

	override fun queryDynamicLabelEnabled(context: Context) = PrismAppShortcut.isDynamicLabelEnabled(context)
}

private const val TAG = "Prism.Shortcut"

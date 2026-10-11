package com.yzddmr6.prismspace.shuttle

import android.app.Activity
import android.content.*
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.pm.LauncherApps
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.util.Log
import com.yzddmr6.prismspace.bridge.BridgeCommand
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeDispatcher
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.BridgePorts
import com.yzddmr6.prismspace.bridge.BridgeWire
import com.yzddmr6.prismspace.bridge.Ping
import com.yzddmr6.prismspace.bridge.EstablishBackwardGrant
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.OwnerUser
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

class ShuttleProvider: ContentProvider() {

	companion object {

		fun <R> call(context: Context, target: UserHandle, command: BridgeCommand<R>): ShuttleOutcome<R> {
			val targetUser = target.toId()
			if (isTargetUnavailable(context, target)) {
				DiagnosticLog.i(
					TAG,
					"Bridge call skipped operation=${command.id} targetUser=$targetUser cause=$PROFILE_UNAVAILABLE",
				)
				return ShuttleOutcome.Skipped(PROFILE_UNAVAILABLE)
			}
			if (!isReady(context, target)) {
				DiagnosticLog.w(
					TAG,
					"Bridge call skipped operation=${command.id} targetUser=$targetUser cause=${ShuttleNotReadyCause.PermissionDenied}",
				)
				return ShuttleOutcome.NotReady(ShuttleNotReadyCause.PermissionDenied)
			}
			val uri = buildCrossProfileUri(targetUser)
			return try {
				val response = callWithTransientRetry(context, uri, command)
				ShuttleOutcome.Value(BridgeWire.decode(command, response))
			} catch (error: RuntimeException) {
				val permissionGranted = isReady(context, target)
				val cause = classifyShuttleNotReadyCause(error, permissionGranted)
				if (cause != null) {
					DiagnosticLog.w(
						TAG,
						"Bridge call failed operation=${command.id} targetUser=$targetUser " +
							"exception=${error.javaClass.name} cause=$cause permissionGranted=$permissionGranted",
						error,
					)
					ShuttleOutcome.NotReady(cause)
				} else {
					DiagnosticLog.w(
						TAG,
						"Bridge execution failed operation=${command.id} targetUser=$targetUser exception=${error.javaClass.name}",
						error,
					)
					ShuttleOutcome.Failed(error)
				}
			}
		}

		/** A stopped, quiet or locked target cannot serve the provider; calling it only produces
		 *  `Unknown authority` noise. Unreadable facts never block the call (the call classifies). */
		private fun isTargetUnavailable(context: Context, target: UserHandle): Boolean {
			val isCurrent = target == Users.current()
			if (isCurrent) return false
			val running = runCatching { Users.isProfileRunning(context, target) }.getOrDefault(true)
			val quiet = runCatching { Users.isProfileQuietModeEnabled(context, target) }.getOrDefault(false)
			val unlocked = runCatching {
				context.getSystemService(UserManager::class.java)?.isUserUnlocked(target) ?: true
			}.getOrDefault(true)
			return shouldSkipForAvailability(isCurrent, running, quiet, unlocked)
		}

		private fun isReady(c: Context, profile: UserHandle) = c.isPermissionGranted(buildCrossProfileUri(profile.toId()))
		@OwnerUser private fun isBackwardReady(c: Context, profile: UserHandle) =
			c.isPermissionGranted(Uri.parse(CONTENT_URI), uid = UserHandles.getUid(profile.toId(), Process.myUid()))

		/**
		 * The wire protocol never returns a null Bundle (BridgeDispatcher always answers), so a null
		 * response means the framework failed to acquire the provider — a transient state on ROMs with
		 * aggressive background management (HyperOS 1 evidence: process alive, grants held, null in
		 * 13ms, self-heals in ~2s). Retry nulls with a small budget; thrown failures (grant loss et al.)
		 * are structural and propagate immediately.
		 */
		private fun <R> callWithTransientRetry(context: Context, uri: Uri, command: BridgeCommand<R>): Bundle {
			val method = BridgeWire.methodName(command)
			val request = BridgeWire.request(command)
			var nulls = 0
			while (true) {
				val response = context.contentResolver.call(uri, method, null, request)
				if (response != null) {
					if (nulls > 0)
						DiagnosticLog.i(TAG, "bridge call recovered operation=${command.id} nullRetries=$nulls")
					return response
				}
				nulls++
				if (nulls >= BRIDGE_CALL_MAX_ATTEMPTS) break
				DiagnosticLog.i(TAG, "bridge call null response operation=${command.id} attempt=$nulls; retrying")
				try {
					Thread.sleep(BRIDGE_CALL_RETRY_DELAY_MS)
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					break
				}
			}
			throw IllegalArgumentException("Missing bridge response for ${command.id} after $nulls attempts")
		}

		private fun Context.uriPermissionCheck(uri: Uri, uid: Int = Process.myUid()) =
			checkUriPermission(uri, 0, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

		private fun Context.isPermissionGranted(uri: Uri, uid: Int = Process.myUid()) =
				uriPermissionCheck(uri, uid) == PERMISSION_GRANTED

		fun hasForwardGrant(context: Context, profile: UserHandle) = isReady(context, profile)
		@OwnerUser fun hasBackwardGrant(context: Context, profile: UserHandle) = isBackwardReady(context, profile)

			fun health(context: Context, profile: UserHandle, timeoutMs: Long = SHUTTLE_HEALTH_TIMEOUT_MS): ShuttleHealth {
				val running = runCatching { Users.isProfileRunning(context, profile) }.getOrDefault(false)
				val quietMode = runCatching { Users.isProfileQuietModeEnabled(context, profile) }.getOrDefault(false)
				val unlocked = runCatching {
					context.getSystemService(UserManager::class.java)?.isUserUnlocked(profile) == true
				}.getOrDefault(false)
				val forwardGrantCheck = context.uriPermissionCheck(buildCrossProfileUri(profile.toId()))
				val forwardGrant = forwardGrantCheck == PERMISSION_GRANTED
				val backwardGrantCheck = runCatching {
					context.uriPermissionCheck(
						Uri.parse(CONTENT_URI),
						uid = UserHandles.getUid(profile.toId(), Process.myUid()),
					)
				}.getOrDefault(PERMISSION_DENIED)
				val backwardGrant = backwardGrantCheck == PERMISSION_GRANTED
				// Own-package state inside the profile, parent-side view: splits "the system touched
				// the package" from "provider alone is unresolvable" when the bridge breaks.
				val launcherApps = context.getSystemService(LauncherApps::class.java)
				val profilePackageInfo = runCatching {
					launcherApps?.getApplicationInfo(context.packageName, 0, profile)
				}.getOrNull()
				val packageEnabled = runCatching {
					launcherApps?.isPackageEnabled(context.packageName, profile)
				}.getOrNull()
				val packageSuspended = profilePackageInfo?.let {
					(it.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
				}
				val ping = when {
					quietMode -> ShuttleOutcome.Skipped("profile_quiet_mode")
					!running -> ShuttleOutcome.Skipped("profile_not_running")
					!unlocked -> ShuttleOutcome.Skipped("profile_locked")
					!forwardGrant -> ShuttleOutcome.NotReady(ShuttleNotReadyCause.PermissionDenied)
					else -> BridgeTargets.profile(profile.toId())
						?.let { com.yzddmr6.prismspace.bridge.Bridge.inProfile(context, it).execute(Ping, timeoutMs) }
						?: ShuttleOutcome.Skipped("profile_missing")
				}
				return ShuttleHealth(
					profile.toId(),
					running,
					quietMode,
					unlocked,
					forwardGrant,
					backwardGrant,
					ping,
					forwardGrantCheck,
					backwardGrantCheck,
					packageEnabled,
					packageSuspended,
				)
			}

		fun initialize(context: Context) {
			Log.v(TAG, "Initializing in profile ${Users.currentId()}...")
			if (Users.isParentProfile())
				return Users.getProfilesManagedByPrism().forEach {
					if (isReady(context, it)) {
						Log.i(TAG, "Shuttle to profile ${it.toId()}: ready")
						if (! isBackwardReady(context, it)) initializeBackwardBridge(context, it) }
					else Log.w(TAG, "Shuttle to profile ${it.toId()}: not ready") }
			if (! DevicePolicies(context).isProfileOwner) return

			if (isReady(context, Users.parentProfile)) Log.i(TAG, "Shuttle to parent profile: ready")
			else Log.w(TAG, "Shuttle to parent profile: not ready")

			initializeInPrism(context)
		}

		fun initializeFromProfileForeground(context: Context) {
			if (Users.isParentProfile()) return initialize(context)
			if (! DevicePolicies(context).isProfileOwner) return
			Log.i(TAG, "Shuttle foreground initialization in profile ${Users.currentId()}")
			initializeInPrism(context, force = true)
		}

		private fun initializeBackwardBridge(context: Context, profile: UserHandle) {
			val target = BridgeTargets.profile(profile.toId()) ?: return
			Bridge.inProfile(context, target).execute(EstablishBackwardGrant)
		}

		internal fun establishBackwardGrant(context: Context) = initializeInPrism(context)

		private fun initializeInPrism(context: Context, force: Boolean = false) {
			if (!force && context.isPermissionGranted(Uri.parse(CONTENT_URI), uid = UserHandles.getAppId(Process.myUid())))
				return Unit.also { Log.i(TAG, "Shuttle in ${Users.current().toId()}: ready") }

			Log.i(TAG, "Shuttle in profile ${Users.current().toId()}: establishing... force=$force")
			ShuttleCarrierActivity.sendToParentProfileQuietlyIfPossible(context) {
				addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
				clipData = ClipData(TAG, emptyArray(), ClipData.Item(buildCrossProfileUri())) }
		}

		@OwnerUser @ProfileUser fun collectActivityResult(context: Context, intent: Intent) {
			val uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
			if (uri == null) {
				DiagnosticLog.w(
					TAG,
					"shuttle grant receive missing uri user=${Users.currentId()} " +
						"data=${intent.data} clipItems=${intent.clipData?.itemCount ?: 0} flags=${intent.flags}",
				)
				return
			}
			DiagnosticLog.i(
				TAG,
				"shuttle grant received user=${Users.currentId()} uri=$uri flags=${intent.flags} " +
					"clipItems=${intent.clipData?.itemCount ?: 0}",
			)
			takeUriGranted(context, uri)
		}

		@OwnerUser @ProfileUser fun takeUriGranted(context: Context, uri: Uri) {
			try {
				context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
				val check = context.uriPermissionCheck(uri)
				DiagnosticLog.i(TAG, "shuttle grant take success user=${Users.currentId()} uri=$uri check=$check")
			} catch (e: RuntimeException) {
				DiagnosticLog.e(TAG, "shuttle grant take failed user=${Users.currentId()} uri=$uri", e)
				throw e
			}
		}

		fun buildCrossProfileUri(profileId: Int = Users.currentId()): Uri = Uri.parse("$SCHEME_CONTENT://$profileId@$AUTHORITY")

		private const val AUTHORITY = "com.yzddmr6.prismspace.shuttle"
		const val PROFILE_UNAVAILABLE = "profile_unavailable"
		const val CONTENT_URI = "$SCHEME_CONTENT://$AUTHORITY"
		private const val SHUTTLE_HEALTH_TIMEOUT_MS = 1_500L
		private const val BRIDGE_CALL_MAX_ATTEMPTS = 3
		private const val BRIDGE_CALL_RETRY_DELAY_MS = 200L
	}

	override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
		val command = extras?.let(BridgeWire::readCommand)
		if (command == null) {
			return BridgeWire.invalidRequest(method, "missing typed bridge command")
		}
		if (method != command.id) return BridgeWire.invalidRequest(command.id, "method=$method")
		val token = Binder.clearCallingIdentity()
		return try {
			BridgeDispatcher.dispatch(context, command)
		} finally {
			Binder.restoreCallingIdentity(token)
		}
	}

	override fun onCreate() = true.also {
		BridgePorts.installRegistered(context)
		initialize(context)
	}

	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

	val context: Context; @JvmName("context") get() = getContext()!!

	class ReceiverActivity: Activity() {

		override fun onCreate(savedInstanceState: Bundle?) {
			super.onCreate(savedInstanceState)
			finish()
			intent.data?.also { takeUriGranted(this, it) }
		}
	}
}

private const val TAG = "Prism.SP"

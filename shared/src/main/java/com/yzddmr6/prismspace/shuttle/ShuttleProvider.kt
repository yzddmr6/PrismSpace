package com.yzddmr6.prismspace.shuttle

import android.app.Activity
import android.content.*
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.util.SparseArray
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.analytics.analytics
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.OwnerUser
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.io.Serializable

class ShuttleProvider: ContentProvider() {

	companion object {

		fun <R> call(context: Context, profile: UserHandle, function: ContextFun<R>): ShuttleResult<R> {
			val bundle = Bundle(1).apply { putParcelable(null, Closure(function)) }
			val uri = buildCrossProfileUri(profile.toId())
			try { return ShuttleResult.value(context.contentResolver.call(uri, function.javaClass.name, null, bundle)) }
			catch (e: RuntimeException) { // "SecurityException" or "IllegalArgumentException: Unknown authority 0@..." if shuttle is not ready
				val permissionGranted = isReady(context, profile)
				val cause = classifyShuttleNotReadyCause(e, permissionGranted)
				if (cause != null) {
					Log.w(TAG, "Shuttle call not ready target=${profile.toId()} cause=$cause permissionGranted=$permissionGranted method=${function.javaClass.name}")
					if (permissionGranted) analytics().logAndReport(TAG, "Error shuttling $function", e)
					return ShuttleResult.notReady(cause) }
				throw e }
		}

		private fun isReady(c: Context, profile: UserHandle) = c.isPermissionGranted(buildCrossProfileUri(profile.toId()))
		@OwnerUser private fun isBackwardReady(c: Context, profile: UserHandle) =
			c.isPermissionGranted(Uri.parse(CONTENT_URI), uid = UserHandles.getUid(profile.toId(), Process.myUid()))

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
				val ping = when {
					quietMode -> ShuttleOutcome.Skipped("profile_quiet_mode")
					!running -> ShuttleOutcome.Skipped("profile_not_running")
					!unlocked -> ShuttleOutcome.Skipped("profile_locked")
					!forwardGrant -> ShuttleOutcome.NotReady(ShuttleNotReadyCause.PermissionDenied)
					else -> Shuttle(context, to = profile).invokeOutcomeWithin(timeoutMs) { true }
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
				)
			}

		fun initialize(context: Context) {
			Log.v(TAG, "Initializing in profile ${Users.currentId()}...")
			if (Users.isParentProfile())
				return Users.getProfilesManagedByPrism().forEach {
					if (isReady(context, it)) {
						Log.i(TAG, "Shuttle to profile ${it.toId()}: ready")
						if (! isBackwardReady(context, it)) initializeBackwardShuttle(context, it) }
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

		private fun initializeBackwardShuttle(context: Context, profile: UserHandle) {
			Shuttle(context, to = profile).launchNoThrows { initializeInPrism(this) }
		}

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
		const val CONTENT_URI = "$SCHEME_CONTENT://$AUTHORITY"
		private const val SHUTTLE_HEALTH_TIMEOUT_MS = 1_500L
	}

	override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
		val closure = requireNotNull(extras?.apply { classLoader = Closure::class.java.classLoader }?.getParcelable<Closure>(null)) { "Missing extra" }
		val token = Binder.clearCallingIdentity()
		val result = try {
			closure.invoke(context)
		} finally {
			Binder.restoreCallingIdentity(token)
		}.also { Log.i(TAG, "Call: $method()=$it") }
		return if (result == null || result == Unit) null else Bundle().apply { put(null, result) }
	}

	override fun onCreate() = true.also { initialize(context) }

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

class ShuttleResult<R> private constructor(private val bundle: Bundle?, val notReadyCause: ShuttleNotReadyCause?) {

	companion object {
		fun <R> value(bundle: Bundle?) = ShuttleResult<R>(bundle, null)
		fun <R> notReady(cause: ShuttleNotReadyCause) = ShuttleResult<R>(null, cause)
	}

	fun isNotReady() = notReadyCause != null
	@Suppress("UNCHECKED_CAST") fun get(): R = bundle?.get(null) as R
	@Suppress("UNCHECKED_CAST") fun getOrNull(): R? = bundle?.get(null) as R?
	override fun toString() =
		if (isNotReady()) "ShuttleResult{NOT_READY cause=$notReadyCause}"
		else "ShuttleResult{" + bundle.toString() + "}"
}

//private fun Bundle?.toStr() = this?.toString()?.substring(6) ?: "null"

private typealias ContextFun<R> = Context.() -> R?

private fun Bundle.put(key: String?, value: Any?) {
	when (value) {
		null -> putString(key, null)
		is Boolean -> putBoolean(key, value)
		is Int -> putInt(key, value)
		is Long -> putLong(key, value)
		is String -> putString(key, value)
		is CharSequence -> putCharSequence(key, value)
		// These Parcelable types must be checked before "is Parcelable".
		is Bundle -> putBundle(key, value)
		is SizeF -> putSizeF(key, value)
		is Parcelable -> putParcelable(key, value)

		is Array<*> -> when {
			value.isArrayOf<Parcelable>() -> @Suppress("UNCHECKED_CAST") putParcelableArray(key, value as Array<Parcelable?>)
			value.isArrayOf<CharSequence>() -> @Suppress("UNCHECKED_CAST") putCharSequenceArray(key, value as Array<CharSequence?>)
			value.isArrayOf<String>() -> @Suppress("UNCHECKED_CAST") putStringArray(key, value as Array<String?>)
			else -> throw IllegalArgumentException("Unsupported array type: " + value.javaClass) }
		is List<*> -> @Suppress("UNCHECKED_CAST") putParcelableArrayList(key,
				if (value is ArrayList<*>) (value as ArrayList<Parcelable>) else ArrayList(value as List<Parcelable>))
		is SparseArray<*> -> @Suppress("UNCHECKED_CAST") putSparseParcelableArray(key, value as SparseArray<Parcelable>)

		is Byte -> putByte(key, value)
		is Char -> putChar(key, value)
		is Short -> putShort(key, value)
		is Float -> putFloat(key, value)
		is Double -> putDouble(key, value)
		is Size -> putSize(key, value)
		is BooleanArray -> putBooleanArray(key, value)
		is IntArray -> putIntArray(key, value)
		is LongArray -> putLongArray(key, value)
		is ByteArray -> putByteArray(key, value)
		is CharArray -> putCharArray(key, value)
		is ShortArray -> putShortArray(key, value)
		is FloatArray -> putFloatArray(key, value)
		is DoubleArray -> putDoubleArray(key, value)

		is IBinder -> putBinder(key, value)

		is Serializable -> putSerializable(key, value)      // Must be the last one
		else -> throw IllegalArgumentException("Unsupported type: " + value.javaClass)
	}
}

private const val TAG = "Prism.SP"

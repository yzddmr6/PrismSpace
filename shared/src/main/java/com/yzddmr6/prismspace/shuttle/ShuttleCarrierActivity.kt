package com.yzddmr6.prismspace.shuttle

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification.GROUP_ALERT_SUMMARY
import android.app.Notification.VISIBILITY_PUBLIC
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.P
import android.os.Bundle
import androidx.core.content.getSystemService
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.notification.NotificationIds
import com.yzddmr6.prismspace.notification.post
import com.yzddmr6.prismspace.shared.R
import com.yzddmr6.prismspace.util.DPM
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Users

class ShuttleCarrierActivity: Activity() {

	companion object {

		@ProfileUser fun sendToParentProfileQuietlyIfPossible(context: Context, decoration: Intent.() -> Unit) {
			val intent = Intent(context, ShuttleCarrierActivity::class.java)
					.addFlags(FLAG_ACTIVITY_NEW_TASK or SILENT_LAUNCH_FLAGS).apply(decoration)
			val credentialNeeded = isCredentialNeeded(context)
			DiagnosticLog.i(
				TAG,
				"carrier self-start requested user=${Users.currentId()} flags=${intent.flags} " +
					"uri=${intent.grantUriForLog()} credentialNeeded=$credentialNeeded",
			)
			if (credentialNeeded) {
				DiagnosticLog.i(TAG, "carrier self-start deferred by credential notification user=${Users.currentId()}")
				return NotificationIds.Shuttle.post(context) { setOngoing(true).setVisibility(VISIBILITY_PUBLIC)
						.setSmallIcon(R.drawable.ic_landscape_black_24dp).setColor(context.getColor(R.color.accent))
						.setContentTitle(context.getString(R.string.notification_profile_shuttle_pending_title))
						.setContentText(context.getString(R.string.notification_profile_shuttle_pending_text))
						.setContentIntent(PendingIntent.getActivity(context, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE))
						.apply { if (SDK_INT >= O) setGroup("Shuttle").setGroupAlertBehavior(GROUP_ALERT_SUMMARY) }}}

			try {
				context.startActivity(intent)   // Start self in current profile as a trampoline to startActivityForResult() cross-profile.
				DiagnosticLog.i(TAG, "carrier self-start dispatched user=${Users.currentId()}")
			} catch (e: RuntimeException) {
				DiagnosticLog.e(TAG, "carrier self-start failed user=${Users.currentId()}", e)
			}
		}
		/** Credential confirmation is needed if separate challenge (P+) is used and is locked by keyguard,
		 *  even if "user" (credential protected storage) is unlocked */
		@ProfileUser private fun isCredentialNeeded(context: Context) =
				if (SDK_INT < P || DevicePolicies(context).run { ! isManagedProfile || invoke(DPM::isUsingUnifiedPassword) }) false
				else (context.getSystemService<KeyguardManager>()?.run { isDeviceLocked && isDeviceSecure } ?: false)

		private const val ACTION = "com.yzddmr6.prismspace.action.SHUTTLE"
		private const val SILENT_LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
				Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY //or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (Users.isParentProfile()) {
			DiagnosticLog.i(
				TAG,
				"carrier entered parent user=${Users.currentId()} flags=${intent.flags} uri=${intent.grantUriForLog()}",
			)
			try { ShuttleProvider.collectActivityResult(this, intent) }
			catch (e: SecurityException) { DiagnosticLog.e(TAG, "carrier parent collect failed", e) }
			setResult(RESULT_OK, Intent(null, ShuttleProvider.buildCrossProfileUri()) // Send reverse shuttle back
					.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
			DiagnosticLog.i(TAG, "carrier parent result prepared user=${Users.currentId()} uri=${ShuttleProvider.buildCrossProfileUri()}")
			finish()
		} else {
			DiagnosticLog.i(
				TAG,
				"carrier entered profile user=${Users.currentId()} flags=${intent.flags} uri=${intent.grantUriForLog()}",
			)
			val intent = intent.setAction(ACTION).removeFlag(FLAG_ACTIVITY_NEW_TASK)
			try {
				if (SDK_INT >= Build.VERSION_CODES.R) {
					DevicePolicies(this).ensureCrossProfileReady()
					DiagnosticLog.i(
						TAG,
						"carrier cross-profile start begin user=${Users.currentId()} flags=${intent.flags} uri=${intent.grantUriForLog()}",
					)
					getSystemService<CrossProfileApps>()!!.startActivity(intent, Users.parentProfile, this)
					DiagnosticLog.i(TAG, "carrier cross-profile start dispatched user=${Users.currentId()}")
				} else {
					DevicePolicies(this).addCrossProfileIntentFilter(IntentFilter(ACTION), FLAG_PARENT_CAN_ACCESS_MANAGED)
					CrossProfile.decorateIntentForActivityInParentProfile(this, intent.setComponent(null))
					startActivityForResult(intent, 1)
					DiagnosticLog.i(TAG, "carrier legacy cross-profile start dispatched user=${Users.currentId()}")
				}
			} catch (e: RuntimeException) {
				DiagnosticLog.e(TAG, "carrier cross-profile start failed user=${Users.currentId()}", e)
				finish()
			}}
	}

	override fun onRestart() {
		super.onRestart()
		if (SDK_INT >= Build.VERSION_CODES.R) try {       // Cannot receive activity result on R+, see onCreate().
			DiagnosticLog.i(TAG, "carrier profile restart taking reverse grant user=${Users.currentId()}")
			ShuttleProvider.takeUriGranted(this, Uri.parse(ShuttleProvider.CONTENT_URI))
		} catch (e: RuntimeException) { DiagnosticLog.e(TAG, "carrier reverse grant take failed user=${Users.currentId()}", e) }
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		DiagnosticLog.i(
			TAG,
			"carrier activity result user=${Users.currentId()} request=$requestCode result=$resultCode " +
				"hasData=${data != null} uri=${data?.grantUriForLog()}",
		)
		ShuttleProvider.collectActivityResult(this, data ?: return)       // Receive the reverse shuttle sent back as activity result
		finish()
	}

	private fun Intent.removeFlag(flag: Int) = this.also { flags = flags and flag.inv() }
}

private fun Intent.grantUriForLog(): Uri? =
	data ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

private const val TAG = "Prism.SRA"

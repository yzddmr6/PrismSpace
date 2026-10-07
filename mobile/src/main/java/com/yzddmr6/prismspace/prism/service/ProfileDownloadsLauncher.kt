package com.yzddmr6.prismspace.prism.service

import android.app.Activity
import android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.os.Build
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.CrossProfileForwardingKind
import com.yzddmr6.prismspace.bridge.InstallCrossProfileForwarding
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users

/**
 * Opens user-facing entry points inside the managed profile.
 *
 * This is intentionally separate from [FileBridgeService]: file bridge copies bytes; this launcher
 * owns the Android cross-profile contract and permission handoff for the profile install entry.
 */
internal class ProfileDownloadsOpener {

    fun openInstallEntry(activity: Activity): FileTransferResult =
        open(
            activity,
            ProfileDownloadsLauncher::buildDirectProfileInstallEntryIntent,
            { ProfileDownloadsLauncher.buildCrossProfileInstallEntryIntent() },
            preferProfileEntry = true,
        )

    private fun open(
        activity: Activity,
        directIntent: (String) -> Intent,
        forwarderIntent: () -> Intent,
        preferProfileEntry: Boolean,
    ): FileTransferResult {
        val context = activity.applicationContext
        return try {
            val profile = Users.profile
                ?: return FileTransferResult(
                    false,
                    str(context, R.string.fb_need_create_space),
                    failureReason = FileTransferFailureReason.SpaceMissing,
                )
            if (preferProfileEntry && startProfileEntry(activity, profile)) {
                return FileTransferResult(true, str(context, R.string.fb_opened_profile_entry))
            }
            when (startDirectly(activity, profile, directIntent(context.packageName))) {
                DirectOpenResult.Opened ->
                    return FileTransferResult(true, str(context, R.string.fb_opened_downloads))
                DirectOpenResult.PermissionRequestOpened ->
                    return FileTransferResult(true, str(context, R.string.fb_opened_cross_profile_permission))
                DirectOpenResult.Unavailable -> Unit
            }
            when (val result = prepareForwarderIntent(context, forwarderIntent())) {
                is ProfileBridgeResult.Value -> {
                    val intent = result.value
                    if (intent != null) {
                        activity.startActivity(intent)
                        return FileTransferResult(true, str(context, R.string.fb_opened_downloads))
                    }
                }
                else -> return FileTransferResult(
                    false,
                    profileBridgeFailureMessage(context, result, str(context, R.string.fb_open_files_failed)),
                    failureReason = result.failureReason(),
                )
            }
            if (startProfileEntry(activity, profile)) {
                return FileTransferResult(true, str(context, R.string.fb_opened_profile_entry))
            }
            FileTransferResult(false, str(context, R.string.fb_open_files_failed))
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "open profile downloads failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_open_downloads_failed))
        }
    }

    private fun startProfileEntry(activity: Activity, profile: UserHandle): Boolean =
        ProfileEntryLauncher.start(activity, profile)

    private fun startDirectly(activity: Activity, profile: UserHandle, intent: Intent): DirectOpenResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return DirectOpenResult.Unavailable
        return runCatching {
            val crossProfileApps = activity.getSystemService(CrossProfileApps::class.java) ?: return DirectOpenResult.Unavailable
            val canInteract = crossProfileApps.canInteractAcrossProfiles()
            if (!canInteract) {
                if (CrossProfileAccessPrompt.shouldPrompt(
                        Build.VERSION.SDK_INT,
                        canInteract,
                        crossProfileApps.canRequestInteractAcrossProfiles(),
                    )
                ) {
                    activity.startActivity(crossProfileApps.createRequestInteractAcrossProfilesIntent())
                    return DirectOpenResult.PermissionRequestOpened
                }
                return DirectOpenResult.Unavailable
            }
            crossProfileApps.startActivity(intent, profile, activity)
            DirectOpenResult.Opened
        }.onFailure {
            DiagnosticLog.w(TAG, "direct profile downloads launch failed; falling back to intent forwarder", it)
        }.getOrDefault(DirectOpenResult.Unavailable)
    }

    private fun prepareForwarderIntent(context: Context, intent: Intent): ProfileBridgeResult<Intent?> {
        val install = installForwarding(context)
        if (install !is ProfileBridgeResult.Value) return install.asFailureResult()
        if (install.value != true) return ProfileBridgeResult.Value(null)
        val forwarder = findCrossProfileForwarder(context, intent) ?: return ProfileBridgeResult.Value(null)
        return ProfileBridgeResult.Value(intent.setComponent(forwarder).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    private fun installForwarding(context: Context): ProfileBridgeResult<Boolean> =
        runProfileBridgeOperation(
            context,
            TAG,
            "profile downloads forwarding install",
            command = InstallCrossProfileForwarding(CrossProfileForwardingKind.ProfileDownloads),
        )

    private fun findCrossProfileForwarder(context: Context, intent: Intent): ComponentName? =
        context.packageManager.queryIntentActivities(
            Intent(intent).setComponent(null),
            MATCH_DISABLED_COMPONENTS or MATCH_DEFAULT_ONLY,
        )
            .firstOrNull { it.activityInfo.packageName == "android" }
            ?.activityInfo
            ?.run { ComponentName(packageName, name) }

    private enum class DirectOpenResult { Opened, PermissionRequestOpened, Unavailable }

    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    private companion object {
        private const val TAG = "Prism.ProfileDownloadsOpen"
    }
}

/** Cross-profile trampoline into the dual space's PrismSpace install entry (foreground APK install). */
internal object ProfileDownloadsLauncher {
    private const val ACTION_PROFILE_DOWNLOADS = "com.yzddmr6.prismspace.action.PROFILE_DOWNLOADS"
    const val EXTRA_OPEN_INSTALL_ENTRY = "com.yzddmr6.prismspace.extra.OPEN_INSTALL_ENTRY"

    fun buildCrossProfileActivityIntent(): Intent =
        Intent(ACTION_PROFILE_DOWNLOADS)
            .addCategory(CrossProfile.CATEGORY_MANAGED_PROFILE)
            .addCategory(Intent.CATEGORY_DEFAULT)

    fun buildDirectProfileActivityIntent(packageName: String): Intent =
        buildCrossProfileActivityIntent().setComponent(profileActivityComponent(packageName))

    fun buildCrossProfileInstallEntryIntent(): Intent =
        buildCrossProfileActivityIntent().putExtra(EXTRA_OPEN_INSTALL_ENTRY, crossProfileInstallEntryIntentSpec().openInstallEntry)

    fun buildDirectProfileInstallEntryIntent(packageName: String): Intent =
        directProfileInstallEntryIntentSpec(packageName).toIntent()

    fun directProfileInstallEntryIntentSpec(packageName: String): DirectProfileDownloadsActivityIntentSpec =
        DirectProfileDownloadsActivityIntentSpec(
            packageName = packageName,
            className = profileActivityClassName(),
            openInstallEntry = true,
        )

    fun crossProfileInstallEntryIntentSpec(): ProfileDownloadsActivityIntentSpec =
        ProfileDownloadsActivityIntentSpec(
            action = ACTION_PROFILE_DOWNLOADS,
            categories = setOf(CrossProfile.CATEGORY_MANAGED_PROFILE, Intent.CATEGORY_DEFAULT),
            openInstallEntry = true,
        )

    fun crossProfileActivityIntentFilter(): IntentFilter =
        IntentFilter(ACTION_PROFILE_DOWNLOADS).apply {
            addCategory(CrossProfile.CATEGORY_MANAGED_PROFILE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

    // Parent -> managed: intents fired in the main space resolve to the dual-space ProfileDownloadsActivity.
    fun crossProfileForwardingFlags() = FLAG_MANAGED_CAN_ACCESS_PARENT

    fun crossProfilePreferredActivityComponent(context: Context) = ComponentName(
        context.packageName,
        profileActivityClassName(),
    )

    fun profileActivityComponent(packageName: String) = ComponentName(
        packageName,
        profileActivityClassName(),
    )

    fun profileEntryComponent(packageName: String) = ComponentName(
        packageName,
        profileEntryClassName(),
    )

    private fun profileActivityClassName() =
        com.yzddmr6.prismspace.prism.ui.ProfileDownloadsActivity::class.java.name

    fun profileEntryClassName() =
        PrismSettingsActivity::class.java.name

    private fun DirectProfileDownloadsActivityIntentSpec.toIntent(): Intent =
        buildDirectProfileActivityIntent(packageName).apply {
            if (openInstallEntry) putExtra(EXTRA_OPEN_INSTALL_ENTRY, true)
        }
}

internal data class ProfileDownloadsActivityIntentSpec(
    val action: String,
    val categories: Set<String>,
    val openInstallEntry: Boolean,
)

internal data class DirectProfileDownloadsActivityIntentSpec(
    val packageName: String,
    val className: String,
    val openInstallEntry: Boolean,
)

internal object CrossProfileAccessPrompt {
    fun shouldPrompt(sdkInt: Int, canInteract: Boolean, canRequest: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.R && !canInteract && canRequest
}

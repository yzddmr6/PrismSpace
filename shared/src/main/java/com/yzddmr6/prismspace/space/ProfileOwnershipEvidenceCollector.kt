package com.yzddmr6.prismspace.space

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.Users.Companion.toId
import com.yzddmr6.prismspace.util.VendorCloneProfiles

/** Per-profile ownership evidence: the only inputs to [SpaceStateClassifier.rank]. */
data class ProfileOwnershipEvidence(
    val prismPackagePresent: Boolean,
    val ownershipMarkerPresent: Boolean,
    val profileOwnerPackage: String?,
    val vendorProfileType: VendorProfileType,
) {
    /** Facts carrying only ownership evidence; runtime fields keep their defaults. */
    fun toFacts(userId: Int) = SpaceProfileFacts(
        userId = userId,
        prismPackagePresent = prismPackagePresent,
        ownershipMarkerPresent = ownershipMarkerPresent,
        profileOwnerPackage = profileOwnerPackage,
        vendorProfileType = vendorProfileType,
    )
}

/**
 * The single collector of ownership evidence, used by both `Users.refreshUsers` (parent side) and
 * the space-state fact collector, so they can never disagree on which profile is ours.
 * Only the settings-entry marker counts as a launcher-activity signal: other launcher entries
 * (e.g. the engine's convergence trampoline, enabled by default in every user) prove nothing.
 * Blocking binder calls; call off the main thread where possible.
 */
class ProfileOwnershipEvidenceCollector(
    private val context: Context,
    private val logTag: String,
) {
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)

    fun collect(profile: UserHandle): ProfileOwnershipEvidence {
        val userId = profile.toId()
        val la = launcherApps
        val packagePresent = runCatching {
            la != null && LauncherAppsCompat.getApplicationInfoNoThrows(
                la, Modules.MODULE_ENGINE, MATCH_UNINSTALLED_PACKAGES, profile,
            )?.installed == true
        }.onFailure {
            DiagnosticLog.w(logTag, "package fact unavailable user=$userId", it)
        }.getOrDefault(false)
        val markerPresent = runCatching {
            la != null && la.getActivityList(Modules.MODULE_ENGINE, profile).orEmpty()
                .any { it.componentName.className == Modules.ACTIVITY_PROFILE_SETTINGS_ENTRY }
        }.onFailure {
            DiagnosticLog.w(logTag, "marker fact unavailable user=$userId", it)
        }.getOrDefault(false)
        val ownerPackage = runCatching {
            DevicePolicies.getProfileOwnerAsUser(context, profile)?.orElse(null)?.packageName
        }.onFailure {
            DiagnosticLog.w(logTag, "profile owner fact unavailable user=$userId", it)
        }.getOrNull()
        val userType = if (SDK_INT >= VANILLA_ICE_CREAM && la != null) runCatching {
            la.getLauncherUserInfo(profile)?.userType
        }.onFailure {
            DiagnosticLog.w(logTag, "profile type fact unavailable user=$userId", it)
        }.getOrNull() else null
        return ProfileOwnershipEvidence(
            prismPackagePresent = packagePresent,
            ownershipMarkerPresent = markerPresent,
            profileOwnerPackage = ownerPackage,
            vendorProfileType = VendorCloneProfiles.profileType(SDK_INT, userType),
        )
    }
}

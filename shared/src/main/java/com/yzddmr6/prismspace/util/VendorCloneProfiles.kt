package com.yzddmr6.prismspace.util

/** Whether a vendor system-level clone profile (e.g. HyperOS XSpace) exists next to this user. */
enum class CloneProfilePresence { Present, Absent, Unknown }

/**
 * Read-only detection of CLONE-type profiles, kept apart from space classification: a clone profile
 * is never a PrismSpace space, and the notice it drives changes no routing.
 *
 * Only the public user type is used (API 35+). No user-id, vendor-name or hidden-API heuristics:
 * below API 35, or when a profile's type cannot be read, the answer is [CloneProfilePresence.Unknown]
 * and no notice is shown.
 */
object VendorCloneProfiles {
    /** Same value as `UserManager.USER_TYPE_PROFILE_CLONE` (API 35). */
    const val USER_TYPE_PROFILE_CLONE = "android.os.usertype.profile.CLONE"
    const val MIN_SDK = 35

    /** [profileTypes]: the user type of every profile in this profile group; null = unreadable. */
    fun presence(sdkInt: Int, profileTypes: List<String?>): CloneProfilePresence = when {
        sdkInt < MIN_SDK -> CloneProfilePresence.Unknown
        profileTypes.any { it == USER_TYPE_PROFILE_CLONE } -> CloneProfilePresence.Present
        profileTypes.any { it == null } -> CloneProfilePresence.Unknown
        else -> CloneProfilePresence.Absent
    }

    /** Conservative: only a positively identified CLONE profile shows the notice. */
    fun hasVendorCloneProfile(sdkInt: Int, profileTypes: List<String?>): Boolean =
        presence(sdkInt, profileTypes) == CloneProfilePresence.Present
}

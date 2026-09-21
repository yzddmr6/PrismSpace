package com.yzddmr6.prismspace.settings.profile

import android.content.pm.PackageInstaller
import com.yzddmr6.prismspace.space.HandoffEvidence
import com.yzddmr6.prismspace.space.HandoffVerdict
import com.yzddmr6.prismspace.space.classifyHandoff

/**
 * What actually happened to a profile-side PackageInstaller session, once the raw status code has
 * been read together with the real package state and the time the user had to answer.
 */
sealed class ProfileInstallOutcome {
    /** The app is installed in this profile, whatever the session reported. */
    object Succeeded : ProfileInstallOutcome()

    /** The system installer aborted before a human could have answered its confirmation. */
    data class Refused(val installerLabel: String?, val singleApk: Boolean) : ProfileInstallOutcome()

    /** The user answered the confirmation with "cancel". */
    object Cancelled : ProfileInstallOutcome()

    /** Any other failure; [message] is the untruncated system status message. */
    data class Failed(val message: String) : ProfileInstallOutcome()
}

/**
 * Below this a human could not have read and answered the system confirm dialog, so an abort is the
 * installer's own refusal. MIUI's InstallStart lifecycle alone is ~260 ms; the refusal observed on
 * HyperOS 3 arrived 214 ms after the confirmation was requested.
 */
internal const val INSTALL_CONFIRM_INTERACTION_MS = 1_500L

/**
 * Classify one finished session. Pure: every Android fact is passed in.
 *
 * `INSTALL_FAILED_ABORTED: User rejected permissions` is returned both by a genuine user cancel and
 * by a vendor installer refusing on its own, so the code alone is never enough: [installedNow] is
 * the verified package state (vendor installers may install the app and still abort the session)
 * and [elapsedMs] separates a refusal from a human tap.
 *
 * @param apkCount number of files in the APK set; a split suite cannot be handed to a file manager.
 * @param installerLabel "<package>/<versionName>" of the confirmation activity, for the dialog.
 */
fun profileInstallOutcome(
    status: Int,
    message: String?,
    installedNow: Boolean,
    elapsedMs: Long?,
    apkCount: Int,
    installerLabel: String? = null,
): ProfileInstallOutcome {
    val verdict = classifyHandoff(
        HandoffEvidence(
            codeSignalsCancel = status == PackageInstaller.STATUS_FAILURE_ABORTED,
            goalReached = installedNow,
            elapsedMs = elapsedMs,
            precheckRefused = false,
            userInteractionThresholdMs = INSTALL_CONFIRM_INTERACTION_MS,
        )
    )
    return when (verdict) {
        HandoffVerdict.Succeeded -> ProfileInstallOutcome.Succeeded
        HandoffVerdict.SystemRefused -> ProfileInstallOutcome.Refused(installerLabel, singleApk = apkCount == 1)
        HandoffVerdict.UserCancelled -> ProfileInstallOutcome.Cancelled
        HandoffVerdict.Pending -> ProfileInstallOutcome.Failed(message ?: "status=$status")
    }
}

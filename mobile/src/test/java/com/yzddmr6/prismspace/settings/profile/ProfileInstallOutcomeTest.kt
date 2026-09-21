package com.yzddmr6.prismspace.settings.profile

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `INSTALL_FAILED_ABORTED: User rejected permissions` is what both a vendor installer's own refusal
 * (HyperOS 3, 214 ms after the confirmation was requested) and a real user cancel report, so the
 * status code alone decides nothing.
 */
class ProfileInstallOutcomeTest {

    private val aborted = PackageInstaller.STATUS_FAILURE_ABORTED

    @Test fun abortIsIgnoredWhenTheAppIsInstalledAnyway() {
        // A vendor installer may take over, install the app and still abort the session.
        assertEquals(
            ProfileInstallOutcome.Succeeded,
            profileInstallOutcome(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", installedNow = true, elapsedMs = 120, apkCount = 1),
        )
    }

    @Test fun abortFasterThanAHumanIsTheSystemRefusingASingleApk() {
        assertEquals(
            ProfileInstallOutcome.Refused(installerLabel = null, singleApk = true),
            profileInstallOutcome(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", installedNow = false, elapsedMs = 214, apkCount = 1),
        )
    }

    @Test fun refusedSplitSuiteCannotBeHandedToAFileManager() {
        assertEquals(
            ProfileInstallOutcome.Refused(installerLabel = null, singleApk = false),
            profileInstallOutcome(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", installedNow = false, elapsedMs = 214, apkCount = 3),
        )
    }

    @Test fun abortAfterTimeToReadTheDialogIsARealUserCancel() {
        assertEquals(
            ProfileInstallOutcome.Cancelled,
            profileInstallOutcome(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", installedNow = false, elapsedMs = 8_000, apkCount = 1),
        )
    }

    @Test fun abortWithoutTimingIsNotBlamedOnTheSystem() {
        // No confirmation was seen launching, so nothing proves a refusal: do not accuse the ROM.
        assertEquals(
            ProfileInstallOutcome.Cancelled,
            profileInstallOutcome(aborted, "INSTALL_FAILED_ABORTED: User rejected permissions", installedNow = false, elapsedMs = null, apkCount = 1),
        )
    }

    @Test fun otherFailuresKeepTheirFullSystemMessage() {
        val message = "INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location"
        assertEquals(
            ProfileInstallOutcome.Failed(message),
            profileInstallOutcome(PackageInstaller.STATUS_FAILURE_STORAGE, message, installedNow = false, elapsedMs = 30, apkCount = 1),
        )
    }

    @Test fun refusalCarriesTheInstallerForTheDialog() {
        assertEquals(
            ProfileInstallOutcome.Refused(installerLabel = "com.miui.packageinstaller/3.0.6", singleApk = true),
            profileInstallOutcome(aborted, null, installedNow = false, elapsedMs = 214, apkCount = 1, installerLabel = "com.miui.packageinstaller/3.0.6"),
        )
    }
}

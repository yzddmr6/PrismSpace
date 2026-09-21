package com.yzddmr6.prismspace.settings.profile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileApkInstallerCompletionTest {

    private val source = File("src/main/java/com/yzddmr6/prismspace/settings/profile/ProfileApkInstaller.kt").readText()

    @Test fun verifiedSuccessNotifiesParentWithExactPackage() {
        val completion = source.substringAfter("private fun BroadcastReceiver.completeInstall")
            .substringBefore("private fun isInstalledHere")

        assertTrue(source.contains("putExtra(EXTRA_PACKAGE, pkg)"))
        assertTrue(completion.contains("CompleteClonePreparation(packageName)"))
        assertTrue(completion.contains("Bridge.inParent"))
        assertTrue(source.substringAfter("PackageInstaller.STATUS_SUCCESS ->").substringBefore("else ->")
            .contains("completeInstall(c, loc, packageName)"))
    }

    @Test fun anAbortedSessionThatInstalledTheAppTakesTheSameCompletionPath() {
        // Vendor installers abort the session after installing the app (cf. F-Droid #2837): the
        // parent's pending preparation must be closed there too, not left stuck.
        val verified = source.substringAfter("is ProfileInstallOutcome.Succeeded ->")
            .substringBefore("is ProfileInstallOutcome.Refused ->")
        assertTrue(verified.contains("completeInstall(c, loc, packageName)"))
        assertTrue(source.contains("isInstalledHere(c, packageName)"))
    }
}

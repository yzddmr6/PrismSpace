package com.yzddmr6.prismspace.prism.service

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDownloadsLauncherTest {

    @Test fun describesCrossProfileTrampolineIntentFromMainToManagedProfile() {
        assertEquals(
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
            ProfileDownloadsLauncher.crossProfileForwardingFlags(),
        )
    }

    @Test fun installEntrySpecCarriesForegroundInstallExtra() {
        val spec = ProfileDownloadsLauncher.crossProfileInstallEntryIntentSpec()

        assertTrue(spec.openInstallEntry)
    }

    @Test fun sourceSettingsSpecCarriesTargetPackage() {
        val spec = ProfileDownloadsLauncher.crossProfileSourceSettingsIntentSpec("com.android.fileexplorer")

        assertEquals("com.android.fileexplorer", spec.openSourceSettingsForPackage)
    }

    @Test fun installEntryIntentTargetsProfileDownloadsInRequestedProfile() {
        val spec = ProfileDownloadsLauncher.directProfileInstallEntryIntentSpec("com.example.prism")

        assertEquals("com.example.prism", spec.packageName)
        assertEquals("com.yzddmr6.prismspace.prism.ui.ProfileDownloadsActivity", spec.className)
        assertTrue(spec.openInstallEntry)
    }

    @Test fun profileEntryClassTargetsLauncherVisibleProfileEntry() {
        assertEquals(
            "com.yzddmr6.prismspace.settings.PrismSettingsActivity",
            ProfileDownloadsLauncher.profileEntryClassName(),
        )
    }

    @Test fun sourceSettingsIntentTargetsProfileDownloadsInRequestedProfile() {
        val spec = ProfileDownloadsLauncher.directProfileSourceSettingsIntentSpec(
            "com.example.prism",
            "com.android.fileexplorer",
        )

        assertEquals("com.example.prism", spec.packageName)
        assertEquals("com.yzddmr6.prismspace.prism.ui.ProfileDownloadsActivity", spec.className)
        assertEquals("com.android.fileexplorer", spec.openSourceSettingsForPackage)
    }
}

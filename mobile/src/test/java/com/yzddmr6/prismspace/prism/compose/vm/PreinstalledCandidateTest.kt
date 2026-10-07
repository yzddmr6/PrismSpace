package com.yzddmr6.prismspace.prism.compose.vm

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples are real paths from the Xiaomi 13 / HyperOS 3 test device (`pm list packages -3 -i -f --user 0`). */
class PreinstalledCandidateTest {

    @Test fun oemPreinstallsUnderDataAppAreRecognized() {
        assertTrue(isOemDataAppPreinstall(0, "/data/app/MIUIGallery/base.apk", "com.miui.gallery", null))
        assertTrue(isOemDataAppPreinstall(0, "/data/app/MIUICalendar/base.apk", "com.android.calendar", null))
        assertTrue(isOemDataAppPreinstall(0, "/data/app/MIUISoundRecorderTargetSdk30/base.apk", "com.android.soundrecorder", ""))
    }

    @Test fun userInstallsAreNotPreinstalls() {
        // Modern randomized layout, including installer=null sideloads (mark.via, ksunext, prismspace).
        assertFalse(isOemDataAppPreinstall(0, "/data/app/~~x/mark.via-y/base.apk", "mark.via", null))
        // Legacy "<pkg>-N" layout.
        assertFalse(isOemDataAppPreinstall(0, "/data/app/com.foo-1/base.apk", "com.foo", null))
        // Store installs carry an installer.
        assertFalse(isOemDataAppPreinstall(0, "/data/app/MIUIGallery/base.apk", "com.miui.gallery", "com.android.vending"))
    }

    @Test fun systemOrMislocatedPackagesAreNotPreinstalls() {
        assertFalse(isOemDataAppPreinstall(ApplicationInfo.FLAG_SYSTEM, "/data/app/MIUIGallery/base.apk", "com.miui.gallery", null))
        assertFalse(isOemDataAppPreinstall(0, "/product/app/MIUIGallery/MIUIGallery.apk", "com.miui.gallery", null))
        assertFalse(isOemDataAppPreinstall(0, "/data/app/base.apk", "com.miui.gallery", null))
        assertFalse(isOemDataAppPreinstall(0, null, "com.miui.gallery", null))
    }
}

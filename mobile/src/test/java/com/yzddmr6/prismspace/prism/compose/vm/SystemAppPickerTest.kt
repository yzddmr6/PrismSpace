package com.yzddmr6.prismspace.prism.compose.vm

import android.content.pm.ApplicationInfo
import com.yzddmr6.prismspace.bridge.MAX_SYSTEM_APP_SELECTION_CHANGES
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import com.yzddmr6.prismspace.bridge.SystemAppSelectionEntry
import com.yzddmr6.prismspace.controller.chunkSelection
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.provisioning.SystemAppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppPickerTest {

    private fun candidate(pkg: String, inDefault: Boolean, target: SystemAppTarget?) =
        SystemAppCandidate(pkg, pkg, inDefault, target)

    private val camera = candidate("com.android.camera", inDefault = true, target = SystemAppTarget.Unavailable)
    private val browser = candidate("com.android.browser", inDefault = true, target = SystemAppTarget.Unavailable)
    private val mms = candidate("com.android.mms", inDefault = false, target = SystemAppTarget.Unavailable)
    private val market = candidate("com.xiaomi.market", inDefault = false, target = SystemAppTarget.Available)

    @Test fun pendingPreselectsTheDefaultSet() {
        val checked = preselectedSystemApps(SelectionStatus.Pending, listOf(camera, browser, mms, market))
        assertEquals(setOf("com.android.camera", "com.android.browser"), checked)
        assertEquals(checked, preselectedSystemApps(null, listOf(camera, browser, mms, market)))
    }

    @Test fun laterVisitsPreselectTheCurrentTarget() {
        for (status in listOf(SelectionStatus.Deferred, SelectionStatus.Confirmed)) {
            assertEquals(setOf("com.xiaomi.market"), preselectedSystemApps(status, listOf(camera, browser, mms, market)))
        }
    }

    @Test fun commitRecordsOnlyDeviationsFromTheRuleResult() {
        val commit = commitSelection(SelectionStatus.Confirmed, listOf(camera, browser, mms, market),
            checked = setOf("com.android.camera", "com.xiaomi.market"))
        val choices = commit.changes.associate { it.pkg to it.choice }
        assertEquals(SystemAppChoice.Clear, choices["com.android.camera"])       // default ∧ checked
        assertEquals(SystemAppChoice.Disabled, choices["com.android.browser"])   // default unchecked → explicit removal
        assertEquals(SystemAppChoice.Clear, choices["com.android.mms"])          // non-default unchecked
        assertEquals(SystemAppChoice.Enabled, choices["com.xiaomi.market"])      // non-default checked
        assertTrue(commit.prepare.isEmpty())
    }

    @Test fun deferredToConfirmWritesDisabledForUncheckedDefaults() {
        // A seeded space (Deferred) confirming the page: unchecking a default must stick as Disabled.
        val commit = commitSelection(SelectionStatus.Confirmed, listOf(camera), checked = emptySet())
        assertEquals(listOf(SystemAppOverrideChange("com.android.camera", SystemAppChoice.Disabled)), commit.changes)
    }

    @Test fun preinstallsAreStagedOnlyWhenChecked() {
        val commit = commitSelection(SelectionStatus.Confirmed, emptyList(), emptySet(), preinstalledChecked = setOf("com.miui.gallery"))
        assertEquals(listOf("com.miui.gallery"), commit.prepare)
    }

    @Test fun selectionsAreChunkedToTheBridgeBound() {
        val changes = List(MAX_SYSTEM_APP_SELECTION_CHANGES * 2 + 1) { SystemAppOverrideChange("p$it", SystemAppChoice.Clear) }
        val chunks = chunkSelection(changes)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= MAX_SYSTEM_APP_SELECTION_CHANGES })
        assertEquals(changes, chunks.flatten())
        assertEquals(listOf(emptyList<SystemAppOverrideChange>()), chunkSelection(emptyList()))   // "Later" still finishes.
    }

    @Test fun bridgeFailureNeverAdvancesStatus() {
        val ready = SystemAppPickerReducer.loaded(SystemAppPickerUiState(), SelectionStatus.Pending, listOf(camera), emptyList())
        val submitting = SystemAppPickerReducer.submitting(ready)
        assertEquals(SystemAppPickerPhase.Submitting, submitting.phase)
        val failed = SystemAppPickerReducer.committed(submitting, success = false, error = "bridge down", confirmedStatus = SelectionStatus.Confirmed)
        assertEquals(SystemAppPickerPhase.Ready, failed.phase)
        assertEquals(SelectionStatus.Pending, failed.status)
        assertEquals("bridge down", failed.error)
        val done = SystemAppPickerReducer.committed(submitting, success = true, error = null, confirmedStatus = SelectionStatus.Confirmed)
        assertEquals(SystemAppPickerPhase.Done, done.phase)
        assertEquals(SelectionStatus.Confirmed, done.status)
    }

    @Test fun togglesOnlyWhileReady() {
        val ready = SystemAppPickerReducer.loaded(SystemAppPickerUiState(), SelectionStatus.Pending, listOf(camera, mms),
            listOf(PreinstalledCandidate("com.miui.gallery", "相册")))
        assertTrue("com.android.camera" in ready.checked)
        assertTrue(ready.preinstalledChecked.isEmpty())        // Preinstalls start unchecked.
        val toggled = SystemAppPickerReducer.toggle(SystemAppPickerReducer.toggle(ready, "com.android.camera"), "com.miui.gallery")
        assertFalse("com.android.camera" in toggled.checked)
        assertEquals(setOf("com.miui.gallery"), toggled.preinstalledChecked)
        val submitting = SystemAppPickerReducer.submitting(ready)
        assertEquals(submitting, SystemAppPickerReducer.toggle(submitting, "com.android.mms"))
    }

    @Test fun groupsExcludeCriticalPackagesAndSplitPreinstalls() {
        val main = listOf(
            MainLauncherApp("com.android.settings", "设置", ApplicationInfo.FLAG_SYSTEM, "/system/priv-app/Settings/Settings.apk", null, true),
            MainLauncherApp("com.android.camera", "相机", ApplicationInfo.FLAG_SYSTEM, "/product/priv-app/MiuiCamera/MiuiCamera.apk", null, true),
            MainLauncherApp("com.miui.gallery", "相册", 0, "/data/app/MIUIGallery/base.apk", null, installedInDual = false),
            MainLauncherApp("com.android.calendar", "日历", 0, "/data/app/MIUICalendar/base.apk", null, installedInDual = true),
            MainLauncherApp("mark.via", "Via", 0, "/data/app/~~a/mark.via-b/base.apk", null, installedInDual = false),
            MainLauncherApp("com.yzddmr6.prismspace", "PrismSpace", 0, "/data/app/~~c/com.yzddmr6.prismspace-d/base.apk", null, false),
        )
        val entries = listOf(
            SystemAppSelectionEntry("com.android.settings", null, SystemAppTarget.Available, critical = true, inDefault = false,
                installed = true, hasLauncherEntry = true),
            SystemAppSelectionEntry("com.android.camera", null, SystemAppTarget.Unavailable, critical = false, inDefault = true,
                installed = true, hasLauncherEntry = true),
        )
        val (system, preinstalled) = pickerGroups(main, entries, selfPackage = "com.yzddmr6.prismspace")
        assertEquals(listOf("com.android.camera"), system.map { it.pkg })
        assertTrue(system.single().inDefault)
        assertEquals(listOf("com.miui.gallery"), preinstalled.map { it.pkg })   // Calendar is already in the dual space.
    }
}

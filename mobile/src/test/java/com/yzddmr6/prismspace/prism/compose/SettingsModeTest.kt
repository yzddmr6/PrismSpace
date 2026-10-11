package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import com.yzddmr6.prismspace.prism.compose.vm.mapSettingsUiModel
import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import com.yzddmr6.prismspace.prism.model.CapabilityAvailability
import com.yzddmr6.prismspace.prism.model.CapabilityState
import com.yzddmr6.prismspace.prism.model.PrismRootStatus
import com.yzddmr6.prismspace.prism.model.PrismSettingsModeState
import com.yzddmr6.prismspace.prism.model.PrismShizukuAdbStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests pure mode-label logic in mapSettingsUiModel().
 *
 * Pure logic covered:
 *   - setNormal → Normal active
 *   - checkShizuku when not ready → mode unchanged (isActive reflects selectedMode)
 *   - checkmark (isActive) follows selectedMode, not live re-detection alone
 *   - spaceSuspended default value
 *   - modeBody variants per profileOwner/shizukuAuthorized
 */
class SettingsModeTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun modeState(shizuku: PrismShizukuAdbStatus) = PrismSettingsModeState.from(
        shizuku = shizuku,
        root = PrismRootStatus.NotDetected,
        res = testZhResolver,
    )

    private fun capState(
        profileOwner: Boolean = true,
        shizukuReady: Boolean = false,
        rootEnabled: Boolean = false,
    ) = CapabilityState(
        normal = CapabilityAvailability.Available,
        shizuku = if (shizukuReady) CapabilityAvailability.Available
                  else CapabilityAvailability.NeedsSetup,
        adb = CapabilityAvailability.NeedsSetup,
        root = if (rootEnabled) CapabilityAvailability.Available
               else CapabilityAvailability.Unsupported,
        profileOwner = if (profileOwner) CapabilityAvailability.Available
                       else CapabilityAvailability.NeedsSetup,
    )

    // ---------------------------------------------------------------------------
    // setNormal -> selectedMode=Normal -> normalMode.isActive==true
    // ---------------------------------------------------------------------------
    @Test
    fun `setNormal - normalMode isActive when selectedMode is Normal`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(),
            selectedMode = PrismMode.Normal,
        )
        assertTrue("Normal mode must be active when selectedMode=Normal", model.normalMode.isActive)
        assertFalse("Shizuku mode must not be active", model.shizukuAdbMode.isActive)
        assertFalse("Root mode must not be active", model.rootMode.isActive)
        assertEquals(PrismMode.Normal, model.selectedMode)
    }

    // ---------------------------------------------------------------------------
    // checkShizuku when not ready -> selectedMode stays unchanged (Normal)
    // isActive for shizuku row must NOT become true when shizuku capability unavailable
    // ---------------------------------------------------------------------------
    @Test
    fun `checkShizuku when not ready - mode unchanged, shizukuAdb not active`() {
        // Simulate: user tried ShizukuAdb but Shizuku was not ready;
        // selectedMode stays Normal (not updated to ShizukuAdb on failure)
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(shizukuReady = false),
            selectedMode = PrismMode.Normal, // mode was NOT changed because Shizuku not ready
        )
        assertFalse("Shizuku row must NOT be active when selectedMode stayed Normal", model.shizukuAdbMode.isActive)
        assertTrue("Normal row must remain active when mode unchanged", model.normalMode.isActive)
    }

    // ---------------------------------------------------------------------------
    // checkmark (isActive) follows selectedMode when Shizuku is capable and selected
    // ---------------------------------------------------------------------------
    @Test
    fun `checkmark follows selectedMode - ShizukuAdb active when selected and capable`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = true,
            modeState = modeState(PrismShizukuAdbStatus.Ready),
            capabilityState = capState(shizukuReady = true),
            selectedMode = PrismMode.Shizuku,
        )
        assertTrue("Shizuku mode must be active when selectedMode=ShizukuAdb and capable", model.shizukuAdbMode.isActive)
        assertFalse("Normal mode must not be active when ShizukuAdb selected", model.normalMode.isActive)
        assertFalse("Root mode must not be active", model.rootMode.isActive)
        assertEquals(PrismMode.Shizuku, model.selectedMode)
    }

    // ---------------------------------------------------------------------------
    // Selection remains visible independently from current readiness.
    // ---------------------------------------------------------------------------
    @Test
    fun `rootMode isActive when selected and root capability Available`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(rootEnabled = true),
            selectedMode = PrismMode.Root,
        )
        assertTrue("Root mode must be active when selectedMode=Root and capable", model.rootMode.isActive)
        assertFalse("Shizuku mode must not be active", model.shizukuAdbMode.isActive)
        assertFalse("Normal mode must not be active when Root selected", model.normalMode.isActive)
    }

    @Test
    fun `stale Root preference remains selected while status says unavailable`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = PrismSettingsModeState.from(
                PrismShizukuAdbStatus.NotRunning,
                PrismRootStatus.Unavailable,
                res = { id, _ -> if (id == com.yzddmr6.prismspace.mobile.R.string.lz_vm_root_status_unavailable) "当前不可用" else id.toString() },
            ),
            capabilityState = capState(rootEnabled = false),
            selectedMode = PrismMode.Root,
        )

        assertTrue(model.rootMode.isActive)
        assertEquals("当前不可用", model.rootMode.statusLabel)
    }

    // ---------------------------------------------------------------------------
    // Root mode is inactive when selectedMode is not Root
    // ---------------------------------------------------------------------------
    @Test
    fun `rootMode not active when capable but selectedMode is Normal`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(rootEnabled = true),
            selectedMode = PrismMode.Normal, // user chose Normal even though root capable
        )
        assertFalse("Root mode must NOT be active when selectedMode=Normal", model.rootMode.isActive)
        assertTrue("Normal mode must be active when selectedMode=Normal", model.normalMode.isActive)
    }

    // ---------------------------------------------------------------------------
    // Mode label: when neither root nor shizuku selected → both inactive, normal is active
    // Used by SettingsScreen to show "普通模式" label for 运行模式 row
    // ---------------------------------------------------------------------------
    @Test
    fun `normalMode always active, shizuku and root inactive by default`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(),
            selectedMode = PrismMode.Normal,
        )
        assertTrue("Normal mode must be active when selectedMode=Normal", model.normalMode.isActive)
        assertFalse("Shizuku mode must not be active in normal mode", model.shizukuAdbMode.isActive)
        assertFalse("Root mode must not be active in normal mode", model.rootMode.isActive)
    }

    // ---------------------------------------------------------------------------
    // modeBody: no profileOwner → contains "未创建" (drives Error level + body copy)
    // ---------------------------------------------------------------------------
    @Test
    fun `modeBody contains 未创建 when profileOwner false`() {
        val model = mapSettingsUiModel(
            profileOwner = false,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(profileOwner = false),
        )
        assertTrue(
            "modeBody should contain 未创建 when profile not created",
            model.modeBody.contains("未创建"),
        )
        assertEquals(PrismLevel.Error, model.level)
    }

    // ---------------------------------------------------------------------------
    // Observed freeze state defaults to Unknown; the compatibility Boolean must never invent Frozen.
    // ---------------------------------------------------------------------------
    @Test
    fun `space freeze defaults to unknown in mapSettingsUiModel result`() {
        val model = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(),
        )
        assertFalse("spaceSuspended must default to false", model.spaceSuspended)
        assertEquals(com.yzddmr6.prismspace.prism.compose.vm.SpaceFreezeState.Unknown, model.spaceFreezeState)
    }

    // ---------------------------------------------------------------------------
    // Surplus profiles: the diagnostic row appears only when the partition reports them.
    // ---------------------------------------------------------------------------
    @Test
    fun `surplus profile ids pass through and default to none`() {
        val none = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(),
        )
        assertTrue("no surplus row without surplus profiles", none.surplusProfileIds.isEmpty())

        val withSurplus = mapSettingsUiModel(
            profileOwner = true,
            shizukuAuthorized = false,
            modeState = modeState(PrismShizukuAdbStatus.NotRunning),
            capabilityState = capState(),
            res = testZhResolver,
            surplusProfileIds = listOf(11, 12),
        )
        assertEquals(listOf(11, 12), withSurplus.surplusProfileIds)
        // The surplus profiles never change the primary's status presentation.
        assertEquals(none.level, withSurplus.level)
        assertEquals(none.spaceActionTitle, withSurplus.spaceActionTitle)
        assertEquals(none.profileOwnerReady, withSurplus.profileOwnerReady)
    }
}

package com.yzddmr6.prismspace.setup.compose

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshot
import com.yzddmr6.prismspace.prism.compose.space.SpaceSnapshotFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCompletionDecisionTest {

    private fun action(
        resultCode: Int,
        state: SpaceState?,
        elapsedMs: Long? = null,
        precheckRefused: Boolean = false,
    ) = setupCompletionAction(resultCode, state, elapsedMs, precheckRefused)

    @Test fun `healthy facts finish regardless of activity result`() {
        assertEquals(SetupCompletionAction.Finish, action(Activity.RESULT_OK, SpaceState.Healthy(20)))
        assertEquals(SetupCompletionAction.Finish, action(Activity.RESULT_CANCELED, SpaceState.Healthy(20)))
        assertEquals(
            SetupCompletionAction.Finish,
            action(Activity.RESULT_CANCELED, SpaceState.Healthy(20), elapsedMs = 400L, precheckRefused = true),
        )
    }

    @Test fun `explicit cancel with fresh no-profile facts shows cancellation`() {
        assertEquals(SetupCompletionAction.ShowCanceled, action(Activity.RESULT_CANCELED, SpaceState.NoProfile))
    }

    @Test fun `missing or incomplete facts keep waiting without inventing success`() {
        assertEquals(SetupCompletionAction.WaitForHealth, action(Activity.RESULT_OK, SpaceState.NoProfile))
        assertEquals(
            SetupCompletionAction.WaitForHealth,
            action(Activity.RESULT_CANCELED, SpaceState.HalfProvisioned(20, resumable = true)),
        )
        assertEquals(SetupCompletionAction.WaitForHealth, action(Activity.RESULT_OK, null))
        assertEquals(SetupCompletionAction.WaitForHealth, action(Activity.RESULT_OK, SpaceState.ForeignProfile(999)))
        // No facts: a cancel code alone must not be turned into a refusal verdict either.
        assertEquals(
            SetupCompletionAction.WaitForHealth,
            action(Activity.RESULT_CANCELED, null, elapsedMs = 1_900L, precheckRefused = true),
        )
    }

    @Test fun `a cancel too fast for a human with a vendor clone user is a system refusal`() {
        // ColorOS: user 999 is a vendor "app clone" profile, ManagedProvisioning returns
        // RESULT_CANCELED about two seconds later without ever showing a screen to the user.
        assertEquals(
            SetupCompletionAction.ShowRefused,
            action(Activity.RESULT_CANCELED, SpaceState.ForeignProfile(999), elapsedMs = 1_900L),
        )
    }

    @Test fun `a cancel the user had time to perform stays a cancellation`() {
        assertEquals(
            SetupCompletionAction.ShowCanceled,
            action(Activity.RESULT_CANCELED, SpaceState.ForeignProfile(999), elapsedMs = 8_000L),
        )
    }

    @Test fun `a pre-flight refusal makes the cancel code the platform repeating itself`() {
        assertEquals(
            SetupCompletionAction.ShowRefused,
            action(Activity.RESULT_CANCELED, SpaceState.NoProfile, elapsedMs = 8_000L, precheckRefused = true),
        )
    }

    @Test fun `provisioning wait flag restores through saved state and consumes once`() {
        val originalHandle = SavedStateHandle()
        SetupStateViewModel(originalHandle).provisioningLaunched = true

        val restored = SetupStateViewModel(SavedStateHandle(mapOf(
            SetupStateViewModel.KEY_PROVISIONING_LAUNCHED to
                originalHandle.get<Boolean>(SetupStateViewModel.KEY_PROVISIONING_LAUNCHED),
        )))

        assertTrue(restored.provisioningLaunched)
        assertTrue(restored.consumeProvisioningLaunched())
        assertFalse(restored.consumeProvisioningLaunched())
    }

    @Test fun `convergence finishes only on healthy and otherwise reaches finite recovery`() {
        assertEquals(
            SetupConvergenceAction.Finish,
            setupConvergenceAction(SpaceSnapshot.Loaded(SpaceState.Healthy(20)), remainingMs = 1),
        )
        assertEquals(
            SetupConvergenceAction.Wait,
            setupConvergenceAction(SpaceSnapshot.Loaded(SpaceState.BridgeDown(20, com.yzddmr6.prismspace.space.SpaceBridgeCause.TimedOut)), 1),
        )
        assertEquals(
            SetupConvergenceAction.Recover,
            setupConvergenceAction(SpaceSnapshot.Failed(SpaceSnapshotFailure("IO", null), null), 0),
        )
    }

    @Test fun `convergence deadline survives recreation and cannot restart indefinitely`() {
        val handle = SavedStateHandle()
        val first = SetupStateViewModel(handle)
        first.beginProvisioning()
        assertEquals(30L, first.convergenceRemaining(nowElapsedMs = 100L, timeoutMs = 30L))

        val restored = SetupStateViewModel(SavedStateHandle(mapOf(
            SetupStateViewModel.KEY_PROVISIONING_LAUNCHED to true,
            SetupStateViewModel.KEY_CONVERGENCE_DEADLINE to
                handle.get<Long>(SetupStateViewModel.KEY_CONVERGENCE_DEADLINE),
        )))
        assertEquals(20L, restored.convergenceRemaining(nowElapsedMs = 110L, timeoutMs = 30L))
        assertEquals(0L, restored.convergenceRemaining(nowElapsedMs = 131L, timeoutMs = 30L))
    }
}

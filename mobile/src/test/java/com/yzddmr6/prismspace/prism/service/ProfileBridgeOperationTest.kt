package com.yzddmr6.prismspace.prism.service

import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBridgeOperationTest {

    @Test fun valueNullRemainsSuccessfulResult() {
        val result = ProfileBridgeResult.from(ShuttleOutcome.Value<String>(null))

        assertTrue(result is ProfileBridgeResult.Value)
        assertNull((result as ProfileBridgeResult.Value).value)
    }

    @Test fun notReadyKeepsSpecificCause() {
        val result = ProfileBridgeResult.from(
            ShuttleOutcome.NotReady(ShuttleNotReadyCause.UnknownAuthority)
        )

        assertEquals(
            ProfileBridgeResult.BridgeNotReady(ShuttleNotReadyCause.UnknownAuthority),
            result,
        )
    }

    @Test fun timedOutIsDistinctFromBridgeNotReady() {
        assertSame(ProfileBridgeResult.TimedOut, ProfileBridgeResult.from(ShuttleOutcome.TimedOut))
    }

    @Test fun skippedProfileLifecycleBecomesSpaceInactive() {
        val result = ProfileBridgeResult.from(ShuttleOutcome.Skipped("profile_quiet_mode"))

        assertEquals(ProfileBridgeResult.SpaceInactive("profile_quiet_mode"), result)
        assertEquals(FileTransferFailureReason.SpaceInactive, result.failureReason())
    }

    @Test fun failedCarriesThrowable() {
        val error = IllegalStateException("boom")
        val result = ProfileBridgeResult.from(ShuttleOutcome.Failed(error))

        assertTrue(result is ProfileBridgeResult.Failed)
        assertSame(error, (result as ProfileBridgeResult.Failed).error)
    }
}

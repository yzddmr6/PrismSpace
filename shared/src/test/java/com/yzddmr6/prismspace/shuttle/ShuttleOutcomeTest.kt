package com.yzddmr6.prismspace.shuttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleOutcomeTest {

    @Test fun securityExceptionWithoutGrantIsPermissionDenied() {
        val cause = classifyShuttleNotReadyCause(SecurityException("Permission Denial"), permissionGranted = false)
        assertEquals(ShuttleNotReadyCause.PermissionDenied, cause)
    }

    @Test fun unknownAuthorityIsClassifiedSeparately() {
        val cause = classifyShuttleNotReadyCause(
            IllegalArgumentException("Unknown authority 10@com.yzddmr6.prismspace.shuttle"),
            permissionGranted = true,
        )
        assertEquals(ShuttleNotReadyCause.UnknownAuthority, cause)
    }

    @Test fun illegalArgumentWithGrantIsCallFailedWithoutChangingOldSwallowBehavior() {
        val cause = classifyShuttleNotReadyCause(IllegalArgumentException("bad call"), permissionGranted = true)
        assertEquals(ShuttleNotReadyCause.PermissionPresentCallFailed, cause)
    }

    @Test fun boundedOutcomeKeepsLegitimateNullDistinctFromTimeout() {
        val value = runBoundedOutcome(2_000L) { ShuttleOutcome.Value<String>(null) }
        assertTrue(value is ShuttleOutcome.Value)
        assertEquals(null, (value as ShuttleOutcome.Value).value)

        val timeout = runBoundedOutcome(100L) {
            Thread.sleep(1_000L)
            ShuttleOutcome.Value("late")
        }
        assertEquals(ShuttleOutcome.TimedOut, timeout)
    }
}

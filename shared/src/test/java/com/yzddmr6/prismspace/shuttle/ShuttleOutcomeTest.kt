package com.yzddmr6.prismspace.shuttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuttleOutcomeTest {

    @Test fun availableTargetIsNotSkipped() =
        assertFalse(shouldSkipForAvailability(isCurrent = false, running = true, quiet = false, unlocked = true))

    @Test fun quietStoppedOrLockedTargetIsSkipped() {
        assertTrue(shouldSkipForAvailability(isCurrent = false, running = true, quiet = true, unlocked = true))
        assertTrue(shouldSkipForAvailability(isCurrent = false, running = false, quiet = false, unlocked = true))
        assertTrue(shouldSkipForAvailability(isCurrent = false, running = true, quiet = false, unlocked = false))
        assertTrue(shouldSkipForAvailability(isCurrent = false, running = false, quiet = true, unlocked = false))
    }

    @Test fun currentUserIsNeverGated() =
        assertFalse(shouldSkipForAvailability(isCurrent = true, running = false, quiet = true, unlocked = false))

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

    @Test fun protocolIllegalArgumentIsNotMisclassifiedAsRepairable() {
        val cause = classifyShuttleNotReadyCause(IllegalArgumentException("bad call"), permissionGranted = true)
        assertEquals(null, cause)
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

    @Test fun healthDiagnosticIncludesRawPermissionChecks() {
        val line = ShuttleHealth(
            profileId = 10,
            running = true,
            quietMode = false,
            unlocked = true,
            forwardGrant = false,
            backwardGrant = true,
            ping = ShuttleOutcome.NotReady(ShuttleNotReadyCause.PermissionDenied),
            forwardGrantCheck = -1,
            backwardGrantCheck = 0,
        ).diagnosticLine()

        assertTrue(line.contains("forwardGrantCheck=-1"))
        assertTrue(line.contains("backwardGrantCheck=0"))
        assertTrue(line.contains("ping=not_ready:PermissionDenied"))
    }

    @Test fun healthDiagnosticIncludesPackageState() {
        val line = ShuttleHealth(
            profileId = 10,
            running = true,
            quietMode = false,
            unlocked = true,
            forwardGrant = true,
            backwardGrant = true,
            ping = ShuttleOutcome.NotReady(ShuttleNotReadyCause.UnknownAuthority),
            packageEnabled = true,
            packageSuspended = false,
        ).diagnosticLine()
        assertTrue(line.contains("pkgEnabled=true"))
        assertTrue(line.contains("pkgSuspended=false"))

        val unknown = ShuttleHealth(
            profileId = 10,
            running = true,
            quietMode = false,
            unlocked = true,
            forwardGrant = true,
            backwardGrant = true,
            ping = ShuttleOutcome.TimedOut,
        ).diagnosticLine()
        assertTrue(unknown.contains("pkgEnabled=-"))
        assertTrue(unknown.contains("pkgSuspended=-"))
    }
}

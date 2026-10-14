package com.yzddmr6.prismspace.settings.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #29: a large suite took 26 s from tap to confirmation and every impatient tap staged it again —
 * ten sessions for one game, then ten stacked confirmations.
 */
class ProfileInstallInFlightTest {

    @Test fun aTapWhileTheSessionIsBeingCreatedWaits() {
        assertEquals(RepeatInstallTap.StillPreparing, repeatInstallTap(sessionAlive = null, confirmationShown = false))
    }

    @Test fun aTapWhileTheSystemChecksTheCommittedSessionWaits() {
        assertEquals(RepeatInstallTap.StillPreparing, repeatInstallTap(sessionAlive = true, confirmationShown = false))
    }

    @Test fun aTapWhileTheConfirmationWaitsBringsItBackInsteadOfStagingAgain() {
        assertEquals(RepeatInstallTap.ShowConfirmation, repeatInstallTap(sessionAlive = true, confirmationShown = true))
    }

    @Test fun aSessionTheSystemNoLongerHasNeverBlocksAFreshInstall() {
        assertEquals(RepeatInstallTap.StartOver, repeatInstallTap(sessionAlive = false, confirmationShown = true))
        assertEquals(RepeatInstallTap.StartOver, repeatInstallTap(sessionAlive = false, confirmationShown = false))
    }

    @Test fun theAbortOfAnAbandonedSessionLeavesTheCurrentInstallAlone() {
        // Abandoning a session nothing could confirm makes the system report it as aborted.
        assertFalse(isStatusFor(trackedSessionId = 228619144, statusSessionId = 672914437))
    }

    @Test fun aStatusIsTakenForTheTrackedSessionOrWithoutASessionId() {
        assertTrue(isStatusFor(trackedSessionId = 672914437, statusSessionId = 672914437))
        assertTrue(isStatusFor(trackedSessionId = 672914437, statusSessionId = -1))
    }

    @Test fun noStatusBelongsToASessionStillBeingCreated() {
        assertFalse(isStatusFor(trackedSessionId = -1, statusSessionId = 672914437))
    }
}

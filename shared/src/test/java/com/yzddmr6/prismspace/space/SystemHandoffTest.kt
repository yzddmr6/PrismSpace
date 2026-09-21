package com.yzddmr6.prismspace.space

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHandoffTest {

    private fun evidence(
        codeSignalsCancel: Boolean,
        goalReached: Boolean?,
        elapsedMs: Long?,
        precheckRefused: Boolean = false,
    ) = HandoffEvidence(
        codeSignalsCancel = codeSignalsCancel,
        goalReached = goalReached,
        elapsedMs = elapsedMs,
        precheckRefused = precheckRefused,
        userInteractionThresholdMs = THRESHOLD_MS,
    )

    @Test fun `verified goal wins over any cancel code`() {
        // Vendor ROMs are known to return a cancel code after the action actually completed.
        assertEquals(
            HandoffVerdict.Succeeded,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = true, elapsedMs = 120L)),
        )
        assertEquals(
            HandoffVerdict.Succeeded,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = true, elapsedMs = null, precheckRefused = true)),
        )
        assertEquals(
            HandoffVerdict.Succeeded,
            classifyHandoff(evidence(codeSignalsCancel = false, goalReached = true, elapsedMs = null)),
        )
    }

    @Test fun `non-cancel code without a verified goal keeps waiting`() {
        assertEquals(
            HandoffVerdict.Pending,
            classifyHandoff(evidence(codeSignalsCancel = false, goalReached = false, elapsedMs = 10_000L)),
        )
        assertEquals(
            HandoffVerdict.Pending,
            classifyHandoff(evidence(codeSignalsCancel = false, goalReached = null, elapsedMs = null)),
        )
    }

    @Test fun `cancel code with unknown facts never invents a verdict`() {
        assertEquals(
            HandoffVerdict.Pending,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = null, elapsedMs = 80L)),
        )
        assertEquals(
            HandoffVerdict.Pending,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = null, elapsedMs = 80L, precheckRefused = true)),
        )
    }

    @Test fun `precheck refusal classifies a cancel code as a system refusal`() {
        assertEquals(
            HandoffVerdict.SystemRefused,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = false, elapsedMs = 30_000L, precheckRefused = true)),
        )
    }

    @Test fun `a cancel too fast for a human is the system refusing`() {
        assertEquals(
            HandoffVerdict.SystemRefused,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = false, elapsedMs = THRESHOLD_MS - 1)),
        )
    }

    @Test fun `a cancel slow enough to be read is the user backing out`() {
        assertEquals(
            HandoffVerdict.UserCancelled,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = false, elapsedMs = THRESHOLD_MS)),
        )
        assertEquals(
            HandoffVerdict.UserCancelled,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = false, elapsedMs = 8_000L)),
        )
    }

    @Test fun `missing timing with a verified unreached goal stays the user's cancel`() {
        // Without timing there is no evidence of refusal, and the pre-check said nothing:
        // attributing it to the system would be a guess, so the honest default is the user.
        assertEquals(
            HandoffVerdict.UserCancelled,
            classifyHandoff(evidence(codeSignalsCancel = true, goalReached = false, elapsedMs = null)),
        )
    }

    private companion object {
        const val THRESHOLD_MS = 2_500L
    }
}

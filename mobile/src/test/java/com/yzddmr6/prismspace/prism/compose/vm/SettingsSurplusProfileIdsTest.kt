package com.yzddmr6.prismspace.prism.compose.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSurplusProfileIdsTest {

    @Test fun parentSideReturnsTheLookup() =
        assertEquals(listOf(11, 12), settingsSurplusProfileIds(isParentProfile = true, lookup = { listOf(11, 12) }))

    /** Inside the managed profile the owner-user accessor must not even be called. */
    @Test fun profileSideNeverCallsTheLookup() {
        var called = false
        val ids = settingsSurplusProfileIds(isParentProfile = false, lookup = { called = true; listOf(11) })
        assertTrue(ids.isEmpty())
        assertFalse(called)
    }

    @Test fun realFailureDegradesToNoRowAndIsReported() {
        var reported: Throwable? = null
        val ids = settingsSurplusProfileIds(
            isParentProfile = true,
            lookup = { throw IllegalStateException("boom") },
            onFailure = { reported = it },
        )
        assertTrue(ids.isEmpty())
        assertTrue(reported is IllegalStateException)
    }
}

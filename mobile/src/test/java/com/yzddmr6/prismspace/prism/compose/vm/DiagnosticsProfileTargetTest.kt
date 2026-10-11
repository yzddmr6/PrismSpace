package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.space.ProfileOwnership
import com.yzddmr6.prismspace.space.SpaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsProfileTargetTest {

    /** TCL T951K: the classified primary (10) wins over a stale Users.profile (12). */
    @Test fun classifiedStateUserIdIsTheTarget() {
        assertEquals(10, selectDiagnosticsProfile(SpaceState.Healthy(10), fallback = 12))
        assertEquals(10, selectDiagnosticsProfile(SpaceState.Inactive(10), fallback = null))
        assertEquals(11, selectDiagnosticsProfile(SpaceState.HalfProvisioned(11, resumable = false), fallback = null))
    }

    @Test fun noOwnProfileMeansNoTarget() {
        assertEquals(null, selectDiagnosticsProfile(SpaceState.NoProfile, fallback = 12))
        assertEquals(null, selectDiagnosticsProfile(SpaceState.ForeignProfile(999), fallback = 12))
    }

    @Test fun fallbackOnlyBeforeTheFirstSnapshot() {
        assertEquals(12, selectDiagnosticsProfile(null, fallback = 12))
        assertEquals(null, selectDiagnosticsProfile(null, fallback = null))
    }

    @Test fun profilesSectionListsThePartitionAndFacts() {
        val body = profilesSectionBody(
            ProfileOwnership(10, listOf(10), listOf(11, 12), listOf(999), listOf(10)),
            SpaceState.Healthy(10),
            "reason=x facts",
            targetId = 10,
        )
        assertTrue(body.contains("primary=10 owned=[10] surplus=[11, 12] foreign=[999]"))
        assertTrue(body.contains("Diagnostics target: user=10"))
        assertTrue(body.contains("Last classification: reason=x facts"))
    }

    @Test fun profilesSectionIsPresentWithoutAnyProfile() {
        val body = profilesSectionBody(ProfileOwnership.EMPTY, SpaceState.NoProfile, "reason=y", targetId = null)
        assertTrue(body.contains("primary=- owned=[] surplus=[] foreign=[]"))
        assertTrue(body.contains("Diagnostics target: -"))
        assertTrue(body.contains("Last classification: reason=y"))
    }
}

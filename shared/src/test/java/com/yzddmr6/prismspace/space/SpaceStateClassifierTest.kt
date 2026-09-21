package com.yzddmr6.prismspace.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceStateClassifierTest {
    private fun profile(
        userId: Int = 22,
        pkg: Boolean = true,
        marker: Boolean = true,
        provisioning: Boolean = false,
        running: Boolean = true,
        unlocked: Boolean = true,
        quiet: Boolean = false,
        bridge: Boolean? = true,
        owner: Boolean? = true,
        ownerPackage: String? = null,
    ) = SpaceProfileFacts(userId, pkg, marker, provisioning, running, unlocked, quiet, bridge,
        profileOwner = owner, profileOwnerPackage = ownerPackage)

    private fun classify(facts: SpaceFacts) = SpaceStateClassifier.classify(facts, PRISM_PACKAGE)

    @Test fun noProfile() = assertEquals(SpaceState.NoProfile, classify(SpaceFacts(emptyList())))

    @Test fun orphanRequiresOwnershipEvidence_profileOwnerPackage() = assertEquals(
        SpaceState.OrphanProfile(22),
        classify(SpaceFacts(listOf(profile(pkg = false, marker = false, ownerPackage = PRISM_PACKAGE)))),
    )

    /** A profile created by the root flow carries our package, so a genuine leftover keeps both
     *  package and owner evidence; without any of them it must degrade to foreign. */
    @Test fun rootLeftoverWithoutAnyEvidenceIsForeign() = assertEquals(
        SpaceState.ForeignProfile(13, null),
        classify(SpaceFacts(listOf(profile(userId = 13, pkg = false, marker = false)))),
    )

    @Test fun miuiXSpaceIsForeignNotOrphan() = assertEquals(
        SpaceState.ForeignProfile(999, null),
        classify(SpaceFacts(listOf(profile(userId = 999, pkg = false, marker = false)))),
    )

    @Test fun otherDpcProfileIsForeignWithItsOwnerPackage() = assertEquals(
        SpaceState.ForeignProfile(10, "com.other.dpc"),
        classify(SpaceFacts(listOf(
            profile(userId = 10, pkg = false, marker = false, ownerPackage = "com.other.dpc"),
        ))),
    )

    @Test fun foreignNeverOutranksPrismEvidence() {
        val xspace999 = profile(userId = 999, pkg = false, marker = false)
        val orphan22 = profile(userId = 22, pkg = false, marker = false, ownerPackage = PRISM_PACKAGE)
        assertEquals(SpaceState.OrphanProfile(22), classify(SpaceFacts(listOf(xspace999, orphan22))))
        assertEquals(SpaceState.ForeignProfile(999, null), classify(SpaceFacts(listOf(xspace999))))
    }

    @Test fun provisioningPrecedesIncompleteMarker() = assertEquals(
        SpaceState.Provisioning(22),
        classify(SpaceFacts(listOf(profile(marker = false, provisioning = true)))),
    )

    @Test fun halfProvisionedIsPreciselyResumableOnlyWithBridgeAndOwnership() {
        assertEquals(SpaceState.HalfProvisioned(22, true), classify(
            SpaceFacts(listOf(profile(marker = false, bridge = true, owner = true)))))
        assertEquals(SpaceState.HalfProvisioned(22, false), classify(
            SpaceFacts(listOf(profile(marker = false, bridge = false, owner = true)))))
        assertEquals(SpaceState.HalfProvisioned(22, false), classify(
            SpaceFacts(listOf(profile(marker = false, bridge = true, owner = false)))))
    }

    @Test fun quietModePrecedesItsDerivedStoppedAndLockedFacts() = assertEquals(
        SpaceState.Inactive(22),
        classify(SpaceFacts(listOf(profile(running = false, unlocked = false, quiet = true)))),
    )

    @Test fun unlockedFalseWhileRunningIsLocked() = assertEquals(
        SpaceState.Locked(22), classify(SpaceFacts(listOf(profile(unlocked = false)))),
    )

    @Test fun quietOrStoppedIsInactive() {
        assertEquals(SpaceState.Inactive(22), classify(SpaceFacts(listOf(profile(quiet = true)))))
        assertEquals(SpaceState.Inactive(22), classify(SpaceFacts(listOf(profile(running = false)))))
    }

    @Test fun missingAndFailedBridgeAreExplicit() {
        assertEquals(SpaceState.BridgeDown(22, SpaceBridgeCause.NotChecked), classify(
            SpaceFacts(listOf(profile(bridge = null)))))
        assertEquals(SpaceState.BridgeDown(22, SpaceBridgeCause.TimedOut), classify(
            SpaceFacts(listOf(profile(bridge = false).copy(bridgeCause = SpaceBridgeCause.TimedOut)))))
    }

    @Test fun healthy() = assertEquals(
        SpaceState.Healthy(22), classify(SpaceFacts(listOf(profile()))),
    )

    @Test fun multipleProfilesUseEvidenceThenStableIdNotEnumerationOrder() {
        val orphan999 = profile(userId = 999, pkg = false, marker = false, ownerPackage = PRISM_PACKAGE)
        val half100 = profile(userId = 100, marker = false)
        val managed22 = profile(userId = 22)
        assertEquals(SpaceState.Healthy(22), classify(SpaceFacts(listOf(orphan999, half100, managed22))))
        assertEquals(SpaceState.HalfProvisioned(100, true), classify(SpaceFacts(listOf(orphan999, half100))))
    }

    @Test fun specialUserIdsAreDataNotSentinels() {
        assertEquals(SpaceState.Healthy(999), classify(SpaceFacts(listOf(profile(userId = 999)))))
        assertEquals(SpaceState.Healthy(100), classify(SpaceFacts(listOf(profile(userId = 100)))))
    }

    @Test fun ownProfileAbsentCoversNothingAndForeignOnly() {
        assertTrue(SpaceStateClassifier.ownProfileAbsent(SpaceState.NoProfile))
        assertTrue(SpaceStateClassifier.ownProfileAbsent(SpaceState.ForeignProfile(999)))
        assertTrue(SpaceStateClassifier.ownProfileAbsent(SpaceState.ForeignProfile(999, "com.oplus.appplatform")))
        // Unknown facts are not an absence claim, and every owned state must block creation.
        assertFalse(SpaceStateClassifier.ownProfileAbsent(null))
        assertFalse(SpaceStateClassifier.ownProfileAbsent(SpaceState.OrphanProfile(22)))
        assertFalse(SpaceStateClassifier.ownProfileAbsent(SpaceState.Healthy(22)))
        assertFalse(SpaceStateClassifier.ownProfileAbsent(SpaceState.HalfProvisioned(22, resumable = true)))
    }

    private companion object {
        const val PRISM_PACKAGE = "com.yzddmr6.prismspace"
    }
}

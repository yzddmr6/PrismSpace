package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceProfileFacts
import com.yzddmr6.prismspace.space.SpaceStateClassifier
import com.yzddmr6.prismspace.space.VendorProfileType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthProbeGateTest {

    @Test fun availableOwnProfileIsProbed() =
        assertTrue(shouldProbeBridgeHealth(foreign = false, packagePresent = true, running = true, unlocked = true, quiet = false))

    /** Device evidence: PrismSpace installed in the CLONE profile 999 got a PermissionDenied probe. */
    @Test fun foreignProfileIsNeverProbedEvenWithOurPackage() {
        val clone999 = SpaceProfileFacts(999, prismPackagePresent = true, ownershipMarkerPresent = false,
            vendorProfileType = VendorProfileType.Clone)
        val foreign = SpaceStateClassifier.isForeign(clone999, PRISM_PACKAGE)
        assertTrue(foreign)
        assertFalse(shouldProbeBridgeHealth(foreign, packagePresent = true, running = true, unlocked = true, quiet = false))
    }

    @Test fun unavailableOrPackagelessProfileIsNotProbed() {
        assertFalse(shouldProbeBridgeHealth(foreign = false, packagePresent = false, running = true, unlocked = true, quiet = false))
        assertFalse(shouldProbeBridgeHealth(foreign = false, packagePresent = true, running = false, unlocked = true, quiet = false))
        assertFalse(shouldProbeBridgeHealth(foreign = false, packagePresent = true, running = true, unlocked = false, quiet = false))
        assertFalse(shouldProbeBridgeHealth(foreign = false, packagePresent = true, running = true, unlocked = true, quiet = true))
    }

    private companion object {
        const val PRISM_PACKAGE = "com.yzddmr6.prismspace"
    }
}

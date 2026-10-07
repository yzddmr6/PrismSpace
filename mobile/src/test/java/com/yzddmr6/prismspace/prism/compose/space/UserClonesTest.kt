package com.yzddmr6.prismspace.prism.compose.space

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserClonesTest {

    @Test fun thirdPartyPackagesAreAlwaysUserClones() {
        assertTrue(isUserClone(isSystem = false, policyEnabled = false))
        assertTrue(isUserClone(isSystem = false, policyEnabled = true))
    }

    @Test fun systemPackagesCountOnlyWhenThePolicyEnablesThemByChoice() {
        assertTrue(isUserClone(isSystem = true, policyEnabled = true))
        assertFalse(isUserClone(isSystem = true, policyEnabled = false))
    }
}

package com.yzddmr6.prismspace.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyClonePrefsTest {

    @Test fun absentKeyPurgesNothing() {
        assertNull(LegacyClonePrefs.registryPurgeCount(present = false, stored = null))
        assertNull(LegacyClonePrefs.registryPurgeCount(present = false, stored = setOf("a")))
    }

    @Test fun presentKeyReportsItsEntryCount() {
        assertEquals(2, LegacyClonePrefs.registryPurgeCount(present = true, stored = setOf("a", "b")))
        assertEquals(0, LegacyClonePrefs.registryPurgeCount(present = true, stored = emptySet()))
    }

    @Test fun presentKeyOfAnotherTypeIsStillPurged() {
        assertEquals(0, LegacyClonePrefs.registryPurgeCount(present = true, stored = null))
    }

    @Test fun retiredKeyNameIsTheRegistryKey() {
        assertEquals("prism_user_cloned_pkgs", LegacyClonePrefs.KEY)
    }
}

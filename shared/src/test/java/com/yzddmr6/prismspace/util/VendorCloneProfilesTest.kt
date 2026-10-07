package com.yzddmr6.prismspace.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorCloneProfilesTest {

    private val full = "android.os.usertype.full.SYSTEM"
    private val managed = "android.os.usertype.profile.MANAGED"
    private val clone = VendorCloneProfiles.USER_TYPE_PROFILE_CLONE

    @Test fun belowApi35TheTypeIsUnknownEvenWithACloneProfile() {
        assertEquals(CloneProfilePresence.Unknown, VendorCloneProfiles.presence(34, listOf(full, clone)))
        assertFalse(VendorCloneProfiles.hasVendorCloneProfile(34, listOf(full, clone)))
    }

    @Test fun aCloneProfileIsPresentFromApi35() {
        assertEquals(CloneProfilePresence.Present, VendorCloneProfiles.presence(35, listOf(full, managed, clone)))
        assertEquals(CloneProfilePresence.Present, VendorCloneProfiles.presence(36, listOf(full, clone)))
    }

    @Test fun anIdentifiedCloneWinsOverAnUnreadableProfile() {
        assertEquals(CloneProfilePresence.Present, VendorCloneProfiles.presence(36, listOf(full, null, clone)))
    }

    @Test fun onlyFullAndManagedProfilesMeansAbsent() {
        assertEquals(CloneProfilePresence.Absent, VendorCloneProfiles.presence(36, listOf(full, managed)))
        assertEquals(CloneProfilePresence.Absent, VendorCloneProfiles.presence(36, emptyList()))
    }

    @Test fun anUnreadableProfileWithoutACloneIsUnknown() {
        assertEquals(CloneProfilePresence.Unknown, VendorCloneProfiles.presence(36, listOf(full, null)))
    }

    @Test fun theNoticeShowsOnlyForPresent() {
        assertTrue(VendorCloneProfiles.hasVendorCloneProfile(36, listOf(full, clone)))
        assertFalse(VendorCloneProfiles.hasVendorCloneProfile(36, listOf(full, managed)))
        assertFalse(VendorCloneProfiles.hasVendorCloneProfile(36, listOf(full, null)))
        assertEquals("android.os.usertype.profile.CLONE", VendorCloneProfiles.USER_TYPE_PROFILE_CLONE)
    }
}

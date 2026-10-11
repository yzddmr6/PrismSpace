package com.yzddmr6.prismspace.settings

import com.yzddmr6.prismspace.util.Modules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrismSettingsActivityEnablerTest {

    @Test fun enablerAcceptsUserInitializeBecauseManifestRegistersIt() {
        val source = File("src/main/java/com/yzddmr6/prismspace/settings/PrismSettingsActivity.kt").readText()

        assertTrue(source.contains("Intent.ACTION_USER_INITIALIZE"))
        assertTrue(source.contains("intent.action != Intent.ACTION_USER_INITIALIZE"))
    }

    /** Shared code reads the ownership marker by name and must never depend on the mobile class. */
    @Test fun sharedOwnershipMarkerNameMatchesThisActivity() {
        assertEquals(PrismSettingsActivity::class.java.name, Modules.ACTIVITY_PROFILE_SETTINGS_ENTRY)
    }
}

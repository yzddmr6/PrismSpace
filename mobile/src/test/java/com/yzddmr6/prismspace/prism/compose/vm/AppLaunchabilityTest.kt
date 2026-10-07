package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability.Launchable
import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability.NoLauncherEntry
import com.yzddmr6.prismspace.prism.compose.vm.AppLaunchability.Paused
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchabilityTest {

    @Test fun decisionTable() {
        data class Row(val entry: Boolean?, val hidden: Boolean, val suspended: Boolean, val expected: AppLaunchability)
        val table = listOf(
            Row(true, false, false, Launchable),
            Row(true, true, false, Paused),
            Row(true, false, true, Paused),
            Row(true, true, true, Paused),
            Row(false, false, false, NoLauncherEntry),
            Row(false, true, false, NoLauncherEntry),       // Never thaw a package that cannot be opened.
            Row(false, false, true, NoLauncherEntry),
            Row(false, true, true, NoLauncherEntry),
            Row(null, false, false, NoLauncherEntry),
            Row(null, true, false, Paused),                 // Unknown entry: resume, then re-evaluate.
            Row(null, false, true, Paused),
            Row(null, true, true, Paused),
        )
        table.forEach { row ->
            assertEquals(row.toString(), row.expected, resolveLaunchability(row.entry, row.hidden, row.suspended))
        }
    }

    @Test fun selfDisabledEntryIsReflectedAfterRefresh() {
        // An app that disables its own launcher alias reads as not openable; re-enabling restores Open.
        assertEquals(NoLauncherEntry, resolveLaunchability(hasEnabledLauncherEntry = false, hidden = false, suspended = false))
        assertEquals(Launchable, resolveLaunchability(hasEnabledLauncherEntry = true, hidden = false, suspended = false))
    }
}

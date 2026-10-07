package com.yzddmr6.prismspace.settings.profile

import com.yzddmr6.prismspace.prism.transfer.TransferKind
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePendingInstallsTest {
    private fun record(pkg: String?, time: Long = 0) = TransferLedgerRecord(
        id = "id-$pkg-$time",
        displayName = "App",
        mime = null,
        sizeBytes = null,
        contentUri = null,
        relativePath = "Download/PrismSpace",
        direction = null,
        role = TransferRole.Received,
        kind = if (pkg != null) TransferKind.ApkSuite else TransferKind.File,
        packageName = pkg,
        apkUris = emptyList(),
        timeMillis = time,
        legacy = false,
    )

    @Test fun repeatedPreparationIsOneTaskWithLatestLabel() {
        val latest = record("app", 2).copy(displayName = "New name")
        assertEquals(listOf(latest), pendingProfileInstalls(listOf(record("app", 1), latest), setOf("app"), { false }, { true }))
    }

    @Test fun installedAppsAndPlainFilesAreNotPendingEvenWhenApksRemain() {
        val pending = record("pending")
        assertEquals(listOf(pending), pendingProfileInstalls(
            listOf(record("installed"), record(null), pending), setOf("installed", "pending"), { it == "installed" }, { true }))
    }

    @Test fun missingApksAreNotPresentedAsReadyToInstall() {
        assertTrue(pendingProfileInstalls(listOf(record("app")), setOf("app"), { false }, { false }).isEmpty())
    }

    @Test fun uninstalledCompletedCloneDoesNotResurrectFromTransferHistory() {
        assertTrue(pendingProfileInstalls(listOf(record("completed")), emptySet(), { false }, { true }).isEmpty())
    }

    @Test fun pendingTaskSurvivesTrimmedTransferHistory() {
        val tasks = pendingProfileInstalls(emptyList(), setOf("older.app"), { false }, { true })
        assertEquals("older.app", tasks.single().packageName)
        assertEquals(TransferKind.ApkSuite, tasks.single().kind)
    }

    @Test fun hiddenSuiteRowsStillCarryTheirTask() {
        // "Clear" hides suite rows; the pending install must still find its label and URIs.
        val hidden = record("app", 3).copy(hidden = true, displayName = "Kept")
        assertEquals("Kept", pendingProfileInstalls(listOf(hidden), setOf("app"), { false }, { true }).single().displayName)
    }
}

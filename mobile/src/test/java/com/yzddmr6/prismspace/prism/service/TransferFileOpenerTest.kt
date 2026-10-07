package com.yzddmr6.prismspace.prism.service

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yzddmr6.prismspace.prism.transfer.TransferKind
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferRole
import com.yzddmr6.prismspace.prism.transfer.displayTitle

class TransferFileOpenerTest {

    @Test fun fileManagerLaunchKeepsDownloadsPrimaryAndAddsXiaomiFileManager() {
        val spec = SystemFileManagerLaunchPlanner.launchSpec()

        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, spec.primary.action)
        assertTrue(
            spec.initialIntents.any {
                it.action == SystemFileManagerLaunchPlanner.ACTION_XIAOMI_FILE_MANAGER_HOME &&
                    it.packageName == InstallSourcePermissionHelper.SYSTEM_FILE_MANAGER_PACKAGE
            },
        )
    }

    @Test fun apkSuiteLedgerRowsTitleAsLabelAndPackage() {
        assertEquals("Via-mark.via", ledgerRecord(name = "Via", packageName = "mark.via", kind = TransferKind.ApkSuite).displayTitle())
    }

    @Test fun plainLedgerRowsDisplayByName() {
        assertEquals("report.pdf", ledgerRecord(name = "report.pdf", packageName = null, kind = TransferKind.File).displayTitle())
    }

    private fun ledgerRecord(name: String, packageName: String?, kind: TransferKind) = TransferLedgerRecord(
        id = "id",
        displayName = name,
        mime = null,
        sizeBytes = null,
        contentUri = null,
        relativePath = "Download/PrismSpace",
        direction = null,
        role = TransferRole.Received,
        kind = kind,
        packageName = packageName,
        apkUris = emptyList(),
        timeMillis = 1L,
        legacy = true,
    )

    @Test fun crossProfileForwarderIsNotAFileSurface() {
        assertFalse(isSystemFileSurfacePackage("android"))
        assertTrue(isSystemFileSurfacePackage("com.android.fileexplorer"))
        assertTrue(isSystemFileSurfacePackage("com.google.android.documentsui"))
    }

    @Test fun everyResolvedPickerRouteMustHaveAReadySurface() {
        val routes = listOf(
            setOf("com.google.android.documentsui"),
            setOf("com.android.fileexplorer"),
        )

        assertFalse(routesHaveUsableSurface(routes, setOf("com.google.android.documentsui")))
        assertTrue(
            routesHaveUsableSurface(
                routes,
                setOf("com.google.android.documentsui", "com.android.fileexplorer"),
            ),
        )
    }
}

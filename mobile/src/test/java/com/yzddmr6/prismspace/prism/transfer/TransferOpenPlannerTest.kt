package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferOpenPlannerTest {

    private val forwarder = HandlerRef("android", "com.android.internal.app.IntentForwarderActivity")
    private val documentsUi = HandlerRef("com.google.android.documentsui", "com.android.documentsui.files.FilesActivity")
    private val aospDocumentsUi = HandlerRef("com.android.documentsui", "com.android.documentsui.files.FilesActivity")
    private val xiaomi = HandlerRef("com.android.fileexplorer", "com.android.fileexplorer.FileActivity")
    private val gallery = HandlerRef("com.miui.gallery", "com.miui.gallery.activity.ExternalPhotoPageActivity")
    private val photos = HandlerRef("com.google.android.apps.photos", "com.google.android.apps.photos.Viewer")

    @Test fun folderDocumentIdIsThePrimaryVolumePath() {
        assertEquals("primary:Download/PrismSpace", TransferOpenPlanner.folderDocumentId("Download/PrismSpace/"))
        assertEquals("primary:Pictures/PrismSpace", TransferOpenPlanner.folderDocumentId("Pictures/PrismSpace"))
    }

    @Test fun imagesAndOtherFilesHaveTheirOwnFolders() {
        assertEquals("Pictures/PrismSpace", TransferOpenPlanner.folderFor("image/jpeg"))
        assertEquals("Download/PrismSpace", TransferOpenPlanner.folderFor("application/pdf"))
        assertEquals("Download/PrismSpace", TransferOpenPlanner.folderFor(null))
    }

    @Test fun folderSurfacesPreferDocumentsUiThenVendorAndNeverTheForwarder() {
        val surfaces = TransferOpenPlanner.folderSurfaces(listOf(forwarder, xiaomi, aospDocumentsUi, documentsUi))

        assertEquals(listOf(documentsUi, aospDocumentsUi, xiaomi), surfaces.map { it.handler })
        assertEquals(
            listOf(FolderSurfaceKind.DocumentsUi, FolderSurfaceKind.DocumentsUi, FolderSurfaceKind.Vendor),
            surfaces.map { it.kind },
        )
        assertEquals(emptyList<FolderSurface>(), TransferOpenPlanner.folderSurfaces(listOf(forwarder)))
    }

    @Test fun viewerSelectionExcludesTheCrossProfileForwarder() {
        assertEquals(ViewerChoice.NoViewer, TransferOpenPlanner.selectViewer(emptyList()))
        assertEquals(ViewerChoice.NoViewer, TransferOpenPlanner.selectViewer(listOf(forwarder)))
        assertEquals(ViewerChoice.Single(gallery), TransferOpenPlanner.selectViewer(listOf(forwarder, gallery)))
        assertEquals(ViewerChoice.Chooser(listOf(forwarder)), TransferOpenPlanner.selectViewer(listOf(gallery, forwarder, photos)))
        assertEquals(ViewerChoice.Chooser(emptyList()), TransferOpenPlanner.selectViewer(listOf(gallery, photos)))
    }

    @Test fun receivedRowsOpenLocally() {
        assertEquals(OpenRoute.Local, TransferOpenPlanner.planOpenRoute(TransferRole.Received, 0, 36, true, SpaceActionGate(false, "x")))
    }

    @Test fun sentRowsUsePlatformCrossProfileStartWhenAllowed() {
        val usable = SpaceActionGate(true, null)
        assertEquals(OpenRoute.CrossProfileStart(23), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, 30, true, usable))
        assertEquals(OpenRoute.QueuedEntry(23), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, 30, false, usable))
        assertEquals(OpenRoute.QueuedEntry(23), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, 29, true, usable))
        // Inside the dual space no gate is computable; the main space is necessarily running.
        assertEquals(OpenRoute.CrossProfileStart(0), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 0, 36, true, null))
    }

    @Test fun closedGateBlocksWithItsGuidance() {
        assertEquals(
            OpenRoute.Blocked("resume first"),
            TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, 36, true, SpaceActionGate(false, "resume first")),
        )
    }

    @Test fun requestSurvivesTheBridgeRoundTrip() {
        val request = TransferOpenRequest("id", OpenMode.File, "content://media/1", "image/png", "Pictures/PrismSpace")
        assertEquals(request, request.toDto().toOpenRequest())
    }
}

package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.bridge.BridgeInspectResult
import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(OpenRoute.Local, TransferOpenPlanner.planOpenRoute(TransferRole.Received, 0, SpaceActionGate(false, "x")))
        assertEquals(OpenRoute.Local, TransferOpenPlanner.planOpenRoute(TransferRole.Received, null, null))
    }

    @Test fun sentRowsAreForwardedToTheOwner() {
        assertEquals(OpenRoute.Forwarded(23), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, SpaceActionGate(true, null)))
        // Inside the dual space no gate is computable; the main space is necessarily running.
        assertEquals(OpenRoute.Forwarded(0), TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 0, null))
    }

    @Test fun closedGateBlocksWithItsGuidance() {
        assertEquals(
            OpenRoute.Blocked("resume first"),
            TransferOpenPlanner.planOpenRoute(TransferRole.Sent, 23, SpaceActionGate(false, "resume first")),
        )
    }

    @Test fun unknownOwnerIsBlocked() {
        assertTrue(TransferOpenPlanner.planOpenRoute(TransferRole.Sent, null, SpaceActionGate(true, null)) is OpenRoute.Blocked)
        assertTrue(TransferOpenPlanner.planOpenRoute(TransferRole.Sent, null, null) is OpenRoute.Blocked)
    }

    // ── Share ──

    private val self = HandlerRef("com.yzddmr6.prismspace", "com.yzddmr6.prismspace.prism.ui.ImportToSpaceActivity")
    private val toManaged = HandlerRef("android", "com.android.internal.app.ForwardIntentToManagedProfile")
    private val messenger = HandlerRef("org.telegram.messenger", "org.telegram.ui.LaunchActivity")
    private val png = ShareItem("content://media/external/images/media/1", "image/png")
    private val pdf = ShareItem("content://media/external/downloads/2", null)

    @Test fun shareFromTheOwningUserIsLocalEvenWhenTheGateIsClosed() {
        // Vendor share proxy: the receiver ran in the target user, so the files already live here.
        assertEquals(OpenRoute.Local, TransferOpenPlanner.planShareRoute(0, 0, SpaceActionGate(false, "x")))
        assertEquals(OpenRoute.Local, TransferOpenPlanner.planShareRoute(24, 24, null))
    }

    @Test fun shareToTheOtherUserRoutesLikeOpeningASentRow() {
        val usable = SpaceActionGate(true, null)
        val closed = SpaceActionGate(false, "resume first")
        val cases = listOf<Pair<Int?, SpaceActionGate?>>(
            24 to usable,
            24 to closed,
            null to usable,
            null to null,
            0 to null,
        )
        cases.forEach { (owner, gate) ->
            assertEquals(
                TransferOpenPlanner.planOpenRoute(TransferRole.Sent, owner, gate),
                TransferOpenPlanner.planShareRoute(owner, 10, gate),
            )
        }
        assertEquals(OpenRoute.Forwarded(24), TransferOpenPlanner.planShareRoute(24, 0, usable))
        assertEquals(OpenRoute.Forwarded(0), TransferOpenPlanner.planShareRoute(0, 24, null))
        assertEquals(OpenRoute.Blocked("resume first"), TransferOpenPlanner.planShareRoute(24, 0, closed))
        assertTrue(TransferOpenPlanner.planShareRoute(null, 0, usable) is OpenRoute.Blocked)
    }

    @Test fun oneItemIsSendAndMoreAreSendMultipleInRequestOrder() {
        val single = TransferOpenPlanner.planShareIntent(listOf(png), listOf("image/png"), listOf(gallery, messenger), emptyList())
        assertEquals(
            SharePlan.Ready(TransferOpenPlanner.ACTION_SEND, "image/png", listOf(png.contentUri), emptyList()),
            single,
        )
        val multi = TransferOpenPlanner.planShareIntent(
            listOf(pdf, png),
            listOf("application/pdf", "image/png"),
            listOf(messenger),
            emptyList(),
        ) as SharePlan.Ready
        assertEquals(TransferOpenPlanner.ACTION_SEND_MULTIPLE, multi.action)
        assertEquals("*/*", multi.type)
        assertEquals(listOf(pdf.contentUri, png.contentUri), multi.uris)
    }

    @Test fun shareChooserExcludesExactlyTheForwarderAndPrismSpaceItself() {
        val plan = TransferOpenPlanner.planShareIntent(
            listOf(png),
            listOf("image/png"),
            listOf(forwarder, self, messenger, toManaged, gallery, self),
            listOf(self),
        ) as SharePlan.Ready

        assertEquals(setOf(forwarder, toManaged, self), plan.excluded.toSet())
        assertEquals(plan.excluded.size, plan.excluded.distinct().size)
        assertFalse(messenger in plan.excluded)
        assertFalse(gallery in plan.excluded)
    }

    @Test fun onlyForwarderAndSelfMeansNoTargetAndNoItemsIsItsOwnCase() {
        assertEquals(
            SharePlan.NoTarget,
            TransferOpenPlanner.planShareIntent(listOf(png), listOf("image/png"), listOf(forwarder, self), listOf(self)),
        )
        assertEquals(SharePlan.NoTarget, TransferOpenPlanner.planShareIntent(listOf(png), listOf("image/png"), emptyList(), emptyList()))
        assertEquals(SharePlan.NoItems, TransferOpenPlanner.planShareIntent(emptyList(), emptyList(), listOf(messenger), emptyList()))
    }

    @Test fun shareMimeTypeCollapsesToTheNarrowestCommonType() {
        assertEquals("image/png", TransferOpenPlanner.shareMimeType(listOf("image/png", "image/png")))
        assertEquals("image/png", TransferOpenPlanner.shareMimeType(listOf("image/png", "IMAGE/PNG")))
        assertEquals("image/*", TransferOpenPlanner.shareMimeType(listOf("image/png", "image/jpeg")))
        assertEquals("image/*", TransferOpenPlanner.shareMimeType(listOf("image/png", "Image/JPEG")))
        assertEquals("*/*", TransferOpenPlanner.shareMimeType(listOf("image/png", "application/pdf")))
        assertEquals("application/pdf", TransferOpenPlanner.shareMimeType(listOf("application/pdf")))
        assertEquals("*/*", TransferOpenPlanner.shareMimeType(emptyList()))
    }

    @Test fun keepPresentDropsMissingKeepsTheRestAndDistrustsBridgeFailures() {
        val third = ShareItem("content://media/3", "image/jpeg")
        assertEquals(
            ShareFilter.Kept(listOf(png, third), 1),
            TransferOpenPlanner.keepPresent(
                listOf(png, pdf, third),
                listOf(BridgeInspectResult.Exists, BridgeInspectResult.Missing, BridgeInspectResult.NoViewer),
            ),
        )
        assertEquals(
            ShareFilter.BridgeFailed,
            TransferOpenPlanner.keepPresent(listOf(png, pdf), listOf(BridgeInspectResult.Exists, null)),
        )
        assertEquals(
            ShareFilter.Kept(emptyList(), 2),
            TransferOpenPlanner.keepPresent(listOf(png, pdf), listOf(BridgeInspectResult.Missing, BridgeInspectResult.Missing)),
        )
    }

    @Test fun keepPresentDropsItemsTheOwnerNoLongerRecords() {
        assertEquals(
            ShareFilter.Kept(listOf(pdf), 1),
            TransferOpenPlanner.keepPresent(listOf(png, pdf), listOf(BridgeInspectResult.Unrecorded, BridgeInspectResult.Exists)),
        )
    }
}

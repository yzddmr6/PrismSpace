package com.yzddmr6.prismspace.prism.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferOpenRequestsTest {

    private class MemorySlot : OpenRequestSlot {
        var fields: Map<String, String?>? = null
        override fun read() = fields
        override fun write(fields: Map<String, String?>?) { this.fields = fields }
    }

    private var now = 1_000L
    private val slot = MemorySlot()
    private val queue = TransferOpenRequestQueue(slot, { now })
    private val request = TransferOpenRequest("r1", OpenMode.File, "content://media/1", "application/pdf", "Download/PrismSpace")

    @Test fun freshRequestIsConsumedExactlyOnce() {
        queue.put(request)
        now += 5_000L

        assertEquals(TakenRequest.Fresh(request, 5_000L), queue.take())
        assertEquals(TakenRequest.Empty, queue.take())
    }

    @Test fun requestsOlderThanThirtySecondsAreDropped() {
        queue.put(request)
        now += 30_001L

        val taken = queue.take()
        assertTrue(taken is TakenRequest.Expired)
        assertEquals("r1", (taken as TakenRequest.Expired).recordId)
        assertEquals(TakenRequest.Empty, queue.take())
    }

    @Test fun newerRequestReplacesTheOlderOne() {
        queue.put(request)
        val newer = request.copy(recordId = "r2", mode = OpenMode.Folder, contentUri = null, mime = null)
        queue.put(newer)

        assertEquals(TakenRequest.Fresh(newer, 0L), queue.take())
    }

    @Test fun clockGoingBackwardsIsNotTrusted() {
        queue.put(request)
        now -= 1L

        assertTrue(queue.take() is TakenRequest.Expired)
    }

    private val share = TransferOpenRequest(
        "batch-1",
        OpenMode.Share,
        null,
        null,
        null,
        listOf(ShareItem("content://media/external/images/media/7", "image/png"), ShareItem("content://media/external/downloads/8", null)),
    )

    @Test fun shareRequestWithAnUnknownMimeSurvivesTheSlot() {
        queue.put(share)
        now += 200L

        assertEquals(TakenRequest.Fresh(share, 200L), queue.take())
    }

    @Test fun shareListsOfUnequalLengthOrEmptyAreExpired() {
        queue.put(share)
        slot.fields = slot.fields!!.toMutableMap().apply { put("share_mimes", "image/png") }
        assertTrue(queue.take() is TakenRequest.Expired)

        queue.put(share)
        slot.fields = slot.fields!!.toMutableMap().apply { remove("share_uris"); remove("share_mimes") }
        val taken = queue.take()
        assertTrue(taken is TakenRequest.Expired)
        assertEquals("batch-1", (taken as TakenRequest.Expired).recordId)

        queue.put(share.copy(shareItems = emptyList()))
        assertTrue(queue.take() is TakenRequest.Expired)
    }

    @Test fun shareRequestReplacesAPendingFolderRequest() {
        val folder = request.copy(recordId = "r0", mode = OpenMode.Folder)
        queue.put(folder)
        queue.put(share)

        assertEquals(TakenRequest.Fresh(share, 0L), queue.take())
        assertEquals(TakenRequest.Empty, queue.take())
    }

    @Test fun otherModesCarryNoShareItems() {
        queue.put(request)

        assertEquals(emptyList<ShareItem>(), (queue.take() as TakenRequest.Fresh).request.shareItems)
    }
}

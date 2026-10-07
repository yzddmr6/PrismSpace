package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedRequestsTest {

    private fun row(
        id: String,
        uri: String?,
        role: TransferRole = TransferRole.Received,
        kind: TransferKind = TransferKind.File,
        mime: String? = "image/png",
        rel: String? = "Pictures/PrismSpace",
    ) = TransferLedgerRecord(
        id = id, displayName = "name", mime = mime, sizeBytes = 1L, contentUri = uri, relativePath = rel,
        direction = TransferDirection.ToProfile, role = role, kind = kind, packageName = null,
        apkUris = emptyList(), timeMillis = 0L, legacy = false,
    )

    private val received = row("t1", "content://media/external/images/media/7")
    private val receivedPdf = row("t2", "content://media/external/downloads/9", mime = "application/pdf", rel = "Download/PrismSpace")
    private val ledger = listOf(received, receivedPdf)

    private fun raw(id: String? = "t1", mode: String? = "File", uri: String? = null, uris: List<String>? = null, mimes: List<String>? = null) =
        RawForwardedRequest(id, mode, uri, uris, mimes)

    @Test fun receivedFileWithMatchingUriIsAcceptedFromTheLedger() {
        val verdict = validateForwardedRequest(raw(uri = received.contentUri), ledger)
        assertEquals(
            ForwardVerdict.Accept(TransferOpenRequest("t1", OpenMode.File, received.contentUri, "image/png", "Pictures/PrismSpace")),
            verdict,
        )
    }

    @Test fun folderModeTakesMimeAndPathFromTheRecordNotTheExtras() {
        val verdict = validateForwardedRequest(raw(id = "t2", mode = "Folder"), ledger) as ForwardVerdict.Accept
        assertEquals("application/pdf", verdict.request.mime)
        assertEquals("Download/PrismSpace", verdict.request.relativePath)
        assertEquals(receivedPdf.contentUri, verdict.request.contentUri)
    }

    @Test fun unknownRecordIsIgnored() {
        assertEquals(ForwardVerdict.Ignore("unknown_record"), validateForwardedRequest(raw(id = "bogus"), ledger))
        assertEquals(ForwardVerdict.Ignore("unknown_record"), validateForwardedRequest(raw(), emptyList()))
    }

    @Test fun mismatchedUriIsIgnored() {
        assertEquals(
            ForwardVerdict.Ignore("uri_mismatch"),
            validateForwardedRequest(raw(uri = "content://media/external/file/1"), ledger),
        )
    }

    @Test fun sentRowsAndApkSuitesAreNotOpenedFromAForwardedRequest() {
        val sent = row("s1", "content://media/external/images/media/3", role = TransferRole.Sent)
        val suite = row("a1", null, kind = TransferKind.ApkSuite)
        assertEquals(ForwardVerdict.Ignore("unsupported_kind"), validateForwardedRequest(raw(id = "s1"), listOf(sent)))
        assertEquals(ForwardVerdict.Ignore("unsupported_kind"), validateForwardedRequest(raw(id = "a1", mode = "Folder"), listOf(suite)))
    }

    @Test fun missingOrUnknownModeAndBlankIdAreMalformed() {
        assertEquals(ForwardVerdict.Ignore("malformed"), validateForwardedRequest(raw(mode = null), ledger))
        assertEquals(ForwardVerdict.Ignore("malformed"), validateForwardedRequest(raw(mode = "Delete"), ledger))
        assertEquals(ForwardVerdict.Ignore("malformed"), validateForwardedRequest(raw(id = null), ledger))
        assertEquals(ForwardVerdict.Ignore("malformed"), validateForwardedRequest(raw(id = " "), ledger))
    }

    @Test fun shareWithOnlyKnownItemsIsAcceptedWithLedgerMimes() {
        val verdict = validateForwardedRequest(
            raw(id = "handoff", mode = "Share", uris = listOf(received.contentUri!!, receivedPdf.contentUri!!), mimes = listOf("text/plain", "")),
            ledger,
        )
        assertEquals(
            ForwardVerdict.Accept(TransferOpenRequest("handoff", OpenMode.Share, null, null, null, listOf(
                ShareItem(received.contentUri!!, "image/png"),
                ShareItem(receivedPdf.contentUri!!, "application/pdf"),
            ))),
            verdict,
        )
    }

    @Test fun shareWithAnyUnknownItemIsIgnoredAsAWhole() {
        assertEquals(
            ForwardVerdict.Ignore("share_item_unknown"),
            validateForwardedRequest(
                raw(id = "handoff", mode = "Share", uris = listOf(received.contentUri!!, "content://evil/1"), mimes = listOf("", "")),
                ledger,
            ),
        )
    }

    @Test fun shareWithoutItemsOrWithUnequalListsIsMalformed() {
        assertEquals(ForwardVerdict.Ignore("malformed"), validateForwardedRequest(raw(mode = "Share"), ledger))
        assertEquals(
            ForwardVerdict.Ignore("malformed"),
            validateForwardedRequest(raw(mode = "Share", uris = listOf(received.contentUri!!), mimes = emptyList()), ledger),
        )
    }

    @Test fun legacyRecordWithoutUriIsAcceptedById() {
        val legacy = row("old", null)
        assertEquals(
            ForwardVerdict.Accept(TransferOpenRequest("old", OpenMode.Folder, null, "image/png", "Pictures/PrismSpace")),
            validateForwardedRequest(raw(id = "old", mode = "Folder"), listOf(legacy)),
        )
    }

    @Test fun recordedHereOnlyForReceivedFileRows() {
        val sent = row("s1", "content://media/external/images/media/3", role = TransferRole.Sent)
        assertTrue(isRecordedHere(received.contentUri!!, ledger))
        assertFalse(isRecordedHere("content://media/external/images/media/3", listOf(sent)))
        assertFalse(isRecordedHere("content://media/external/images/media/8", ledger))
        assertFalse(isRecordedHere(received.contentUri!!, emptyList()))
    }
}

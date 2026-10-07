package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.bridge.BridgeFileStore
import com.yzddmr6.prismspace.bridge.BridgeTransferDirection
import com.yzddmr6.prismspace.bridge.BridgeTransferRole
import com.yzddmr6.prismspace.bridge.PublishedFileDto
import com.yzddmr6.prismspace.bridge.TransferLedgerDto
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.TransferCancellationSignal
import com.yzddmr6.prismspace.prism.service.TransferSource
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferExecutorTest {

    private val main = 0
    private val dual = 23

    /** One fake ledger per user, plus the MediaStore rows of the target. */
    private class FakeWorld(
        val current: Int,
        val failOpen: ProfileBridgeResult<PendingWrite>? = null,
        val failFinish: Boolean = false,
        val failRecordSent: Boolean = false,
        val unreadable: Set<String> = emptySet(),
        val onWrite: (String) -> Unit = {},
        /** What the owner reads back after publishing; null = the read-back failed. */
        val readBack: (TransferLedgerDto) -> Pair<String?, String?>? = { it.displayName to "${it.relativePath}/" },
    ) : TransferPorts {
        val ledgers = mutableMapOf<Int, MutableList<Pair<TransferLedgerDto, String>>>()
        val pendingRows = mutableSetOf<String>()
        val publishedRows = mutableSetOf<String>()
        val aborted = mutableListOf<String>()
        private var next = 0

        override fun openSession(targetUserId: Int, store: BridgeFileStore, name: String, mime: String, relativePath: String): ProfileBridgeResult<PendingWrite> {
            failOpen?.let { return it }
            val uri = "content://media/$targetUserId/${next++}"
            pendingRows += uri
            return ProfileBridgeResult.Value(PendingWrite(uri, ByteArrayOutputStream()))
        }

        override fun finish(targetUserId: Int, store: BridgeFileStore, uri: String, dto: TransferLedgerDto): ProfileBridgeResult<PublishedFileDto> {
            val actual = readBack(dto)
            val published = PublishedFileDto(uri, actual?.first, actual?.second)
            // The owner records "Received" with the same rule the executor applies to "Sent".
            val receivedRow = dto.withPublished(published)
            if (failFinish) {
                // Target published and recorded, but the answer never reached the caller.
                ledgers.getOrPut(targetUserId) { mutableListOf() } += receivedRow to uri
                return ProfileBridgeResult.TimedOut
            }
            pendingRows -= uri
            publishedRows += uri
            ledgers.getOrPut(targetUserId) { mutableListOf() } += receivedRow to uri
            return ProfileBridgeResult.Value(published)
        }

        override fun abort(targetUserId: Int, store: BridgeFileStore, uri: String, transferId: String) {
            aborted += transferId
            pendingRows -= uri
            publishedRows -= uri
            ledgers[targetUserId]?.removeAll { it.first.transferId == transferId }
        }

        override fun source(item: TransferItem): TransferSource = TransferSource.testing(item.source.displayName, item.source.mime, item.source.declaredSize) {
            if (item.transferId in unreadable) throw IOException("gone")
            onWrite(item.transferId)
            ByteArrayInputStream(ByteArray(200_000) { 1 })
        }

        override fun recordSent(sourceUserId: Int, dto: TransferLedgerDto, contentUri: String): Boolean {
            if (failRecordSent) return false
            ledgers.getOrPut(sourceUserId) { mutableListOf() } += dto to contentUri
            return true
        }
    }

    private class RecordingLog : TransferLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String, error: Throwable?) { lines += message }
    }

    private fun item(id: String, mime: String = "application/pdf") =
        TransferItem(id, SourceResolution.Resolved(0, SourceUserRule.CurrentUser, "content://p/$id", "$id.pdf", mime, 200_000L))

    private fun request(source: Int, target: Int?, role: SpaceRole, vararg ids: String) = TransferRequest(
        batchId = "batch",
        entry = TransferEntry.FilesPage,
        destination = TransferDestination.OtherSpace(source, target, role),
        items = ids.map { item(it) },
        skipped = emptyList(),
    )

    @Test fun receivedIsWrittenInTheTargetAndSentInTheSourceWhenTheSourceRunsTheExecutor() {
        val world = FakeWorld(current = main)
        val outcomes = TransferExecutor(world, RecordingLog()).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal())

        val sent = outcomes.single() as TransferOutcome.Sent
        assertEquals(200_000L, sent.bytes)
        val received = world.ledgers.getValue(dual).single()
        val sentRow = world.ledgers.getValue(main).single()
        assertEquals(BridgeTransferRole.Received, received.first.role)
        assertEquals(BridgeTransferRole.Sent, sentRow.first.role)
        assertEquals("t1", received.first.transferId)
        assertEquals("t1", sentRow.first.transferId)
        assertEquals(sent.publishedUri, sentRow.second)
        assertEquals(BridgeTransferDirection.ToProfile, received.first.direction)
        assertEquals(200_000L, received.first.sizeBytes)
        assertEquals("Download/PrismSpace", received.first.relativePath)
    }

    @Test fun sameRowsWhenAVendorProxyStartedTheReceiverInTheTargetUser() {
        // Source in the dual space, receiver running in the main space (the target).
        val world = FakeWorld(current = main)
        TransferExecutor(world, RecordingLog()).run(request(dual, main, SpaceRole.Main, "t1"), TransferCancellationSignal())

        assertEquals(BridgeTransferRole.Received, world.ledgers.getValue(main).single().first.role)
        assertEquals(BridgeTransferRole.Sent, world.ledgers.getValue(dual).single().first.role)
        assertEquals(BridgeTransferDirection.ToMain, world.ledgers.getValue(main).single().first.direction)
    }

    @Test fun imagesGoToPictures() {
        val world = FakeWorld(current = main)
        val req = request(main, dual, SpaceRole.Dual).copy(items = listOf(item("img", "image/png")))
        TransferExecutor(world, RecordingLog()).run(req, TransferCancellationSignal())

        assertEquals("Pictures/PrismSpace", world.ledgers.getValue(dual).single().first.relativePath)
    }

    @Test fun finishFailureAbortsAndRemovesTheTargetRow() {
        val world = FakeWorld(current = main, failFinish = true)
        val outcome = TransferExecutor(world, RecordingLog()).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()

        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.TargetWriteFailed, SpaceRole.Dual), outcome)
        assertEquals(listOf("t1"), world.aborted)
        assertTrue(world.ledgers[dual].isNullOrEmpty())
        assertTrue(world.ledgers[main].isNullOrEmpty())
    }

    @Test fun cancellingDuringTheThirdOfFiveKeepsTheFirstTwoAndCleansTheThird() {
        val signal = TransferCancellationSignal()
        val world = FakeWorld(current = main, onWrite = { id -> if (id == "t3") signal.cancel() })
        val log = RecordingLog()

        val outcomes = TransferExecutor(world, log).run(request(main, dual, SpaceRole.Dual, "t1", "t2", "t3", "t4", "t5"), signal)

        assertTrue(outcomes[0] is TransferOutcome.Sent)
        assertTrue(outcomes[1] is TransferOutcome.Sent)
        assertEquals(TransferOutcome.Cancelled("t3", started = true), outcomes[2])
        assertEquals(TransferOutcome.Cancelled("t4", started = false), outcomes[3])
        assertEquals(TransferOutcome.Cancelled("t5", started = false), outcomes[4])
        assertEquals(listOf("t3"), world.aborted)
        assertTrue("no half-written row stays pending", world.pendingRows.isEmpty())
        assertEquals(2, world.ledgers.getValue(dual).size)
        assertEquals(2, world.ledgers.getValue(main).size)
    }

    @Test fun everyQueuedLineHasExactlyOneResultLine() {
        val signal = TransferCancellationSignal()
        val world = FakeWorld(current = main, unreadable = setOf("t2"), onWrite = { id -> if (id == "t3") signal.cancel() })
        val log = RecordingLog()

        TransferExecutor(world, log).run(request(main, dual, SpaceRole.Dual, "t1", "t2", "t3", "t4", "t5"), signal)

        val queued = log.lines.filter { it.startsWith("xfer.item.queued") }.map { it.substringAfter(" id=").substringBefore(' ') }
        val results = log.lines.filter { it.startsWith("xfer.item.result") }.map { it.substringAfter(" id=").substringBefore(' ') }
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), queued)
        assertEquals(queued, results)
        assertTrue(log.lines.none { it.contains(".pdf") })
    }

    @Test fun failuresPointAtTheSideThatFailed() {
        val unreadable = TransferExecutor(FakeWorld(current = main, unreadable = setOf("t1")), RecordingLog())
            .run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()
        val spaceGone = TransferExecutor(FakeWorld(current = main, failOpen = ProfileBridgeResult.SpaceInactive("quiet")), RecordingLog())
            .run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()
        val bridge = TransferExecutor(
            FakeWorld(current = main, failOpen = ProfileBridgeResult.BridgeNotReady(ShuttleNotReadyCause.PermissionDenied)),
            RecordingLog(),
        ).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()
        val noTarget = TransferExecutor(FakeWorld(current = main), RecordingLog())
            .run(request(main, null, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()
        val localWrite = TransferExecutor(FakeWorld(current = main, failOpen = ProfileBridgeResult.Failed(IllegalStateException())), RecordingLog())
            .run(request(dual, main, SpaceRole.Main, "t1"), TransferCancellationSignal()).single()

        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.SourceUnreadable, SpaceRole.Main), unreadable)
        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.SpaceUnavailable, SpaceRole.Dual), spaceGone)
        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.BridgeNotReady, null), bridge)
        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.SpaceUnavailable, SpaceRole.Dual), noTarget)
        // Writing into the main space failed: the failure names the main space, not "the other space".
        assertEquals(TransferOutcome.Failed("t1", FileTransferFailureReason.TargetWriteFailed, SpaceRole.Main), localWrite)
    }

    @Test fun sentRowFailureDoesNotTurnADeliveredFileIntoAFailure() {
        val world = FakeWorld(current = main, failRecordSent = true)
        val log = RecordingLog()

        val outcome = TransferExecutor(world, log).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal()).single()

        assertTrue(outcome is TransferOutcome.Sent)
        assertEquals(1, world.ledgers.getValue(dual).size)
        assertTrue(log.lines.any { it.startsWith("ledger.sent_record_failed id=t1") })
    }

    @Test fun renamedFileIsRecordedUnderItsRealNameOnBothSides() {
        // MediaStore renamed the second copy of the same name on publish.
        val world = FakeWorld(current = main, readBack = { "t1 (1).pdf" to "Download/PrismSpace/" })

        val sent = TransferExecutor(world, RecordingLog()).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal())
            .single() as TransferOutcome.Sent

        assertEquals("t1 (1).pdf", sent.displayName)
        assertEquals("Download/PrismSpace", sent.relativePath)
        assertEquals("t1 (1).pdf", world.ledgers.getValue(dual).single().first.displayName)
        assertEquals("t1 (1).pdf", world.ledgers.getValue(main).single().first.displayName)
        assertEquals("Download/PrismSpace", world.ledgers.getValue(main).single().first.relativePath)
    }

    @Test fun failedReadBackFallsBackToTheRequestedNameAndFolder() {
        val world = FakeWorld(current = main, readBack = { null })

        val sent = TransferExecutor(world, RecordingLog()).run(request(main, dual, SpaceRole.Dual, "t1"), TransferCancellationSignal())
            .single() as TransferOutcome.Sent

        assertEquals("t1.pdf", sent.displayName)
        assertEquals("Download/PrismSpace", sent.relativePath)
        assertEquals("t1.pdf", world.ledgers.getValue(main).single().first.displayName)
        assertEquals("t1.pdf", world.ledgers.getValue(dual).single().first.displayName)
    }

    @Test fun publishedFactsOverrideOnlyWhenPresent() {
        val requested = TransferLedgerDto("id", "a.png", "image/png", 1L, "Pictures/PrismSpace", null, BridgeTransferRole.Received)

        assertEquals("a (2).png", requested.withPublished(PublishedFileDto("u", "a (2).png", "Pictures/PrismSpace/")).displayName)
        assertEquals("Pictures/PrismSpace", requested.withPublished(PublishedFileDto("u", "a (2).png", "Pictures/PrismSpace/")).relativePath)
        assertEquals(requested, requested.withPublished(PublishedFileDto("u", null, null)))
        assertEquals(requested, requested.withPublished(PublishedFileDto("u", "  ", "/")))
    }

    @Test fun reasonsCollapseIntoFourClasses() {
        val classes = FileTransferFailureReason.entries.map { it.userFailureClass() }.toSet()
        assertEquals(
            setOf(
                FileTransferFailureReason.SourceUnreadable,
                FileTransferFailureReason.SpaceUnavailable,
                FileTransferFailureReason.BridgeNotReady,
                FileTransferFailureReason.TargetWriteFailed,
            ),
            classes,
        )
    }
}

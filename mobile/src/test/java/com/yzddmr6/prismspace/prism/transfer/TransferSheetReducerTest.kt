package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSheetReducerTest {

    private fun item(id: String, size: Long? = 100L, mime: String = "application/pdf") = TransferItem(
        id,
        SourceResolution.Resolved(0, SourceUserRule.CurrentUser, "content://p/$id", "$id.pdf", mime, size),
    )

    private fun request(count: Int, skipped: Int = 0) = TransferRequest(
        batchId = "b",
        entry = TransferEntry.ShareSheet,
        destination = TransferDestination.OtherSpace(0, 23, SpaceRole.Dual),
        items = (1..count).map { item("i$it") },
        skipped = (1..skipped).map { SourceRef("content://p/skip$it", listOf("content://p/skip$it"), null) },
    )

    private fun reduce(vararg events: SheetEvent): TransferSheetState? =
        events.fold(null as TransferSheetState?) { state, event -> TransferSheetReducer.reduce(state, event) }

    @Test fun externalEntryStopsAtConfirm() {
        val state = reduce(SheetEvent.Started, SheetEvent.Planned(BatchPlan.Ready(request(2, skipped = 1)), external = true))

        val confirm = state as TransferSheetState.Confirm
        assertEquals(SpaceRole.Dual, confirm.target)
        assertEquals(2, confirm.items.size)
        assertEquals(1, confirm.skipped)
        assertTrue(confirm.hasFiles)
    }

    @Test fun inAppEntryGoesStraightToProgress() {
        val state = reduce(SheetEvent.Started, SheetEvent.Planned(BatchPlan.Ready(request(3)), external = false))

        val progress = state as TransferSheetState.Progress
        assertEquals(1, progress.index)
        assertEquals(3, progress.total)
        assertNull(progress.percent)
    }

    @Test fun cancellingConfirmClosesWithoutAnyProgress() {
        val state = reduce(
            SheetEvent.Started,
            SheetEvent.Planned(BatchPlan.Ready(request(2)), external = true),
            SheetEvent.Closed,
        )

        assertNull(state)
    }

    @Test fun blockedGateKeepsConfirmOnSend() {
        val blocked = reduce(
            SheetEvent.Planned(BatchPlan.Ready(request(1)), external = true, gateGuidance = "resume first"),
            SheetEvent.SendConfirmed,
        )

        assertTrue(blocked is TransferSheetState.Confirm)
    }

    @Test fun progressPercentFollowsWrittenBytesAndStaysIndeterminateWithoutSize() {
        val sized = reduce(
            SheetEvent.Planned(BatchPlan.Ready(request(1)), external = false),
            SheetEvent.ItemStarted(0, 1, "i1.pdf", 200L),
            SheetEvent.ItemProgress(0, 50L),
        ) as TransferSheetState.Progress
        val unsized = reduce(
            SheetEvent.Planned(BatchPlan.Ready(request(1)), external = false),
            SheetEvent.ItemStarted(0, 1, "i1.pdf", 0L),
            SheetEvent.ItemProgress(0, 50L),
        ) as TransferSheetState.Progress

        assertEquals(25, sized.percent)
        assertNull(unsized.percent)
    }

    @Test fun cancelledBatchReportsSentCountInsteadOfASingleCancelled() {
        val req = request(5)
        val outcomes = listOf(
            TransferOutcome.Sent("i1", "u1", 1),
            TransferOutcome.Sent("i2", "u2", 1),
            TransferOutcome.Cancelled("i3", started = true),
            TransferOutcome.Cancelled("i4", started = false),
            TransferOutcome.Cancelled("i5", started = false),
        )

        val result = TransferSheetReducer.finished(req, outcomes)

        assertEquals(2, result.sent)
        assertEquals(5, result.total)
        assertEquals(ResultHeadline.CancelledRest, result.headline)
        assertEquals(listOf(ItemStatus.Sent, ItemStatus.Sent, ItemStatus.Cancelled, ItemStatus.Cancelled, ItemStatus.Cancelled), result.rows.map { it.status })
    }

    @Test fun headlinesCoverAllPartialAndNone() {
        val req = request(2)
        assertEquals(
            ResultHeadline.AllSent,
            TransferSheetReducer.finished(req, listOf(TransferOutcome.Sent("i1", "u", 1), TransferOutcome.Sent("i2", "u", 1))).headline,
        )
        val partial = TransferSheetReducer.finished(
            req,
            listOf(
                TransferOutcome.Sent("i1", "u", 1),
                TransferOutcome.Failed("i2", FileTransferFailureReason.TargetWriteFailed, SpaceRole.Dual),
            ),
        )
        assertEquals(ResultHeadline.Partial, partial.headline)
        assertEquals(SpaceRole.Dual, partial.rows[1].failedSpace)
        assertEquals(
            ResultHeadline.NoneSent,
            TransferSheetReducer.finished(req, listOf(TransferOutcome.Cancelled("i1", true), TransferOutcome.Cancelled("i2", false))).headline,
        )
    }

    @Test fun skippedItemsAreReportedAsUnreadableFromTheSourceSpace() {
        val result = TransferSheetReducer.finished(request(1, skipped = 1), listOf(TransferOutcome.Sent("i1", "u", 1)))

        assertEquals(1, result.sent)
        assertEquals(2, result.total)
        assertEquals(ResultHeadline.Partial, result.headline)
        assertEquals(FileTransferFailureReason.SourceUnreadable, result.rows.last().reason)
        assertEquals(SpaceRole.Main, result.rows.last().failedSpace)
    }

    @Test fun itemsWithoutOutcomeAreNeverShownAsSent() {
        val result = TransferSheetReducer.finished(request(2), listOf(TransferOutcome.Sent("i1", "u", 1)))

        assertEquals(ItemStatus.Cancelled, result.rows[1].status)
    }

    @Test fun everyRejectionReachesTheSheet() {
        BatchRejection.entries.forEach { reason ->
            assertEquals(TransferSheetState.Rejected(reason), reduce(SheetEvent.Started, SheetEvent.Planned(BatchPlan.Rejected(reason), external = true)))
        }
    }
}

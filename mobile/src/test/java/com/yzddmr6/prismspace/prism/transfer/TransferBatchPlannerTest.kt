package com.yzddmr6.prismspace.prism.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferBatchPlannerTest {

    private val main = 0
    private val dual = 23
    private var ids = 0
    private val newId: () -> String = { "id-${ids++}" }

    private fun ref(name: String) = SourceRef("content://p/$name", listOf("content://p/$name"), null)

    private fun resolved(user: Int, name: String = "a.pdf") = SourceResolution.Resolved(
        sourceUserId = user,
        rule = SourceUserRule.CurrentUser,
        readableUri = "content://p/$name",
        displayName = name,
        mime = "application/pdf",
        declaredSize = 1L,
    )

    private fun plan(resolutions: List<SourceResolution>, dualUser: Int? = dual) = TransferBatchPlanner.plan(
        TransferEntry.ShareSheet,
        resolutions.indices.map { ref("f$it") },
        resolutions,
        main,
        dualUser,
        newId,
    )

    @Test fun mainSourceTargetsTheDualSpace() {
        val request = (plan(listOf(resolved(main))) as BatchPlan.Ready).request

        assertEquals(TransferDestination.OtherSpace(main, dual, SpaceRole.Dual), request.destination)
        assertEquals(1, request.items.size)
    }

    @Test fun dualSourceTargetsTheMainSpace() {
        val request = (plan(listOf(resolved(dual))) as BatchPlan.Ready).request

        assertEquals(TransferDestination.OtherSpace(dual, main, SpaceRole.Main), request.destination)
    }

    @Test fun planIgnoresWhichUserTheReceiverRunsIn() {
        // The planner takes no "current user": the same resolutions yield the same destination
        // whether the share proxy started the receiver in the main or in the dual space.
        val resolutions = listOf(resolved(dual, "a"), resolved(dual, "b"))
        ids = 0
        val asIfInMain = (plan(resolutions) as BatchPlan.Ready).request
        ids = 0
        val asIfInDual = (plan(resolutions) as BatchPlan.Ready).request

        assertEquals(asIfInMain, asIfInDual)
        assertEquals(SpaceRole.Main, asIfInMain.destination.target)
    }

    @Test fun rejectionsAreClassified() {
        assertEquals(BatchPlan.Rejected(BatchRejection.NoFiles), plan(emptyList()))
        assertEquals(
            BatchPlan.Rejected(BatchRejection.AllUnreadable),
            plan(listOf(SourceResolution.Unreadable, SourceResolution.Unreadable)),
        )
        assertEquals(
            BatchPlan.Rejected(BatchRejection.MixedSourceUsers),
            plan(listOf(resolved(main), resolved(dual))),
        )
        assertEquals(
            BatchPlan.Rejected(BatchRejection.UnmanagedSourceUser),
            plan(listOf(resolved(999))),
        )
    }

    @Test fun partlyReadableBatchSkipsTheUnreadableItems() {
        val request = (plan(listOf(resolved(main), SourceResolution.Unreadable, resolved(main, "c"))) as BatchPlan.Ready).request

        assertEquals(2, request.items.size)
        assertEquals(listOf(ref("f1")), request.skipped)
    }

    @Test fun missingDualSpaceStillPlansTheDirectionButHasNoTargetUser() {
        val request = (plan(listOf(resolved(main)), dualUser = null) as BatchPlan.Ready).request

        assertEquals(SpaceRole.Dual, request.destination.target)
        assertNull(request.destination.targetUserId)
    }

    @Test fun everyItemGetsItsOwnTransferId() {
        val request = (plan(listOf(resolved(main, "a"), resolved(main, "b"))) as BatchPlan.Ready).request

        assertEquals(2, request.items.map { it.transferId }.toSet().size)
    }
}

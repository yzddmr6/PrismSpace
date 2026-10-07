package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason

internal data class ItemSummary(val name: String, val sizeBytes: Long?, val isImage: Boolean)

internal enum class ItemStatus { Sent, Cancelled, Failed }

internal data class ItemResult(
    val name: String,
    val status: ItemStatus,
    val reason: FileTransferFailureReason? = null,
    val failedSpace: SpaceRole? = null,
)

internal enum class ResultHeadline { AllSent, CancelledRest, Partial, NoneSent }

/** The one transfer sheet: external entries pass Confirm, in-app entries start at Progress. */
internal sealed interface TransferSheetState {
    data object Resolving : TransferSheetState

    data class Confirm(
        val target: SpaceRole,
        val items: List<ItemSummary>,
        val skipped: Int,
        val hasImages: Boolean,
        val hasFiles: Boolean,
        /** Non-null: sending is blocked and this explains how to make the target usable. */
        val gateGuidance: String?,
    ) : TransferSheetState

    data class Progress(
        val target: SpaceRole,
        /** 1-based. */
        val index: Int,
        val total: Int,
        val currentName: String,
        /** Null = indeterminate (no positive declared size). */
        val percent: Int?,
        val declaredSize: Long?,
    ) : TransferSheetState

    data class Result(
        val target: SpaceRole,
        val sent: Int,
        val total: Int,
        val cancelled: Boolean,
        val rows: List<ItemResult>,
        /** The user holding the published files (the target space); null when unknown. */
        val targetUserId: Int? = null,
        /** Published URIs of the items that were actually sent, in request order. Empty → no share action. */
        val shareItems: List<ShareItem> = emptyList(),
    ) : TransferSheetState {
        val headline: ResultHeadline
            get() = when {
                sent == 0 -> ResultHeadline.NoneSent
                cancelled -> ResultHeadline.CancelledRest
                sent == total -> ResultHeadline.AllSent
                else -> ResultHeadline.Partial
            }
    }

    data class Rejected(val reason: BatchRejection) : TransferSheetState
}

internal sealed interface SheetEvent {
    data object Started : SheetEvent
    data class Planned(val plan: BatchPlan, val external: Boolean, val gateGuidance: String? = null) : SheetEvent
    data object SendConfirmed : SheetEvent
    data object Closed : SheetEvent
    data class ItemStarted(val index: Int, val total: Int, val name: String, val declaredSize: Long?) : SheetEvent
    data class ItemProgress(val index: Int, val written: Long) : SheetEvent
    data class Finished(val request: TransferRequest, val outcomes: List<TransferOutcome>) : SheetEvent
}

/** Pure state machine for [TransferSheetState]; null = no sheet. */
internal object TransferSheetReducer {

    fun reduce(state: TransferSheetState?, event: SheetEvent): TransferSheetState? = when (event) {
        SheetEvent.Started -> TransferSheetState.Resolving
        is SheetEvent.Planned -> planned(event)
        SheetEvent.SendConfirmed -> (state as? TransferSheetState.Confirm)
            ?.takeIf { it.gateGuidance == null }
            ?.let { confirm ->
                val first = confirm.items.first()
                TransferSheetState.Progress(confirm.target, 1, confirm.items.size, first.name, null, first.sizeBytes)
            } ?: state
        SheetEvent.Closed -> null
        is SheetEvent.ItemStarted -> {
            val target = targetOf(state) ?: SpaceRole.Dual
            TransferSheetState.Progress(target, event.index + 1, event.total, event.name, null, event.declaredSize)
        }
        is SheetEvent.ItemProgress -> (state as? TransferSheetState.Progress)
            ?.takeIf { it.index == event.index + 1 }
            ?.copy(percent = transferProgressPercent(state.declaredSize, event.written))
            ?: state
        is SheetEvent.Finished -> finished(event.request, event.outcomes)
    }

    private fun planned(event: SheetEvent.Planned): TransferSheetState = when (val plan = event.plan) {
        is BatchPlan.Rejected -> TransferSheetState.Rejected(plan.reason)
        is BatchPlan.Ready -> {
            val request = plan.request
            val items = request.items.map {
                ItemSummary(it.source.displayName, it.source.declaredSize, TransferPaths.isImage(it.source.mime))
            }
            val target = request.destination.target
            if (event.external) {
                TransferSheetState.Confirm(
                    target = target,
                    items = items,
                    skipped = request.skipped.size,
                    hasImages = items.any { it.isImage },
                    hasFiles = items.any { !it.isImage },
                    gateGuidance = event.gateGuidance,
                )
            } else {
                TransferSheetState.Progress(target, 1, items.size, items.first().name, null, items.first().sizeBytes)
            }
        }
    }

    fun finished(request: TransferRequest, outcomes: List<TransferOutcome>): TransferSheetState.Result {
        val target = request.destination.target
        val source = if (target == SpaceRole.Dual) SpaceRole.Main else SpaceRole.Dual
        val byId = outcomes.associateBy { it.transferId }
        val rows = request.items.map { item ->
            val name = item.source.displayName
            when (val outcome = byId[item.transferId]) {
                is TransferOutcome.Sent -> ItemResult(outcome.displayName ?: name, ItemStatus.Sent)
                is TransferOutcome.Cancelled -> ItemResult(name, ItemStatus.Cancelled)
                is TransferOutcome.Failed -> ItemResult(name, ItemStatus.Failed, outcome.reason, outcome.failedSpace)
                // An item without an outcome never ran: report it as cancelled, never as sent.
                null -> ItemResult(name, ItemStatus.Cancelled)
            }
        } + request.skipped.map { ref ->
            ItemResult(skippedName(ref), ItemStatus.Failed, FileTransferFailureReason.SourceUnreadable, source)
        }
        // Only files that really landed can be shared on; cancelled, failed and skipped items never can.
        val shareItems = if (request.kind == TransferKind.File) {
            request.items.mapNotNull { item ->
                (byId[item.transferId] as? TransferOutcome.Sent)?.let { ShareItem(it.publishedUri, item.source.mime) }
            }
        } else {
            emptyList()
        }
        return TransferSheetState.Result(
            target = target,
            sent = rows.count { it.status == ItemStatus.Sent },
            total = rows.size,
            cancelled = rows.any { it.status == ItemStatus.Cancelled },
            rows = rows,
            targetUserId = request.destination.targetUserId,
            shareItems = shareItems,
        )
    }

    private fun skippedName(ref: SourceRef): String =
        ref.receivedUri.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { ref.receivedUri }

    private fun targetOf(state: TransferSheetState?): SpaceRole? = when (state) {
        is TransferSheetState.Confirm -> state.target
        is TransferSheetState.Progress -> state.target
        is TransferSheetState.Result -> state.target
        else -> null
    }
}

/** Only a positive declared size yields a percentage; otherwise the bar stays indeterminate. */
internal fun transferProgressPercent(declaredSize: Long?, written: Long): Int? = when {
    declaredSize == null || declaredSize <= 0L -> null
    else -> ((written.toDouble() / declaredSize) * 100).toInt().coerceIn(0, 100)
}

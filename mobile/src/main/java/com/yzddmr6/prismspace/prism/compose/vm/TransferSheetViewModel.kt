package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.service.TransferCancellationSignal
import com.yzddmr6.prismspace.prism.transfer.AndroidSourceResolver
import com.yzddmr6.prismspace.prism.transfer.AndroidTransferPorts
import com.yzddmr6.prismspace.prism.transfer.BatchPlan
import com.yzddmr6.prismspace.prism.transfer.ExecutorEvent
import com.yzddmr6.prismspace.prism.transfer.SheetEvent
import com.yzddmr6.prismspace.prism.transfer.SourceResolution
import com.yzddmr6.prismspace.prism.transfer.SpaceRole
import com.yzddmr6.prismspace.prism.transfer.TransferBatchPlanner
import com.yzddmr6.prismspace.prism.transfer.TransferEntry
import com.yzddmr6.prismspace.prism.transfer.TransferExecutor
import com.yzddmr6.prismspace.prism.transfer.TransferOutcome
import com.yzddmr6.prismspace.prism.transfer.TransferRequest
import com.yzddmr6.prismspace.prism.transfer.TransferSheetReducer
import com.yzddmr6.prismspace.prism.transfer.TransferSheetState
import com.yzddmr6.prismspace.prism.transfer.currentDualUsability
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates one "send to the other space" batch behind [TransferSheetState]. External entries
 * (share sheet, any explicit SEND intent) stop at Confirm until the user taps Send; in-app entries,
 * whose files the user just picked inside PrismSpace, go straight to Progress. Skipping Confirm is
 * an in-process call, never an intent extra.
 */
class TransferSheetViewModel(app: Application) : AndroidViewModel(app) {

    private val mutableState = MutableStateFlow<TransferSheetState?>(null)
    internal val state: StateFlow<TransferSheetState?> = mutableState

    private var request: TransferRequest? = null
    private var cancellation: TransferCancellationSignal? = null
    private var job: Job? = null
    private var externalStarted = false

    /** Share-sheet entry. Idempotent across configuration changes. */
    fun startExternal(uris: List<Uri>, intentType: String?) {
        if (externalStarted) return
        externalStarted = true
        begin(uris, intentType, TransferEntry.ShareSheet, external = true)
    }

    /** In-app entry (Files page / dual-space entry): the picked files go straight to Progress. */
    fun startInApp(uris: List<Uri>, entry: TransferEntry) {
        if (uris.isEmpty() || isBusy()) return
        begin(uris, null, entry, external = false)
    }

    /** Confirm → Progress. Ignored while the space gate blocks sending. */
    fun send() {
        val confirm = mutableState.value as? TransferSheetState.Confirm ?: return
        if (confirm.gateGuidance != null) return
        val pending = request ?: return
        DiagnosticLog.i(TAG, "xfer.batch.confirm result=send batch=${pending.batchId}")
        dispatch(SheetEvent.SendConfirmed)
        execute(pending)
    }

    /**
     * Cancels whatever is in flight. Progress stops at the next block boundary and still ends in a
     * Result; Confirm and Resolving close the sheet without writing anything.
     */
    fun cancel() {
        when (mutableState.value) {
            is TransferSheetState.Progress -> cancellation?.cancel()
            is TransferSheetState.Confirm -> {
                DiagnosticLog.i(TAG, "xfer.batch.confirm result=cancel batch=${request?.batchId}")
                close()
            }
            TransferSheetState.Resolving -> {
                job?.cancel()
                close()
            }
            else -> Unit
        }
    }

    fun close() {
        request = null
        dispatch(SheetEvent.Closed)
    }

    private fun isBusy() = when (mutableState.value) {
        TransferSheetState.Resolving, is TransferSheetState.Progress -> true
        else -> false
    }

    private fun begin(uris: List<Uri>, intentType: String?, entry: TransferEntry, external: Boolean) {
        request = null
        dispatch(SheetEvent.Started)
        job = viewModelScope.launch {
            val (plan, gate) = withContext(Dispatchers.IO) { plan(uris, intentType, entry, external) }
            if (mutableState.value != TransferSheetState.Resolving) return@launch
            dispatch(SheetEvent.Planned(plan, external, gate))
            if (plan is BatchPlan.Ready) {
                request = plan.request
                if (!external) execute(plan.request)
            }
        }
    }

    private fun plan(uris: List<Uri>, intentType: String?, entry: TransferEntry, external: Boolean): Pair<BatchPlan, String?> {
        val app = getApplication<Application>()
        val currentUserId = Users.currentId()
        val currentIsParent = runCatching { Users.isParentProfile() }.getOrDefault(true)
        val parentUserId = runCatching { Users.parentProfile.toId() }.getOrNull()
        val dualUserId = if (currentIsParent) Users.profile?.toId()
        else currentUserId.takeIf { Users.isCurrentProfileManagedByPrism() }
        val pairedUserId = if (currentIsParent) dualUserId else parentUserId
        val resolver = AndroidSourceResolver(app, currentUserId, pairedUserId)
        val refs = uris.distinct().map { resolver.ref(it, intentType) }
        DiagnosticLog.i(TAG, "xfer.batch.open entry=$entry items=${refs.size} current=$currentUserId")
        val resolutions = refs.mapIndexed { index, ref ->
            runCatching { resolver.resolve(ref, index) }.getOrElse { error ->
                DiagnosticLog.w(TAG, "xfer.resolve idx=$index rule=Unreadable cause=${error.javaClass.simpleName}")
                SourceResolution.Unreadable
            }
        }
        val plan = TransferBatchPlanner.plan(entry, refs, resolutions, parentUserId, dualUserId) {
            UUID.randomUUID().toString()
        }
        if (plan is BatchPlan.Rejected) {
            DiagnosticLog.w(
                TAG,
                "xfer.batch.rejected reason=${plan.reason} users=${TransferBatchPlanner.sourceUsers(resolutions)}",
            )
        }
        // SpaceRepository is @OwnerUser: the gate is only computable in the main space. Inside the
        // dual space the main space is necessarily running; failures are classified by the executor.
        val gate = if (external && currentIsParent && plan is BatchPlan.Ready && plan.request.destination.target == SpaceRole.Dual) {
            fileTransferGate(currentDualUsability(app), prismResolver(app)).guidance
        } else {
            null
        }
        return plan to gate
    }

    private fun execute(pending: TransferRequest) {
        val signal = TransferCancellationSignal()
        cancellation = signal
        job = viewModelScope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                TransferExecutor(AndroidTransferPorts(getApplication())).run(pending, signal) { event ->
                    when (event) {
                        is ExecutorEvent.ItemStarted -> dispatch(
                            SheetEvent.ItemStarted(
                                event.index,
                                event.total,
                                event.item.source.displayName,
                                event.item.source.declaredSize,
                            ),
                        )
                        is ExecutorEvent.ItemProgress -> dispatch(SheetEvent.ItemProgress(event.index, event.written))
                        is ExecutorEvent.ItemFinished -> Unit
                    }
                }
            }
            logDone(pending, outcomes)
            cancellation = null
            dispatch(SheetEvent.Finished(pending, outcomes))
        }
    }

    private fun logDone(pending: TransferRequest, outcomes: List<TransferOutcome>) {
        DiagnosticLog.i(
            TAG,
            "xfer.batch.done batch=${pending.batchId} sent=${outcomes.count { it is TransferOutcome.Sent }} " +
                "total=${pending.items.size + pending.skipped.size} " +
                "failed=${outcomes.count { it is TransferOutcome.Failed } + pending.skipped.size} " +
                "cancelled=${outcomes.count { it is TransferOutcome.Cancelled }}",
        )
    }

    private fun dispatch(event: SheetEvent) {
        mutableState.update { TransferSheetReducer.reduce(it, event) }
    }

    override fun onCleared() {
        cancellation?.cancel()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "Prism.ImportToSpace"
    }
}

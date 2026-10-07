package com.yzddmr6.prismspace.controller

import android.content.Context
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.bridge.ApplySystemAppSelection
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.MAX_SYSTEM_APP_SELECTION_CHANGES
import com.yzddmr6.prismspace.bridge.QuerySystemAppSelectionPage
import com.yzddmr6.prismspace.bridge.SelectionFinish
import com.yzddmr6.prismspace.bridge.SystemAppApplyReportDto
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import com.yzddmr6.prismspace.bridge.SystemAppSelectionEntry
import com.yzddmr6.prismspace.bridge.SystemAppSelectionPage
import com.yzddmr6.prismspace.data.PrismAppListProvider
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation
import com.yzddmr6.prismspace.provisioning.SelectionStatus
import com.yzddmr6.prismspace.util.UserHandles

/** Profile-side selection state read by the main space; the main space keeps no copy of it. */
internal data class SystemAppSelectionState(val status: SelectionStatus?, val entries: List<SystemAppSelectionEntry>)

/**
 * Main-space client of the profile-side system app policy. Every add/remove of a system app goes
 * through [ApplySystemAppSelection]; the result is the only evidence the UI may report.
 */
internal object SystemAppSelectionClient {

    @WorkerThread
    fun readSelection(context: Context, userId: Int, pageLimit: Int = MAX_PAGES): ProfileBridgeResult<SystemAppSelectionState> {
        val entries = ArrayList<SystemAppSelectionEntry>()
        var status: SelectionStatus? = null
        var page = 0
        while (page < pageLimit) {
            val result = runProfileBridgeOperation(
                context, TAG, "query system app selection page=$page",
                target = BridgeTargets.profile(userId),
                command = QuerySystemAppSelectionPage(page),
            )
            val value = (result as? ProfileBridgeResult.Value<SystemAppSelectionPage>)?.value
                ?: return result.failure()
            status = value.status
            entries += value.entries
            if (!value.hasMore) return ProfileBridgeResult.Value(SystemAppSelectionState(status, entries))
            page++
        }
        return ProfileBridgeResult.Failed(IllegalStateException("System app selection exceeded $pageLimit pages"))
    }

    @WorkerThread
    fun readStatus(context: Context, userId: Int): ProfileBridgeResult<SelectionStatus?> {
        val result = runProfileBridgeOperation(
            context, TAG, "query system app selection status",
            target = BridgeTargets.profile(userId),
            command = QuerySystemAppSelectionPage(0, 1),
        )
        val value = (result as? ProfileBridgeResult.Value<SystemAppSelectionPage>)?.value ?: return result.failure()
        return ProfileBridgeResult.Value(value.status)
    }

    /**
     * Sends [changes] in bounded chunks; only the last chunk carries [finish]. Reports of later
     * chunks win for a package, so a target flipped by the final status change is reported last.
     */
    @WorkerThread
    fun apply(
        context: Context,
        userId: Int,
        changes: List<SystemAppOverrideChange>,
        finish: SelectionFinish?,
    ): ProfileBridgeResult<SystemAppApplyReportDto> {
        val chunks = chunkSelection(changes)
        val merged = LinkedHashMap<String, Int>()
        val ignored = LinkedHashSet<String>()
        chunks.forEachIndexed { index, chunk ->
            val last = index == chunks.lastIndex
            val result = runProfileBridgeOperation(
                context, TAG, "apply system app selection changes=${chunk.size} finish=${if (last) finish else null}",
                target = BridgeTargets.profile(userId),
                command = ApplySystemAppSelection(chunk, if (last) finish else null),
            )
            val report = (result as? ProfileBridgeResult.Value<SystemAppApplyReportDto>)?.value ?: return result.failure()
            report.available.forEach { merged[it] = AVAILABLE }
            report.unavailable.forEach { merged[it] = UNAVAILABLE }
            report.absent.forEach { merged[it] = ABSENT }
            report.failed.forEach { merged[it] = FAILED }
            ignored += report.ignoredCritical
        }
        fun with(kind: Int) = merged.filterValues { it == kind }.keys.toList()
        return ProfileBridgeResult.Value(SystemAppApplyReportDto(with(AVAILABLE), with(UNAVAILABLE), with(ABSENT), with(FAILED), ignored.toList()))
    }

    /** Single-package add/remove from the space page or the clone flow, then local bookkeeping. */
    @WorkerThread
    fun setAvailable(context: Context, userId: Int, pkg: String, available: Boolean): ProfileBridgeResult<SystemAppApplyReportDto> {
        val choice = if (available) SystemAppChoice.Enabled else SystemAppChoice.Disabled
        val result = apply(context, userId, listOf(SystemAppOverrideChange(pkg, choice)), finish = null)
        (result as? ProfileBridgeResult.Value)?.value?.let { record(context, userId, it, setOf(pkg)) }
        return result
    }

    /**
     * Mirrors a policy report into the main space: the list provider's policy cache and the explicit
     * system-clone markers (only for packages the user chose, see [UserCloneRegistry]).
     */
    fun record(context: Context, userId: Int, report: SystemAppApplyReportDto, chosen: Set<String>) {
        report.available.filter { it in chosen }.forEach { UserCloneRegistry.add(context, it) }
        report.unavailable.forEach { UserCloneRegistry.remove(context, it) }
        PrismAppListProvider.getInstance(context).applyPolicyResult(UserHandles.of(userId), report.available, report.unavailable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ProfileBridgeResult<*>.failure(): ProfileBridgeResult<T> = when (this) {
        is ProfileBridgeResult.Value -> ProfileBridgeResult.Failed(IllegalStateException("Empty system app selection result"))
        else -> this as ProfileBridgeResult<T>
    }

    private const val AVAILABLE = 0
    private const val UNAVAILABLE = 1
    private const val ABSENT = 2
    private const val FAILED = 3
    private const val MAX_PAGES = 50
    private const val TAG = "Prism.SysAppClient"
}

/** Chunks a selection to the bridge bound; an empty selection is still one (finish-carrying) chunk. */
internal fun chunkSelection(changes: List<SystemAppOverrideChange>): List<List<SystemAppOverrideChange>> =
    if (changes.isEmpty()) listOf(emptyList()) else changes.chunked(MAX_SYSTEM_APP_SELECTION_CHANGES)

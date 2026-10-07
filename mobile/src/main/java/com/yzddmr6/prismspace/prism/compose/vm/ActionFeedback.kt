package com.yzddmr6.prismspace.prism.compose.vm

import android.content.Context
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale

/** Unified feedback-channel currency. */
data class ActionFeedback(val message: String, val isError: Boolean)

/**
 * Resolver from a string-resource id (+ optional format args) to a localized string.
 *
 * Production callers pass a lambda backed by `PrismLocale.wrap(context)::getString`, so the
 * copy follows the user's chosen language. Tests provide their own resource-backed resolver.
 */
typealias StringResolver = (Int, Array<out Any>) -> String

/**
 * Build a locale-aware [StringResolver] backed by the user's chosen language. Resolves each id
 * through `PrismLocale.wrap(context)` so the copy follows the in-app language override.
 */
fun prismResolver(context: Context): StringResolver =
    { id, args -> PrismLocale.wrap(context).getString(id, *args) }

/**
 * Pure: batch-action feedback shared by SpaceViewModel and tests.
 * failed == failures.size; isError == failures.isNotEmpty().
 * CopyToDual is intentionally absent: batch clone reports through [batchCloneFeedback], which
 * counts real per-package results instead of "request started".
 */
fun batchActionFeedback(action: BatchAction, succeeded: Int, failed: Int, res: StringResolver): ActionFeedback {
    val msg = when (action) {
        BatchAction.Freeze ->
            if (failed == 0) res(R.string.lz_vm_batch_freeze_ok, arrayOf(succeeded))
            else res(R.string.lz_vm_batch_freeze_partial, arrayOf(succeeded, failed))
        BatchAction.Uninstall ->
            if (failed == 0) res(R.string.lz_vm_batch_uninstall_ok, arrayOf(succeeded))
            else res(R.string.lz_vm_batch_uninstall_partial, arrayOf(succeeded, failed))
        BatchAction.CopyToDual -> error("Batch clone uses batchCloneFeedback (real per-package results)")
    }
    return ActionFeedback(msg, isError = failed > 0)
}

/** Pure, truthful summary for the system-uninstaller queue. */
internal fun uninstallQueueFeedback(
    summary: UninstallSummary,
    skipped: Int = 0,
    res: StringResolver,
): ActionFeedback = ActionFeedback(
    res(
        R.string.lz_vm_uninstall_queue_summary,
        arrayOf(summary.succeeded, summary.cancelled, summary.timedOut + skipped),
    ),
    isError = summary.cancelled > 0 || summary.timedOut > 0 || skipped > 0,
)

/** Pure: the queue stopped on a mid-run usability gate trip; unlaunched heads are counted as
 *  not attempted (never fired), followed by the state-specific guidance. */
internal fun uninstallAbortFeedback(
    queue: UninstallQueueState,
    skipped: Int,
    guidance: String,
    res: StringResolver,
): ActionFeedback = ActionFeedback(
    res(R.string.lz_vm_uninstall_queue_aborted, arrayOf(
        queue.summary.succeeded,
        queue.summary.cancelled,
        queue.summary.timedOut,
        queue.total - queue.outcomes.size + skipped,
        guidance,
    )),
    isError = true,
)

/** Pure: batch-clone summary from real per-package results. Staged packages are "prepared" and the
 *  copy always states that installation still needs confirmation inside the dual space — nothing
 *  may read as cloned until an enhanced route reports a verified install. Single emission. */
internal fun batchCloneFeedback(counts: BatchCloneCounts, res: StringResolver): ActionFeedback {
    val message = when {
        counts.prepared == 0 && counts.installed == 0 ->
            res(R.string.lz_vm_batch_clone_failed, arrayOf(counts.failed))
        counts.installed == 0 && counts.failed == 0 ->
            res(R.string.lz_vm_batch_clone_prepared, arrayOf(counts.prepared))
        counts.installed == 0 ->
            res(R.string.lz_vm_batch_clone_prepared_partial, arrayOf(counts.prepared, counts.failed))
        counts.prepared == 0 && counts.failed == 0 ->
            res(R.string.lz_vm_batch_clone_installed, arrayOf(counts.installed))
        counts.prepared == 0 ->
            res(R.string.lz_vm_batch_clone_installed_partial, arrayOf(counts.installed, counts.failed))
        else ->
            res(R.string.lz_vm_batch_clone_mixed, arrayOf(counts.installed, counts.prepared, counts.failed))
    }
    return ActionFeedback(message, isError = counts.failed > 0)
}

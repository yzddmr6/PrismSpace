package com.yzddmr6.prismspace.prism.compose.vm

internal const val UNINSTALL_VERIFICATION_TIMEOUT_MS = 15_000L

/** Grace after the host returns to foreground with the package still present: a confirmed-but-slow
 *  uninstall that lands within the grace still counts as verified success; a package that is still
 *  present once the grace elapses means the user backed out of the system dialog (Cancelled).
 *
 *  Known boundaries, both failing in the CONSERVATIVE direction (never a false "已卸载"):
 *  - Dismissing the dialog via recents/app-switch keeps the dialog task alive: the host resumes
 *    while the package is still present, so after the grace the head is classified Cancelled and
 *    the queue advances; a LATER confirmation from the still-open dialog then lands without a
 *    queue watcher and self-heals via the next list refresh (the row disappears).
 *  - On ROMs where package removal takes >2s after confirmation, a real uninstall may be reported
 *    as 「未卸载」 first; the same refresh self-heals the row. The 15s timeout below stays the
 *    fallback for the genuinely ambiguous case (profile state unqueryable after return).
 *  Neither boundary ever claims an uninstall that did not happen. */
internal const val UNINSTALL_CANCEL_GRACE_MS = 2_000L

internal data class UninstallRequest(
    val packageName: String,
    val targetUserId: Int,
    val mainCopyExisted: Boolean,
)

internal enum class UninstallStage { ReadyToLaunch, AwaitingSystemUi, Verifying }
internal enum class UninstallOutcomeStatus { Success, Cancelled, TimedOut }

/** Outcome of asking the profile to present the system uninstaller for one queue head. */
internal sealed interface UninstallLaunchReply {
    data object Launched : UninstallLaunchReply

    /** @param message user-facing guidance; @param reason classified cause for diagnostics. */
    data class Failed(val message: String?, val reason: String? = null) : UninstallLaunchReply
}

/** The launch step of the uninstall queue: routes the request into the managed profile. */
internal fun interface UninstallLaunchPort {
    suspend fun requestUninstall(request: UninstallRequest): UninstallLaunchReply
}

internal data class UninstallCurrent(
    val request: UninstallRequest,
    val stage: UninstallStage,
    val verificationStartedAtMs: Long? = null,
)

internal data class UninstallOutcome(
    val request: UninstallRequest,
    val status: UninstallOutcomeStatus,
    val mainCopyLost: Boolean,
)

internal data class UninstallQueueState(
    val pending: List<UninstallRequest> = emptyList(),
    val current: UninstallCurrent? = null,
    val outcomes: List<UninstallOutcome> = emptyList(),
    val total: Int = 0,
) {
    val complete: Boolean get() = current == null && pending.isEmpty()
    val summary: UninstallSummary get() = UninstallSummary(
        succeeded = outcomes.count { it.status == UninstallOutcomeStatus.Success },
        cancelled = outcomes.count { it.status == UninstallOutcomeStatus.Cancelled },
        timedOut = outcomes.count { it.status == UninstallOutcomeStatus.TimedOut },
    )
}

internal data class UninstallSummary(val succeeded: Int, val cancelled: Int, val timedOut: Int) {
    val failed: Int get() = cancelled + timedOut
}

internal object UninstallQueueReducer {

    fun start(requests: List<UninstallRequest>): UninstallQueueState {
        val first = requests.firstOrNull()
        return UninstallQueueState(
            pending = requests.drop(1),
            current = first?.let { UninstallCurrent(it, UninstallStage.ReadyToLaunch) },
            total = requests.size,
        )
    }

    fun launched(state: UninstallQueueState): UninstallQueueState {
        val current = state.current?.takeIf { it.stage == UninstallStage.ReadyToLaunch } ?: return state
        return state.copy(current = current.copy(stage = UninstallStage.AwaitingSystemUi))
    }

    /** The host returned to the foreground (the profile-side system dialog was dismissed or the
     *  user navigated back). Verification — not the return itself — decides the outcome. */
    fun returned(state: UninstallQueueState, nowMs: Long): UninstallQueueState {
        val current = state.current ?: return state
        return when (current.stage) {
            UninstallStage.AwaitingSystemUi -> state.copy(current = current.copy(
                stage = UninstallStage.Verifying,
                verificationStartedAtMs = nowMs,
            ))
            UninstallStage.Verifying, UninstallStage.ReadyToLaunch -> state
        }
    }

    fun observed(
        state: UninstallQueueState,
        installedInTarget: Boolean?,
        mainCopyExists: Boolean,
        nowMs: Long,
    ): UninstallQueueState {
        val current = state.current?.takeIf { it.stage == UninstallStage.Verifying } ?: return state
        val elapsedMs = nowMs - (current.verificationStartedAtMs ?: nowMs)
        val status = when {
            installedInTarget == false -> UninstallOutcomeStatus.Success
            installedInTarget == true && elapsedMs >= UNINSTALL_CANCEL_GRACE_MS ->
                UninstallOutcomeStatus.Cancelled
            elapsedMs >= UNINSTALL_VERIFICATION_TIMEOUT_MS -> UninstallOutcomeStatus.TimedOut
            else -> return state
        }
        return completeCurrent(state, status, mainCopyExists)
    }

    fun launchFailed(state: UninstallQueueState, mainCopyExists: Boolean): UninstallQueueState =
        completeCurrent(state, UninstallOutcomeStatus.TimedOut, mainCopyExists)

    /** A delayed launch check can only finish the exact head that scheduled it. */
    fun launchUnobserved(state: UninstallQueueState, expected: UninstallCurrent?, mainCopyExists: Boolean): UninstallQueueState =
        if (state.current === expected && expected?.stage == UninstallStage.AwaitingSystemUi)
            launchFailed(state, mainCopyExists) else state

    private fun completeCurrent(
        state: UninstallQueueState,
        status: UninstallOutcomeStatus,
        mainCopyExists: Boolean,
    ): UninstallQueueState {
        val current = state.current ?: return state
        val outcome = UninstallOutcome(
            current.request,
            status,
            mainCopyLost = current.request.mainCopyExisted && !mainCopyExists,
        )
        val next = state.pending.firstOrNull()
        return state.copy(
            pending = state.pending.drop(1),
            current = next?.let { UninstallCurrent(it, UninstallStage.ReadyToLaunch) },
            outcomes = state.outcomes + outcome,
        )
    }
}

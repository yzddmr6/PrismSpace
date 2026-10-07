package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.UNINSTALL_CANCEL_GRACE_MS
import com.yzddmr6.prismspace.prism.compose.vm.UNINSTALL_VERIFICATION_TIMEOUT_MS
import com.yzddmr6.prismspace.prism.compose.vm.UninstallLaunchPort
import com.yzddmr6.prismspace.prism.compose.vm.UninstallLaunchReply
import com.yzddmr6.prismspace.prism.compose.vm.UninstallOutcomeStatus
import com.yzddmr6.prismspace.prism.compose.vm.UninstallQueueReducer
import com.yzddmr6.prismspace.prism.compose.vm.UninstallQueueState
import com.yzddmr6.prismspace.prism.compose.vm.UninstallRequest
import com.yzddmr6.prismspace.prism.compose.vm.UninstallStage
import com.yzddmr6.prismspace.prism.compose.vm.uninstallAbortFeedback
import com.yzddmr6.prismspace.prism.compose.vm.uninstallQueueFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class UninstallQueueTest {

    @Test fun delayedLaunchWatchdogCannotCancelAResumedOrReplacementHead() {
        val awaiting = UninstallQueueReducer.launched(UninstallQueueReducer.start(listOf(request("one"))))
        val resumed = UninstallQueueReducer.returned(awaiting, 100)
        assertEquals(resumed, UninstallQueueReducer.launchUnobserved(resumed, awaiting.current, true))
        val replacement = UninstallQueueReducer.launched(UninstallQueueReducer.start(listOf(request("one"))))
        assertEquals(replacement, UninstallQueueReducer.launchUnobserved(replacement, awaiting.current, true))
    }

    @Test fun silentLaunchFailureTerminatesWithoutClaimingRemoval() {
        val state = UninstallQueueReducer.launched(UninstallQueueReducer.start(listOf(request("one"))))
        val failed = UninstallQueueReducer.launchUnobserved(state, state.current, true)
        assertTrue(failed.complete)
        assertEquals(0, failed.summary.succeeded)
        assertEquals(1, failed.summary.timedOut)
    }

    @Test fun allConfirmedAdvanceOneAtATimeAndCountRealSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one"), request("two")))
        assertEquals("one", state.current?.request?.packageName)
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 100)
        state = UninstallQueueReducer.observed(state, installedInTarget = false, mainCopyExists = true, nowMs = 100)
        assertEquals("two", state.current?.request?.packageName)
        assertEquals(UninstallStage.ReadyToLaunch, state.current?.stage)

        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 200)
        state = UninstallQueueReducer.observed(state, installedInTarget = false, mainCopyExists = true, nowMs = 200)

        assertTrue(state.complete)
        assertEquals(2, state.summary.succeeded)
        assertEquals(0, state.summary.failed)
    }

    /** The production cancel path: the profile-side dialog was dismissed (host resumed to
     *  foreground) and the package is still present once the cancel grace elapses. */
    @Test fun verifiedCancellationDoesNotCountAsSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 100)

        // Within the grace window a still-present package is not yet judged (confirm may be slow).
        state = UninstallQueueReducer.observed(state, installedInTarget = true, mainCopyExists = true, nowMs = 100)
        assertEquals(UninstallStage.Verifying, state.current?.stage)
        assertTrue(state.outcomes.isEmpty())

        state = UninstallQueueReducer.observed(
            state, installedInTarget = true, mainCopyExists = true, nowMs = 100 + UNINSTALL_CANCEL_GRACE_MS,
        )

        assertEquals(UninstallOutcomeStatus.Cancelled, state.outcomes.single().status)
        assertEquals(0, state.summary.succeeded)
        assertEquals(1, state.summary.cancelled)
    }

    @Test fun confirmLandingInsideTheCancelGraceCountsAsVerifiedSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 1_000)
        state = UninstallQueueReducer.observed(state, installedInTarget = true, mainCopyExists = true, nowMs = 1_000)
        state = UninstallQueueReducer.observed(
            state, installedInTarget = false, mainCopyExists = true, nowMs = 1_000 + UNINSTALL_CANCEL_GRACE_MS / 2,
        )

        assertEquals(UninstallOutcomeStatus.Success, state.outcomes.single().status)
        assertEquals(1, state.summary.succeeded)
        assertEquals(0, state.summary.cancelled)
    }

    /** Genuinely ambiguous case: the profile cannot even be queried after the return — the 15s
     *  timeout stays the fallback and never claims success. */
    @Test fun unknownPackageStateTimesOutWithoutClaimingSuccess() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 1_000)
        state = UninstallQueueReducer.observed(
            state,
            installedInTarget = null,
            mainCopyExists = true,
            nowMs = 1_000 + UNINSTALL_VERIFICATION_TIMEOUT_MS,
        )

        assertEquals(UninstallOutcomeStatus.TimedOut, state.outcomes.single().status)
        assertEquals(1, state.summary.timedOut)
    }

    @Test fun timeInSystemUiStartsNeitherGraceNorTimeout() {
        var state = UninstallQueueReducer.start(listOf(request("one")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.observed(
            state,
            installedInTarget = true,
            mainCopyExists = true,
            nowMs = UNINSTALL_VERIFICATION_TIMEOUT_MS * 10,
        )
        assertFalse(state.complete)
        assertEquals(UninstallStage.AwaitingSystemUi, state.current?.stage)

        state = UninstallQueueReducer.returned(state, 200_000)
        state = UninstallQueueReducer.observed(state, true, true, 200_001)
        assertEquals(UninstallStage.Verifying, state.current?.stage)
    }

    @Test fun verifiedSuccessAndCancelStayDistinctOutcomes() {
        var success = UninstallQueueReducer.start(listOf(request("ok")))
        success = UninstallQueueReducer.launched(success)
        success = UninstallQueueReducer.returned(success, 0)
        success = UninstallQueueReducer.observed(success, false, true, 0)

        var cancelled = UninstallQueueReducer.start(listOf(request("cancel")))
        cancelled = UninstallQueueReducer.launched(cancelled)
        cancelled = UninstallQueueReducer.returned(cancelled, 0)
        cancelled = UninstallQueueReducer.observed(cancelled, true, true, UNINSTALL_CANCEL_GRACE_MS)

        assertEquals(UninstallOutcomeStatus.Success, success.outcomes.single().status)
        assertEquals(UninstallOutcomeStatus.Cancelled, cancelled.outcomes.single().status)
    }

    @Test fun summarySeparatesNotRemovedFromUnconfirmed() {
        var state = UninstallQueueReducer.start(listOf(request("cancel"), request("timeout")))
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 0)
        state = UninstallQueueReducer.observed(state, true, true, UNINSTALL_CANCEL_GRACE_MS)
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 0)
        state = UninstallQueueReducer.observed(state, null, true, UNINSTALL_VERIFICATION_TIMEOUT_MS)

        assertEquals("卸载完成：已卸载 0 个，未卸载 1 个，未能确认 1 个。", uninstallQueueFeedback(state.summary).message)
    }

    @Test fun launchedReplyFromStubbedPortAwaitsSystemUi() {
        val port = UninstallLaunchPort { UninstallLaunchReply.Launched }
        var state = UninstallQueueReducer.start(listOf(request("one")))

        state = driveLaunch(state, port, mainCopyExists = true)

        assertEquals(UninstallStage.AwaitingSystemUi, state.current?.stage)
        assertTrue(state.outcomes.isEmpty())
    }

    @Test fun failedReplyFromStubbedPortCountsAsUnconfirmedAndAdvances() {
        val port = UninstallLaunchPort { UninstallLaunchReply.Failed("bridge_not_ready") }
        var state = UninstallQueueReducer.start(listOf(request("one"), request("two")))

        state = driveLaunch(state, port, mainCopyExists = true)

        assertEquals(UninstallOutcomeStatus.TimedOut, state.outcomes.single().status)
        assertEquals(0, state.summary.succeeded)
        assertEquals(1, state.summary.timedOut)
        assertEquals("two", state.current?.request?.packageName)
        assertEquals(UninstallStage.ReadyToLaunch, state.current?.stage)
    }

    @Test fun failedLaunchWithMainCopyLostRaisesSentinel() {
        var state = UninstallQueueReducer.start(listOf(request("one")))

        state = driveLaunch(
            state,
            UninstallLaunchPort { UninstallLaunchReply.Failed("x") },
            mainCopyExists = false,
        )

        assertTrue(state.outcomes.single().mainCopyLost)
    }

    @Test fun stubbedPortDrivesSerialQueueToCompletion() {
        val replies = ArrayDeque<UninstallLaunchReply>(
            listOf(UninstallLaunchReply.Launched, UninstallLaunchReply.Failed("profile_down")),
        )
        val port = UninstallLaunchPort { replies.removeFirst() }
        var state = UninstallQueueReducer.start(listOf(request("one"), request("two")))

        // First head: launched in the profile, then verified gone → real success.
        state = driveLaunch(state, port, mainCopyExists = true)
        state = UninstallQueueReducer.returned(state, 100)
        state = UninstallQueueReducer.observed(state, installedInTarget = false, mainCopyExists = true, nowMs = 100)
        assertEquals(UninstallOutcomeStatus.Success, state.outcomes[0].status)
        assertEquals("two", state.current?.request?.packageName)

        // Second head: profile could not present the uninstaller → unconfirmed, never success.
        state = driveLaunch(state, port, mainCopyExists = true)

        assertTrue(state.complete)
        assertEquals(UninstallOutcomeStatus.TimedOut, state.outcomes[1].status)
        assertEquals(1, state.summary.succeeded)
        assertEquals(1, state.summary.timedOut)
        assertEquals("卸载完成：已卸载 1 个，未卸载 0 个，未能确认 1 个。", uninstallQueueFeedback(state.summary).message)
    }

    @Test fun abortFeedbackCountsNotAttemptedHeadsHonestly() {
        var state = UninstallQueueReducer.start((1..5).map { request("app$it") })
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 0)
        state = UninstallQueueReducer.observed(state, false, true, 0)
        state = UninstallQueueReducer.launched(state)
        state = UninstallQueueReducer.returned(state, 0)
        state = UninstallQueueReducer.observed(state, true, true, UNINSTALL_CANCEL_GRACE_MS)
        state = UninstallQueueReducer.launchFailed(state, true)

        val fb = uninstallAbortFeedback(state, 1, "请先在设置中修复双开空间连接，然后再卸载分身")

        assertEquals("卸载已停止：已卸载 1 个，未卸载 1 个，未能确认 1 个，3 个未执行。请先在设置中修复双开空间连接，然后再卸载分身", fb.message)
        assertTrue(fb.isError)
    }

    @Test fun abortBeforeFirstLaunchDoesNotClaimAnyRemoval() {
        val state = UninstallQueueReducer.start(listOf(request("one"), request("two")))
        val fb = uninstallAbortFeedback(state, 0, "请先恢复双开空间")
        assertEquals("卸载已停止：已卸载 0 个，未卸载 0 个，未能确认 0 个，2 个未执行。请先恢复双开空间", fb.message)
    }

    /** Mirrors the production mapping in SpaceViewModel.driveUninstallLaunch: a launched reply
     *  awaits the system UI; a failed reply records the real observed main-copy state. */
    private fun driveLaunch(
        state: UninstallQueueState,
        port: UninstallLaunchPort,
        mainCopyExists: Boolean,
    ): UninstallQueueState = runBlocking {
        val current = state.current?.takeIf { it.stage == UninstallStage.ReadyToLaunch } ?: return@runBlocking state
        when (port.requestUninstall(current.request)) {
            UninstallLaunchReply.Launched -> UninstallQueueReducer.launched(state)
            is UninstallLaunchReply.Failed -> UninstallQueueReducer.launchFailed(state, mainCopyExists)
        }
    }

    private fun request(pkg: String) = UninstallRequest(pkg, targetUserId = 22, mainCopyExisted = true)
}

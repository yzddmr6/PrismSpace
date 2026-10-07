package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.vm.*
import java.io.*
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpaceBrowsingTest {
    @Test fun eachRealSpaceRetainsItsOwnQueryAndChoices() {
        val main = SpaceBrowseOptions("mail", sort = SortOrder.Cloned, filter = CloneFilter.No)
        val dual = SpaceBrowseOptions("chat", showSystem = true)
        val another = SpaceBrowseOptions("bank")
        val state = SpaceUiState(segment = SpaceSegment.Main, selectedDualSpaceId = "space_29",
            browsing = mapOf("main" to main, "space_29" to dual, "space_30" to another))
        assertEquals(main, state.browse)
        assertEquals(dual, state.copy(segment = SpaceSegment.Dual).browse)
        assertEquals(another, state.copy(segment = SpaceSegment.Dual, selectedDualSpaceId = "space_30").browse)
        assertEquals(main, state.copy(segment = SpaceSegment.Dual).copy(segment = SpaceSegment.Main).browse)
    }

    @Test fun browseChoicesCanBeSavedWithoutAndroidObjects() {
        val choices = hashMapOf("space_29" to SpaceBrowseOptions("chat", "system", SortOrder.Cloned, CloneFilter.Yes, true))
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { stream -> stream.writeObject(choices) } }.toByteArray()
        assertEquals(choices, ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() })
    }

    @Test fun identicalNamesHaveDeterministicOrderRegardlessOfInputOrder() {
        val a = row("a.pkg", "Same")
        val b = row("b.pkg", "Same")
        assertEquals(listOf(a,b), transform(listOf(b,a)))
        assertEquals(transform(listOf(a,b)), transform(listOf(b,a)))
    }

    @Test fun chineseNamesUseChineseCollationInsteadOfUnicodeOrder() {
        assertEquals(listOf("阿里", "微信", "支付宝"), transform(listOf(row("z","支付宝"),row("w","微信"),row("a","阿里"))).map { it.label })
    }

    @Test fun cloneStatusChangesDoNotReorderNameSortedRows() {
        val rows = listOf(row("a","Alpha"),row("z","Zulu"))
        assertEquals(listOf("a","z"), transform(listOf(rows[1].copy(cloned=true),rows[0])).map { it.pkg })
    }

    @Test fun unknownCloneStatusIsNotAnUnclonedApp() {
        val unknown = row("unknown", "Unknown").copy(cloneStateKnown = false)
        assertTrue(applyListTransform(listOf(unknown), SpaceSegment.Main, "", SortOrder.Name, CloneFilter.No, false).isEmpty())
    }

    @Test fun missingTargetSnapshotDoesNotOfferAddOrContinueInstall() {
        val input = SpaceAppInput("app", "App", false, false, true, false, false,
            segment = SpaceSegment.Main, cloneStateKnown = false)
        val rows = mapRows(listOf(input, input.copy(prepared = true))) { _, _ -> "status" }
        assertTrue(rows.all { it.primaryAction == null && !it.cloneStateKnown })
    }

    @Test fun updatesWhileLoadingAreMergedWithoutConcurrentLoads() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val next = CompletableDeferred<SpaceReloadRequest>()
        var calls = 0
        try {
            val queue = SpaceReloadQueue(scope, debounceMs = 0, onFailure = { throw it }) { request ->
                if (++calls == 1) { entered.complete(Unit); release.await() } else next.complete(request)
            }
            queue.request(SpaceReloadRequest(setOf(0)))
            withTimeout(2_000) { entered.await() }
            repeat(100) { queue.request(SpaceReloadRequest(setOf(29))) }
            queue.request(SpaceReloadRequest(setOf(30), true))
            assertEquals(1, calls)
            release.complete(Unit)
            assertEquals(SpaceReloadRequest(setOf(29,30), true), withTimeout(2_000) { next.await() })
        } finally { scope.cancel() }
    }

    @Test fun failedRefreshReportsFailureAndDoesNotKillFutureRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Exception>()
        val recovered = CompletableDeferred<Unit>()
        try {
            val queue = SpaceReloadQueue(scope, debounceMs = 0, onFailure = { failed.complete(it) }) { request ->
                if (request.users == setOf(29)) error("profile unavailable")
                recovered.complete(Unit)
            }
            queue.request(SpaceReloadRequest(setOf(29)))
            assertEquals("profile unavailable", withTimeout(2_000) { failed.await() }.message)
            queue.request(SpaceReloadRequest(setOf(0)))
            withTimeout(2_000) { recovered.await() }
        } finally { scope.cancel() }
    }

    @Test fun fullRefreshDominatesMergedPackageUpdates() {
        assertEquals(SpaceReloadRequest(null, true), SpaceReloadRequest(setOf(29)).merge(SpaceReloadRequest(null,true)))
        assertEquals(SpaceReloadRequest(null, true), SpaceReloadRequest(null,true).merge(SpaceReloadRequest(setOf(0))))
    }

    @Test fun reloadReasonsAreMergedForDiagnostics() {
        val merged = SpaceReloadRequest(setOf(22), reason = "resume")
            .merge(SpaceReloadRequest(setOf(22), reason = "callback"))
            .merge(SpaceReloadRequest(setOf(22), reason = "resume"))
        assertEquals(setOf(22), merged.users)
        assertEquals("resume+callback", merged.reason)
        assertEquals("load", SpaceReloadRequest().merge(SpaceReloadRequest()).reason)
    }

    @Test fun showAllSystemAppsDefaultsOffForEverySpace() {
        // The dual list already carries policy-enabled system apps (with Open); the extra
        // "show all system apps" view stays opt-in, matching the corrected SpaceScreen comment.
        assertEquals(false, SpaceBrowseOptions().showSystem)
    }

    private fun row(pkg: String, label: String) = SpaceRow(pkg, label, false, false, true, false, false, false, SpaceSegment.Main, null, false)
    private fun transform(rows: List<SpaceRow>) = applyListTransform(rows, SpaceSegment.Main, "", SortOrder.Name, CloneFilter.All, false, Locale.CHINA)
}

package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSuitePublisherTest {

    @Test fun namesAreCanonicalInsideTheExactPackageNamespace() {
        assertEquals("pkg.apk", ApkSuiteNames.canonical("pkg", 0))
        assertEquals("pkg.split2.apk", ApkSuiteNames.canonical("pkg", 2))
        assertEquals(".pkg.prism-pending-token-1.apk", ApkSuiteNames.pending("pkg", "token", 1))
    }

    @Test fun completeSuiteReplacesTheLedgerSuiteInOnePublishStep() {
        val store = RecordingStore()
        val published = ApkSuitePublisher(store) { "fixed" }.replace(
            listOf("/base.apk", "/split.apk"),
            "pkg",
            "Download/PrismSpace/",
            previous = listOf(PublishedApk("old-base", ""), PublishedApk("old-split", "")),
        )

        assertEquals(listOf("new-0", "new-1"), published)
        assertEquals(listOf("pkg.apk", "pkg.split1.apk"), store.published.map { it.canonicalName })
        assertEquals(listOf("old-base", "old-split"), store.replacedPrevious.map { it.uri })
        assertTrue(store.aborted.isEmpty())
    }

    @Test fun sameNamePlainFileOutsideTheLedgerSuiteIsNeverDeleted() {
        // A user-transferred "pkg.apk" lives in the same folder but was never part of a published
        // suite: the caller's previous list (from the ledger) does not contain it, so it survives.
        val store = RecordingStore(folder = listOf(PublishedApk("user-file", "pkg.apk")))
        ApkSuitePublisher(store) { "fixed" }.replace(listOf("/base.apk"), "pkg", "Download/PrismSpace/", previous = emptyList())

        assertTrue(store.replacedPrevious.isEmpty())
        assertEquals(listOf("user-file"), store.folder.map { it.uri })
    }

    @Test fun duplicatePreviousUrisAreDeletedOnce() {
        val store = RecordingStore()
        ApkSuitePublisher(store) { "fixed" }.replace(
            listOf("/base.apk"),
            "pkg",
            "Download/PrismSpace/",
            previous = listOf(PublishedApk("old", ""), PublishedApk("old", "")),
        )

        assertEquals(listOf("old"), store.replacedPrevious.map { it.uri })
    }

    @Test fun copyFailureAbortsOnlyNewRowsAndNeverPublishesOverPreviousSuite() {
        val store = RecordingStore(failStageIndex = 1)

        runCatching {
            ApkSuitePublisher(store) { "fixed" }.replace(
                listOf("/base.apk", "/split.apk"),
                "pkg",
                "Download/PrismSpace/",
                previous = listOf(PublishedApk("old", "")),
            )
        }

        assertEquals(listOf("new-0"), store.aborted.map { it.uri })
        assertTrue(store.published.isEmpty())
        assertTrue(store.replacedPrevious.isEmpty())
    }

    @Test fun atomicPublishFailureCleansNewRowsAndLeavesPreviousSuiteToProviderTransaction() {
        val store = RecordingStore(failPublish = true)

        runCatching {
            ApkSuitePublisher(store) { "fixed" }.replace(
                listOf("/base.apk"),
                "pkg",
                "Download/PrismSpace/",
                previous = listOf(PublishedApk("old", "")),
            )
        }

        assertEquals(listOf("new-0"), store.aborted.map { it.uri })
        assertTrue(store.replacedPrevious.isEmpty())
    }

    private class RecordingStore(
        val folder: List<PublishedApk> = emptyList(),
        private val failStageIndex: Int? = null,
        private val failPublish: Boolean = false,
    ) : ApkSuiteStore {
        val staged = mutableListOf<StagedApk>()
        val aborted = mutableListOf<StagedApk>()
        var replacedPrevious = emptyList<PublishedApk>()
        var published = emptyList<StagedApk>()

        override fun stage(sourcePath: String, pendingName: String, canonicalName: String, relativePath: String): StagedApk {
            if (staged.size == failStageIndex) error("copy failed")
            return StagedApk("new-${staged.size}", canonicalName).also(staged::add)
        }

        override fun replaceAtomically(previous: List<PublishedApk>, staged: List<StagedApk>) {
            if (failPublish) error("publish failed")
            replacedPrevious = previous
            published = staged
        }

        override fun abort(staged: StagedApk) { aborted += staged }
    }
}

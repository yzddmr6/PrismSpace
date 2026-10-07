package com.yzddmr6.prismspace.controller

import java.io.File
import com.yzddmr6.prismspace.prism.compose.vm.PrismMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch clone diagnostics parity: each batch route emits the same analytics event as the
 * interactive route it mirrors, and the batch path never performs per-package quiet-mode
 * activation retries (the driver owns the single per-run budget).
 */
class BatchCloneContractTest {

    @Test fun batchFileSyncEmitsTheSameEventAsInteractiveClone() {
        val batch = requestForBatchSource()

        assertTrue(batch.contains("\"clone_file_sync\""))
        assertTrue(batch.contains("allowActivationRetry = false"))
        assertTrue(batch.contains("needsActivation = true"))
    }

    @Test fun batchRootKeepsTheCloneRootEvent() {
        val source = readSource()
        val root = source.substringAfter("fun installExistingViaRoot").substringBefore("fun transactPrivilegedClone")

        assertTrue(root.contains("\"clone_root\""))
    }

    @Test fun interactiveStagingKeepsPerRequestActivationRetry() {
        // Single-clone UX must not change: the activation retry stays available by default.
        val source = readSource()
        val core = source.substringAfter("fun stageApkSetToProfile").substringBefore("data class RootInstallResult")
        assertTrue(core.contains("allowActivationRetry: Boolean = true"))

        val interactive = source.substringAfter("fun cloneViaFileSync").substringBefore("private fun feedback")
        assertFalse(interactive.contains("allowActivationRetry = false"))
        assertTrue(interactive.contains("prompt_activating_space"))
    }

    @Test fun forcedNormalPreparationNeverInstallsSilentlyUnderRootOrShizuku() {
        // The selection page's preinstalled group: Root/Shizuku may be configured and ready, yet the
        // route must stay the user-confirmed FILE_SYNC preparation (BatchCloneResult.Prepared).
        val ready = CloneRuntimeReadiness(shizukuReady = true, rootReady = true)
        for (preferred in PrismMode.values()) {
            val mode = batchCloneMode(preferred, forceNormalPreparation = true)
            assertEquals(PrismAppClones.MODE_INSTALLER, mode)
            assertEquals(CloneRoute.FILE_SYNC, planCloneRoute(false, false, mode, { true }, ready).route)
        }
        assertEquals(CloneRoute.ROOT, planCloneRoute(false, false, batchCloneMode(PrismMode.Root, false), { true }, ready).route)
        assertEquals(CloneRoute.SHIZUKU, planCloneRoute(false, false, batchCloneMode(PrismMode.Shizuku, false), { true }, ready).route)

        val batch = requestForBatchSource()
        assertTrue(batch.contains("batchCloneMode(runtime.preferredMode, forceNormalPreparation)"))
        assertTrue(batch.contains("result.success -> BatchCloneResult.Prepared"))
    }

    private fun requestForBatchSource(): String =
        readSource().substringAfter("fun requestForBatch").substringBefore("fun fullApkSet")

    private fun readSource(): String =
        listOf(
            File("mobile/src/main/java/com/yzddmr6/prismspace/controller/PrismAppClones.kt"),
            File("src/main/java/com/yzddmr6/prismspace/controller/PrismAppClones.kt"),
        ).first(File::isFile).readText()
}

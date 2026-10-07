package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

class ProfileSystemAppsProvisioningGuardTest {

    @Test
    fun provisioningKeepsUserFacingInstallEntrancesEnabled() {
        val source = String(Files.readAllBytes(systemAppsManagerSource()), StandardCharsets.UTF_8)

        assertTrue(source.contains("\"com.miui.packageinstaller\""))
        assertTrue(source.contains("\"com.android.fileexplorer\""))
        assertTrue(source.contains("\"com.google.android.documentsui\""))
    }

    @Test
    fun everyProvisioningEntryConvergesThroughTheSystemAppPolicy() {
        val source = String(Files.readAllBytes(prismProvisioningSource()), StandardCharsets.UTF_8)
        val incremental = source.substringAfter("void performIncrementalProfileOwnerProvisioningIfNeeded")
            .substringBefore("static boolean shouldRunOneTimePostProvisionMigration")
        val fresh = source.substringAfter("void proceedProfileProvisioning")
            .substringBefore("private static void finishByLaunchingOwnerUser")
        val repair = source.substringAfter("void reprovisionManagedProfile")
            .substringBefore("private static void retireConvergeTrampoline")

        assertTrue(fresh.contains("SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Provision, provisionState)"))
        assertTrue(repair.contains("SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Repair, stateBeforeRepair)"))
        assertTrue(incremental.contains("SystemAppPolicyRuntime.converge(context, policies, ConvergeReason.Incremental, state)"))
    }

    @Test
    fun freshProvisioningConvergesBeforeTheProfileIsEnabled() {
        val source = String(Files.readAllBytes(prismProvisioningSource()), StandardCharsets.UTF_8)
        val fresh = source.substringAfter("void proceedProfileProvisioning")
            .substringBefore("private static void finishByLaunchingOwnerUser")

        val converge = fresh.indexOf("SystemAppPolicyRuntime.converge")
        val enable = fresh.indexOf("DevicePolicyManager::setProfileEnabled")
        assertTrue(converge >= 0)
        assertTrue(enable > converge)
    }

    @Test
    fun legacyManagedProvisioningListsAreGone() {
        val provisioning = String(Files.readAllBytes(prismProvisioningSource()), StandardCharsets.UTF_8)
        val manual = String(Files.readAllBytes(manualProvisioningSource()), StandardCharsets.UTF_8)

        assertFalse(provisioning.contains("hideUnnecessaryAppsInManagedProfile"))
        assertFalse(provisioning.contains("enableCriticalAppsIfNeeded"))
        assertFalse(provisioning.contains("DeleteNonRequiredAppsTask"))
        assertFalse(manual.contains("DeleteNonRequiredAppsTask"))
        assertFalse(Files.exists(sourcePath("engine/src/main/java/com/yzddmr6/prismspace/provisioning")
            .resolve("task/DeleteNonRequiredAppsTask.java")))
    }

    private fun systemAppsManagerSource(): Path {
        return sourcePath("shared/src/main/java/com/yzddmr6/prismspace/provisioning/SystemAppsManager.java")
    }

    private fun prismProvisioningSource(): Path {
        return sourcePath("engine/src/main/java/com/yzddmr6/prismspace/provisioning/PrismProvisioning.java")
    }

    private fun manualProvisioningSource(): Path =
        sourcePath("engine/src/main/java/com/yzddmr6/prismspace/provisioning/ProfileOwnerManualProvisioning.java")

    private fun sourcePath(relativePath: String): Path {
        var current = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            val source = current.resolve(relativePath)
            if (Files.exists(source)) return source
            current = current.parent
        }
        error("Cannot locate $relativePath from ${System.getProperty("user.dir")}")
    }
}

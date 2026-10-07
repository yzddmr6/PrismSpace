package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolContractTest {

    @Test fun commandIdsAreUniqueAndUsedAsWireMethodNames() {
        val commands = BridgeCommandCatalog.all

        assertEquals(commands.size, commands.map { it.id }.distinct().size)
        commands.forEach { command -> assertEquals(command.id, BridgeWire.methodName(command)) }
    }

    @Test fun assembledHandlersExposeNoMissingPorts() {
        val handlers = BridgeHandlers(
            FakeAppControlPort,
            FakeFileBridgePort,
            FakeAppListPort,
            FakeShortcutPort,
            FakeInstallerPort,
        )

        assertTrue(handlers.missing().isEmpty())
        assertTrue(handlers.missing(requireInstaller = true).isEmpty())
        assertTrue(handlers.copy(installer = null).missing().isEmpty())
        assertEquals(listOf("installer"), handlers.copy(installer = null).missing(requireInstaller = true))
    }

    @Test fun commandPayloadsCannotCarryExecutableOrIntentTargets() {
        BridgeCommandCatalog.all.forEach { command ->
            command.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                assertTrue("${command.id}.${field.name} carries Intent", !Intent::class.java.isAssignableFrom(field.type))
                assertTrue("${command.id}.${field.name} carries function", !Function::class.java.isAssignableFrom(field.type))
                assertTrue("${command.id}.${field.name} carries reflection target", !Class::class.java.isAssignableFrom(field.type))
                assertTrue(
                    "${command.id}.${field.name} looks like an executable escape hatch",
                    FORBIDDEN_EXECUTABLE_FIELD_NAMES.none { field.name.contains(it, ignoreCase = true) },
                )
            }
        }
    }

    @Test fun profileAppPageSizeIsServerBounded() {
        assertEquals(1, clampProfileAppPageSize(-1))
        assertEquals(50, clampProfileAppPageSize(50))
        assertEquals(MAX_PROFILE_APP_PAGE_SIZE, clampProfileAppPageSize(Int.MAX_VALUE))
    }

    @Test fun diagnosticsChunkSizeCannotBeSelectedByCaller() {
        val payloadFields = ReadDiagnosticsChunk::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(setOf("token", "offset"), payloadFields.toSet())
        assertEquals(2, payloadFields.size)
        assertTrue(DIAGNOSTICS_CHUNK_BYTES < 1024 * 1024)
    }

    @Test fun profileAppPageAccumulatorStopsAtBoundaryPage() {
        val first = sampleProfileApp("first")
        val second = sampleProfileApp("second")
        val accumulator = ProfileAppPageAccumulator()

        assertTrue(accumulator.accept(ProfileAppPage(listOf(first), hasMore = true)))
        assertTrue(!accumulator.accept(ProfileAppPage(listOf(second), hasMore = false)))
        assertEquals(listOf(first, second), accumulator.entries)
    }

    @Test fun ordinaryTargetResolutionIsCachedAndFreshResolutionIsExplicit() {
        val source = File("src/main/java/com/yzddmr6/prismspace/bridge/BridgeTargets.kt").readText()
        val ordinary = source.substringAfter("fun profile(userId: Int)")
            .substringBefore("@WorkerThread")
        val fresh = source.substringAfter("fun profileFresh")
            .substringBefore("fun parent")
        val parent = source.substringAfter("fun parent()")
            .substringBefore("@WorkerThread")
        val freshParent = source.substringAfter("fun parentFresh")

        assertFalse(source.contains("fun profile(context: Context"))
        assertFalse(source.contains("fun parent(context: Context"))
        assertFalse(ordinary.contains("Users.refreshUsers"))
        assertFalse(ordinary.contains("DevicePolicies"))
        assertFalse(parent.contains("Users.refreshUsers"))
        assertFalse(parent.contains("DevicePolicies"))
        assertTrue(fresh.indexOf("Users.refreshUsers") < fresh.indexOf("return profile"))
        assertTrue(freshParent.indexOf("Users.refreshUsers") < freshParent.indexOf("return parent"))
    }

    @Test fun currentProfileOwnershipIsCollectedBeforeTargetResolution() {
        val users = File("src/main/java/com/yzddmr6/prismspace/util/Users.kt").readText()
        val refresh = users.substringAfter("fun refreshUsers(context: Context)")
            .substringBefore("fun isProfileRunning")

        assertTrue(refresh.contains("sCurrentProfileManagedByPrism = runCatching { DevicePolicies(context).isProfileOwner }"))
        assertTrue(users.contains("fun isCurrentProfileManagedByPrism() = sCurrentProfileManagedByPrism"))
    }

    @Test fun requestAppUninstallIsEnumeratedAsProfileSideCommand() {
        val commands = BridgeCommandCatalog.all.filterIsInstance<RequestAppUninstall>()

        assertEquals(1, commands.size)
        assertEquals("app.request_uninstall", commands.single().id)
    }

    @Test fun profileUninstallIntentSpecContainsNoUserTargeting() {
        val source = File("src/main/java/com/yzddmr6/prismspace/bridge/CoreBridgeOperations.kt").readText()
        val spec = source.codeLinesOnly().substringAfter("fun uninstallIntentSpec").substringBefore("\nfun ")

        assertTrue(spec.contains("ACTION_UNINSTALL_PACKAGE"))
        assertFalse("the profile-side uninstall spec must never carry a target-user extra", spec.contains("EXTRA_USER"))
    }

    @Test fun requestAppUninstallIsDispatched() {
        val source = File("src/main/java/com/yzddmr6/prismspace/bridge/BridgeDispatcher.kt").readText()

        assertTrue(source.contains("is RequestAppUninstall ->"))
        assertTrue(source.contains("CoreBridgeOperations.requestAppUninstall(context, command.packageName)"))
    }

    @Test fun transferCommandsAreDestinationCommandsAndDispatched() {
        val transferCommands = listOf(
            RecordTransfer::class.java,
            InspectTransferredFile::class.java,
        )
        transferCommands.forEach { type ->
            val samples = BridgeCommandCatalog.all.filter { type.isInstance(it) }
            assertEquals("${type.simpleName} must be enumerated exactly once", 1, samples.size)
            assertTrue("${type.simpleName} must run in the user that owns the file", samples.single() is DestinationCommand<*>)
        }
        val dispatcher = File("src/main/java/com/yzddmr6/prismspace/bridge/BridgeDispatcher.kt").readText()
        listOf("is RecordTransfer ->", "is InspectTransferredFile ->").forEach { branch ->
            assertTrue("dispatcher must route $branch", dispatcher.contains(branch))
        }
    }

    @Test fun vendorClonePresenceIsAMainSpaceQueryAndDispatched() {
        val samples = BridgeCommandCatalog.all.filterIsInstance<QueryVendorCloneProfilePresence>()
        assertEquals(1, samples.size)
        assertTrue("the dual space asks the main space", samples.single() is ParentCommand<*>)
        assertEquals("space.query_vendor_clone_presence", samples.single().id)
        val dispatcher = File("src/main/java/com/yzddmr6/prismspace/bridge/BridgeDispatcher.kt").readText()
        assertTrue(dispatcher.contains("QueryVendorCloneProfilePresence -> success("))
        assertTrue(dispatcher.contains("Users.vendorCloneProfilePresence(context)"))
    }

    @Test fun removedFileCommandsStayRemoved() {
        val ids = BridgeCommandCatalog.all.map { it.id }.toSet()
        listOf(
            "file.query_latest_visible_image",
            "file.open_image_picker",
            "file.open_latest_for_read",
            "file.write_per_app_share_marker",
            "file.delete_per_app_share_marker",
            // Cross-space open/share is forwarded by the system intent forwarder, never parked in a mailbox.
            "file.queue_transfer_open",
        ).forEach { removed -> assertFalse("$removed must not come back", removed in ids) }
        assertEquals(listOf(CrossProfileForwardingKind.ProfileDownloads), CrossProfileForwardingKind.entries.toList())
    }

    private object FakeAppControlPort : AppControlPort {
        override fun setAppFrozen(context: Context, packageName: String, frozen: Boolean) = true
        override fun ensureAppHiddenState(context: Context, packageName: String, hidden: Boolean) = true
        override fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean) = true
        override fun setPackagesSuspended(
            context: Context,
            packageNames: List<String>,
            suspended: Boolean,
        ) = emptyArray<String>()
        override fun setPackagesFrozen(
            context: Context,
            packageNames: List<String>,
            frozen: Boolean,
        ) = emptyArray<String>()
        override fun ensureAppFreeToLaunch(context: Context, packageName: String) = ""
    }

    private object FakeFileBridgePort : FileBridgePort {
        override fun queryPendingClonePreparations(context: Context): List<String> = emptyList()
        override fun openWriteSession(
            context: Context,
            store: BridgeFileStore,
            safeName: String,
            mimeType: String,
            relativePath: String,
        ): WriteSessionDto = error("unused")
        override fun finishWriteSession(
            context: Context,
            store: BridgeFileStore,
            targetUri: String,
            record: TransferLedgerDto?,
        ) = PublishedFileDto(targetUri, record?.displayName, record?.relativePath)
        override fun abortWriteSession(
            context: Context,
            store: BridgeFileStore,
            targetUri: String,
            transferId: String?,
        ) = Unit
        override fun importApkSet(
            context: Context,
            paths: List<String>,
            label: String,
            packageName: String,
            cloneLocation: String,
        ): String? = null
        override fun completeClonePreparation(context: Context, packageName: String) = true
        override fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto? = null
        override fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind) = true
        override fun recordTransfer(context: Context, record: TransferLedgerDto, contentUri: String) = true
        override fun inspectTransferredFile(
            context: Context,
            contentUri: String,
            mime: String?,
            mode: BridgeOpenMode,
        ) = BridgeInspectResult.Exists
    }

    private object FakeAppListPort : AppListPort {
        override fun queryProfileApps(context: Context, pageIndex: Int, pageSize: Int) =
            ProfileAppPage(emptyList(), false)
    }

    private object FakeShortcutPort : ShortcutPort {
        override fun prepareProfileLaunch(
            context: Context,
            packageName: String,
            action: String?,
            dataUri: String?,
            categories: List<String>,
        ) = true
        override fun cancelProfileLaunch(context: Context) = Unit
        override fun updateAll(context: Context, dynamicLabel: Boolean) = true
        override fun removeInParent(context: Context, packageName: String, profileUserId: Int) = true
        override fun refreshInParent(context: Context, packageName: String, profileUserId: Int) = true
        override fun queryDynamicLabelEnabled(context: Context) = true
    }

    private object FakeInstallerPort : InstallerPort {
        override fun notifyPackageRestarted(
            context: Context,
            packageName: String,
            uid: Int,
            uptimeMillis: Long,
        ) = true
    }

    private fun sampleProfileApp(packageName: String) = ProfileAppEntry(
        packageName,
        uid = 1,
        flags = 0,
        hidden = false,
        enabled = true,
        targetSdkVersion = 35,
        label = packageName,
        iconResource = 0,
        sourceDir = null,
        publicSourceDir = null,
        splitSourceDirs = emptyList(),
    )

    private companion object {
        val FORBIDDEN_EXECUTABLE_FIELD_NAMES = setOf(
            "intent",
            "className",
            "methodName",
            "fieldName",
            "shellCommand",
        )

        /** Strips comment lines so assertions target code, not prose mentioning guarded tokens. */
        fun String.codeLinesOnly(): String = lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")
    }
}

package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.yzddmr6.prismspace.shuttle.ShuttleProvider

class BridgeCommandParcelTest {

    @Test fun everyCommandAndResultRoundTrips() {
        assertRoundTrip(Ping, true)
        assertRoundTrip(QueryPendingClonePreparations, listOf("one", "two"))
        assertRoundTrip(SetAppFrozen("pkg", true), true)
        assertRoundTrip(EnsureAppHiddenState("pkg", false), false)
        assertRoundTrip(SetPackageSuspended("pkg", true), true)
        assertRoundTrip(SetPackagesSuspended(listOf("a", "b"), false), arrayOf("b"))
        assertRoundTrip(SetPackagesFrozen(listOf("a", "b"), true), arrayOf("a"))
        assertRoundTrip(EnsureAppFreeToLaunch("pkg"), "reason")
        assertRoundTrip(
            QuerySystemAppSelectionPage(0, 50),
            SystemAppSelectionPage(
                com.yzddmr6.prismspace.provisioning.SelectionStatus.Pending,
                listOf(SystemAppSelectionEntry("pkg", null, null, false, true, true, true)),
                hasMore = false,
            ),
        )
        assertRoundTrip(
            ApplySystemAppSelection(listOf(SystemAppOverrideChange("pkg", SystemAppChoice.Disabled)), SelectionFinish.Confirm),
            SystemAppApplyReportDto(listOf("a"), listOf("b"), listOf("c"), emptyList(), emptyList()),
        )

        val writePipe = ParcelFileDescriptor.createPipe()
        try {
            assertRoundTrip(
                OpenWriteSession(BridgeFileStore.Downloads, "name", "type", "path"),
                WriteSessionDto("content://write", writePipe[1]),
            )
            assertRoundTrip(
                FinishWriteSession(
                    BridgeFileStore.Media,
                    "content://write",
                    TransferLedgerDto(
                        "id",
                        "name",
                        "image/png",
                        3L,
                        "Pictures/PrismSpace",
                        BridgeTransferDirection.ToProfile,
                        BridgeTransferRole.Received,
                    ),
                ),
                PublishedFileDto("content://write", "name (1).png", "Pictures/PrismSpace/"),
            )
            assertRoundTrip(AbortWriteSession(BridgeFileStore.Media, "content://write", "id"), Unit)
            assertRoundTrip(
                ImportApkSet(listOf("/base.apk", "/split.apk"), "Label", "pkg", "Downloads"),
                "content://apk",
            )
            assertRoundTrip(
                RecordTransfer(
                    TransferLedgerDto("id", "name", "type", null, null, null, BridgeTransferRole.Sent),
                    "content://target",
                ),
                true,
            )
            assertRoundTrip(
                InspectTransferredFile("content://target", "type", BridgeOpenMode.File),
                BridgeInspectResult.NoViewer,
            )
            assertRoundTrip(
                QueueTransferOpen(TransferOpenRequestDto("id", BridgeOpenMode.Folder, null, null, "Pictures/PrismSpace")),
                true,
            )
            assertRoundTrip(
                QueueTransferOpen(
                    TransferOpenRequestDto(
                        "id",
                        BridgeOpenMode.Share,
                        null,
                        null,
                        null,
                        listOf(TransferShareItemDto("content://a", "image/png"), TransferShareItemDto("content://b", null)),
                    ),
                ),
                true,
            )
            assertRoundTrip(RunBridgeSelfTest(byteArrayOf(1, 2)), SelfTestResultDto(byteArrayOf(2, 1), "location"))
            assertRoundTrip(
                InstallCrossProfileForwarding(CrossProfileForwardingKind.ProfileDownloads),
                true,
            )
            assertRoundTrip(
                QueryProfileAppsPage(1, Int.MAX_VALUE),
                ProfileAppPage(listOf(sampleProfileApp()), hasMore = false),
            )
            assertRoundTrip(UpdateAllShortcutsInProfile(false), true)
            assertRoundTrip(RemoveShortcutsInParent("pkg", 10), true)
            assertRoundTrip(RefreshShortcutInParent("pkg", 10), false)
            assertRoundTrip(QueryDynamicShortcutLabelEnabled, true)
            assertRoundTrip(
                QueryProfileProvisioningFacts,
                ProfileProvisioningFactsDto(profileOwner = true, provisionComplete = false),
            )
            assertRoundTrip(TriggerIncrementalProvisioning, true)
            assertRoundTrip(WipeProfile, false)
            assertRoundTrip(QueryParentIsProfileOwner, true)
            assertRoundTrip(QueryVendorCloneProfilePresence, com.yzddmr6.prismspace.util.CloneProfilePresence.Present)
            assertRoundTrip(SaveProfileName(10, "Work"), true)
            assertRoundTrip(EstablishBackwardGrant, Unit)
            assertRoundTrip(SetAppOpMode("pkg", 1, 2, 1_000_001), Unit)
            assertRoundTrip(NotifyPackageRestarted("pkg", 1_000_001, 123L), true)
            assertRoundTrip(StartProfileDeactivation(10), Unit)
            assertRoundTrip(
                UnfreezeAndLaunchApp("pkg"),
                LaunchOutcomeDto(LaunchOutcomeKind.Unknown, "reason"),
            )
            assertRoundTrip(
                PrepareProfileShortcutLaunch(
                    "pkg",
                    "android.intent.action.VIEW",
                    "https://example.test/path#fragment",
                    listOf("android.intent.category.BROWSABLE"),
                ),
                true,
            )
            assertRoundTrip(CancelProfileShortcutLaunch, Unit)
            assertRoundTrip(OpenAppDetailsInProfile("pkg"), Unit)
            assertRoundTrip(RequestAppUninstall("pkg"), UninstallLaunchDto(UninstallLaunchKind.Failed, "reason"))
            assertRoundTrip(RequestAppUninstall("pkg"), UninstallLaunchDto(UninstallLaunchKind.Prepared))
            val diagnosticToken = "00000000-0000-0000-0000-000000000000"
            assertRoundTrip(
                OpenDiagnosticsSnapshot,
                DiagnosticsSnapshotSessionDto(diagnosticToken, 512L),
            )
            assertRoundTrip(
                ReadDiagnosticsChunk(diagnosticToken, 256L),
                DiagnosticsChunkDto(byteArrayOf(1, 2, 3), eof = true),
            )
            assertRoundTrip(ReadDiagnosticsChunk(diagnosticToken, 512L), DiagnosticsSnapshotInvalid)
            assertRoundTrip(CloseDiagnosticsSnapshot(diagnosticToken), Unit)
        } finally {
            writePipe.forEach(ParcelFileDescriptor::close)
        }
    }

    @Test fun providerSetsCommandClassLoaderBeforeFirstBundleRead() {
        val request = roundTripBundleWithoutClassLoader(BridgeWire.request(Ping))
        val response = requireNotNull(ShuttleProvider().call("wrong.method", null, request))
        val error = runCatching { BridgeWire.decode(Ping, response) }.exceptionOrNull()

        assertTrue(error is BridgeExecutionException)
        assertEquals(BridgeErrorCategory.InvalidRequest, (error as BridgeExecutionException).bridgeError.category)
        assertEquals("method=wrong.method", error.bridgeError.message)
    }

    @Test fun missingCommandIsAnInvalidRequest() {
        val response = requireNotNull(ShuttleProvider().call(Ping.id, null, null))
        val error = runCatching { BridgeWire.decode(Ping, response) }.exceptionOrNull()

        assertTrue(error is BridgeExecutionException)
        assertEquals(BridgeErrorCategory.InvalidRequest, (error as BridgeExecutionException).bridgeError.category)
    }

    @Test fun requiredResultsFailClosedWhenMissing() {
        listOf<BridgeCommand<*>>(
            SetPackagesSuspended(emptyList(), true),
            SetPackagesFrozen(emptyList(), true),
            EnsureAppFreeToLaunch("pkg"),
        ).forEach { command ->
            assertTrue(runCatching { command.decodeResult(Bundle()) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun <R> assertRoundTrip(command: BridgeCommand<R>, result: R) {
        val restoredCommand = roundTripParcelable(command)
        assertEquals(command.id, restoredCommand.id)
        if (command is RunBridgeSelfTest && restoredCommand is RunBridgeSelfTest) {
            assertArrayEquals(command.marker, restoredCommand.marker)
        } else {
            assertEquals(command, restoredCommand)
        }

        val encoded = Bundle().also { command.encodeResult(result, it) }
        val restoredBundle = roundTripBundle(encoded)
        val decoded = command.decodeResult(restoredBundle)
        assertResultEquals(result, decoded)
    }

    private fun <T : android.os.Parcelable> roundTripParcelable(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            parcel.readParcelable<T>(BridgeCommand::class.java.classLoader) as T
        } finally {
            parcel.recycle()
        }
    }

    private fun roundTripBundle(value: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(value)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            requireNotNull(parcel.readBundle(BridgeCommand::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun roundTripBundleWithoutClassLoader(value: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(value)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            requireNotNull(parcel.readBundle())
        } finally {
            parcel.recycle()
        }
    }

    private fun assertResultEquals(expected: Any?, actual: Any?) {
        when {
            expected is Array<*> && actual is Array<*> -> assertArrayEquals(expected, actual)
            expected is SelfTestResultDto && actual is SelfTestResultDto -> {
                assertArrayEquals(expected.bytes, actual.bytes)
                assertEquals(expected.location, actual.location)
            }
            expected is DiagnosticsChunkDto && actual is DiagnosticsChunkDto -> {
                assertArrayEquals(expected.bytes, actual.bytes)
                assertEquals(expected.eof, actual.eof)
            }
            expected is WriteSessionDto && actual is WriteSessionDto -> {
                assertEquals(expected.uri, actual.uri)
                assertNotNull(actual.descriptor)
                actual.descriptor.close()
            }
            else -> assertEquals(expected, actual)
        }
    }

    private fun sampleProfileApp() = ProfileAppEntry(
        packageName = "pkg",
        uid = 1,
        flags = 2,
        hidden = true,
        enabled = false,
        targetSdkVersion = 35,
        label = "Label",
        iconResource = 3,
        sourceDir = "/base.apk",
        publicSourceDir = "/base.apk",
        splitSourceDirs = listOf("/split.apk"),
    )
}

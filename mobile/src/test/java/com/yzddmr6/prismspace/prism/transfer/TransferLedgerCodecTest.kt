package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLedgerCodecTest {

    private var ids = 0
    private val newId: () -> String = { "gen-${ids++}" }

    private fun file(id: String, time: Long = 0L, uri: String? = "content://media/external/downloads/$id") = TransferLedgerRecord(
        id = id,
        displayName = "$id.pdf",
        mime = "application/pdf",
        sizeBytes = 10L,
        contentUri = uri,
        relativePath = "Download/PrismSpace",
        direction = TransferDirection.ToProfile,
        role = TransferRole.Received,
        kind = TransferKind.File,
        packageName = null,
        apkUris = emptyList(),
        timeMillis = time,
        legacy = false,
    )

    private fun suite(id: String, pkg: String, role: TransferRole = TransferRole.Received, uris: List<String> = listOf("u-$id")) =
        file(id).copy(kind = TransferKind.ApkSuite, packageName = pkg, role = role, apkUris = uris, mime = null)

    @Test fun legacyFileRowIsMarkedLegacyWithUnknownUriAndKnownFolder() {
        val record = TransferLedgerCodec.decode(
            mapOf("name" to "photo.jpg", "location" to "Pictures/PrismSpace", "isImage" to true, "time" to 42L, "direction" to "toProfile"),
            currentIsParent = false,
            newId,
        )

        assertTrue(record.legacy)
        assertNull(record.contentUri)
        assertNull(record.mime)
        assertNull(record.sizeBytes)
        assertEquals("gen-0", record.id)
        assertEquals("Pictures/PrismSpace", record.relativePath)
        assertEquals(TransferRole.Received, record.role)
        assertEquals(TransferKind.File, record.kind)
        assertEquals(TransferDirection.ToProfile, record.direction)
        assertTrue("legacy icon falls back to isImage", record.isImage)
        assertEquals(42L, record.timeMillis)
    }

    @Test fun legacyRowWithPackageIsAnApkSuiteSentFromMainAndReceivedInDual() {
        val fields = mapOf<String, Any?>("name" to "Via", "pkg" to "mark.via", "location" to "克隆到双开空间", "time" to 1L)

        val main = TransferLedgerCodec.decode(fields, currentIsParent = true, newId)
        val dual = TransferLedgerCodec.decode(fields, currentIsParent = false, newId)

        assertEquals(TransferKind.ApkSuite, main.kind)
        assertEquals(TransferRole.Sent, main.role)
        assertEquals(TransferRole.Received, dual.role)
        assertEquals("Download/PrismSpace", main.relativePath)
        assertTrue(main.apkUris.isEmpty())
    }

    @Test fun legacyLocationsOutsideTheWhitelistBecomeUnknown() {
        listOf("Documents/Work", "下载/PrismSpace", "").forEach { location ->
            val record = TransferLedgerCodec.decode(mapOf("name" to "a", "location" to location), false, newId)
            assertNull("$location must not be trusted as a folder", record.relativePath)
            assertNull(record.direction)
        }
    }

    @Test fun v2RoundTripKeepsEveryField() {
        val record = suite("s1", "pkg", TransferRole.Sent, listOf("a", "b")).copy(hidden = true, sizeBytes = 99L, mime = "application/vnd.android.package-archive")

        val decoded = TransferLedgerCodec.decode(TransferLedgerCodec.encode(record), currentIsParent = true, newId)

        assertEquals(record, decoded)
        assertFalse(TransferLedgerCodec.needsMigration(TransferLedgerCodec.encode(record)))
    }

    @Test fun newRowsStillWriteTheLegacyFieldsForRollback() {
        val fields = TransferLedgerCodec.encode(file("f1", time = 7L))

        assertEquals("f1.pdf", fields["name"])
        assertEquals("Download/PrismSpace", fields["location"])
        assertEquals(false, fields["isImage"])
        assertEquals(7L, fields["time"])
        assertEquals("toProfile", fields["direction"])
        assertEquals(2, fields["v"])
    }

    @Test fun corruptEntriesAreSkippedAndLegacyRowsCountedForMigration() {
        val decoded = TransferLedgerCodec.decodeAll(
            listOf(null, "garbage", mapOf("name" to "old"), TransferLedgerCodec.encode(file("new"))),
            currentIsParent = true,
            newId,
        )

        assertEquals(2, decoded.records.size)
        assertEquals(1, decoded.migrated)
        assertEquals(2, decoded.dropped)
    }

    @Test fun fiftyFileCapNeverEvictsApkSuites() {
        val suiteRow = suite("suite", "pkg")
        val files = (0 until 60).map { file("f$it") }

        val kept = TransferLedgerCodec.retain(files.take(30) + suiteRow + files.drop(30))

        assertEquals(TransferLedgerCodec.MAX_VISIBLE_FILES, kept.count { it.kind == TransferKind.File })
        assertTrue(suiteRow in kept)
        assertEquals("f0", kept.first().id)
    }

    @Test fun upsertReplacesTheSamePackageAndRoleOnly() {
        val old = suite("old", "pkg")
        val other = suite("other", "other.pkg")
        val sentElsewhere = suite("sent", "pkg", TransferRole.Sent)
        val fresh = suite("fresh", "pkg")

        val result = TransferLedgerCodec.upsertApkSuite(listOf(old, other, sentElsewhere), fresh)

        assertEquals(listOf("fresh", "other", "sent"), result.map { it.id })
    }

    @Test fun clearHidesTheNewestSuitePerPackageAndDropsFiles() {
        val result = TransferLedgerCodec.clear(listOf(file("f1"), suite("s-new", "pkg"), suite("s-old", "pkg"), file("f2")))

        assertEquals(listOf("s-new"), result.records.map { it.id })
        assertTrue(result.records.single().hidden)
        assertTrue(TransferLedgerCodec.visible(result.records).isEmpty())
        assertEquals(4, result.visibleCleared)
        assertEquals(1, result.keptSuiteIndex)
        assertEquals("s-new", TransferLedgerCodec.apkSuite(result.records, "pkg")?.id)
    }

    @Test fun removeByTransferIdDropsFilesButOnlyHidesSuites() {
        val records = listOf(file("t1"), suite("s1", "pkg"))

        val (afterFile, removedFile) = TransferLedgerCodec.remove(records, "t1")
        val (afterSuite, removedSuite) = TransferLedgerCodec.remove(records, "s1")
        val (afterAbort, _) = TransferLedgerCodec.remove(records, "s1", hideSuites = false)
        val (_, missing) = TransferLedgerCodec.remove(records, "nope")

        assertTrue(removedFile)
        assertEquals(listOf("s1"), afterFile.map { it.id })
        assertTrue(removedSuite)
        assertTrue(afterSuite.single { it.id == "s1" }.hidden)
        assertEquals(listOf("t1"), afterAbort.map { it.id })
        assertFalse(missing)
    }

    @Test fun insertMovesAnExistingIdToTheTop() {
        val result = TransferLedgerCodec.insert(listOf(file("a"), file("b")), file("b", time = 9L))

        assertEquals(listOf("b", "a"), result.map { it.id })
        assertEquals(9L, result.first().timeMillis)
    }
}

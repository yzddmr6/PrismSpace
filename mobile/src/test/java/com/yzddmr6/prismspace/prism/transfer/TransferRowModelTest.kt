package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.compose.vm.testZhResolver
import com.yzddmr6.prismspace.prism.service.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRowModelTest {

    private val time: (Long) -> String = { if (it > 0) "10-07 12:00" else "" }

    private fun record(
        role: TransferRole,
        direction: TransferDirection?,
        kind: TransferKind = TransferKind.File,
        uri: String? = "content://media/external/downloads/1",
        legacy: Boolean = false,
        rel: String? = "Download/PrismSpace",
    ) = TransferLedgerRecord(
        id = "id",
        displayName = if (kind == TransferKind.ApkSuite) "Via" else "report.pdf",
        mime = if (legacy) null else "application/pdf",
        sizeBytes = null,
        contentUri = uri,
        relativePath = rel,
        direction = direction,
        role = role,
        kind = kind,
        packageName = if (kind == TransferKind.ApkSuite) "mark.via" else null,
        apkUris = emptyList(),
        timeMillis = 1L,
        legacy = legacy,
    )

    @Test fun sentRowsNameTheSpaceHoldingTheFile() {
        val model = transferRowModel(record(TransferRole.Sent, TransferDirection.ToProfile), testZhResolver, currentIsParent = true, time)

        assertEquals("已发送 · 双开空间 · Download/PrismSpace · 10-07 12:00", model.summary)
        assertEquals("report.pdf", model.title)
        assertEquals(RowActionKind.OpenFolder, model.primary?.kind)
        assertEquals("打开所在文件夹", model.primary?.label)
        assertEquals(RowActionKind.OpenFile, model.secondary?.kind)
        assertTrue(model.secondary!!.enabled)
        assertTrue(model.canRemove)
    }

    @Test fun receivedRowsNameTheSpaceTheyCameFrom() {
        val fromMain = transferRowModel(record(TransferRole.Received, TransferDirection.ToProfile), testZhResolver, false, time)
        val fromDual = transferRowModel(record(TransferRole.Received, TransferDirection.ToMain), testZhResolver, true, time)

        assertTrue(fromMain.summary.startsWith("已接收 · 来自主空间"))
        assertTrue(fromDual.summary.startsWith("已接收 · 来自双开空间"))
    }

    @Test fun legacyRowsKeepTheFolderButExplainWhyTheFileCannotOpen() {
        val model = transferRowModel(
            record(TransferRole.Received, null, uri = null, legacy = true, rel = null),
            testZhResolver,
            true,
            time,
        )

        assertEquals("已接收 · 10-07 12:00", model.summary)
        assertTrue(model.primary!!.enabled)
        assertFalse(model.secondary!!.enabled)
        assertEquals("此记录来自旧版本，未保存文件位置。", model.secondary!!.disabledReason)
    }

    @Test fun apkSuitesKeepTheirInstallEntry() {
        val main = transferRowModel(record(TransferRole.Sent, TransferDirection.ToProfile, TransferKind.ApkSuite), testZhResolver, true, time)
        val dual = transferRowModel(record(TransferRole.Received, TransferDirection.ToProfile, TransferKind.ApkSuite), testZhResolver, false, time)

        assertEquals("Via-mark.via", main.title)
        assertTrue(main.summary.startsWith("APK 套件 · 已发送 · 双开空间"))
        assertEquals(RowActionKind.ContinueInstall, main.primary?.kind)
        assertEquals(RowActionKind.Install, dual.primary?.kind)
        assertNull(main.secondary)
        assertEquals(RowIcon.ApkSuite, main.icon)
    }

    @Test fun unknownTimeIsLeftOut() {
        assertEquals("", formatTransferTime(0L))
        assertEquals("", formatTransferTime(-1L))
    }
}

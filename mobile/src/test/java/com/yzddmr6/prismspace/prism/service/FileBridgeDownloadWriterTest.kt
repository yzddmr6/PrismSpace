package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FileBridgeDownloadWriterTest {

    @Test fun insertsFixedSelfTestFileWithoutPreDeleting() {
        val store = RecordingDownloadStore()

        val uri = FileBridgeDownloadWriter(store).write(
            displayName = "prismspace-bridge-main.txt",
            mimeType = "text/plain",
            bytes = "payload".toByteArray(),
        )

        assertEquals("content://downloads/1", uri)
        // No pre-delete: the writer relies on MediaStore duplicate-safe naming.
        assertEquals(
            listOf("insert:prismspace-bridge-main.txt:text/plain:7:Download/PrismSpace/"),
            store.calls,
        )
    }

    @Test fun writesDownloadToCallerProvidedRelativePath() {
        val store = RecordingDownloadStore()

        FileBridgeDownloadWriter(store).write(
            displayName = "doc.txt",
            mimeType = "text/plain",
            bytes = "payload".toByteArray(),
            relativePath = "Download/PrismSpace/",
        )

        assertEquals(
            listOf("insert:doc.txt:text/plain:7:Download/PrismSpace/"),
            store.calls,
        )
    }

    private class RecordingDownloadStore : FileBridgeDownloadStore {
        val calls = mutableListOf<String>()

        override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
            calls += "insert:$displayName:$mimeType:${bytes.size}:$relativePath"
            return "content://downloads/1"
        }
    }
}

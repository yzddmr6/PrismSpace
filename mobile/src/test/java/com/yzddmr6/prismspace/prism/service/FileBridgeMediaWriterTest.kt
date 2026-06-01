package com.yzddmr6.prismspace.prism.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FileBridgeMediaWriterTest {

    @Test fun insertsSharedMediaWithoutPreDeleting() {
        val store = RecordingMediaStore()

        val uri = FileBridgeMediaWriter(store).write(
            displayName = "photo.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3),
        )

        assertEquals("content://images/1", uri)
        // No pre-delete: the writer relies on MediaStore duplicate-safe naming.
        assertEquals(
            listOf("insert:photo.png:image/png:3:Pictures/PrismSpace/"),
            store.calls,
        )
    }

    @Test fun writesSharedMediaToCallerProvidedRelativePath() {
        val store = RecordingMediaStore()

        FileBridgeMediaWriter(store).write(
            displayName = "photo.png",
            mimeType = "image/png",
            bytes = byteArrayOf(1, 2, 3),
            relativePath = "Pictures/PrismSpace/",
        )

        assertEquals(
            listOf("insert:photo.png:image/png:3:Pictures/PrismSpace/"),
            store.calls,
        )
    }

    private class RecordingMediaStore : FileBridgeMediaStore {
        val calls = mutableListOf<String>()

        override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
            calls += "insert:$displayName:$mimeType:${bytes.size}:$relativePath"
            return "content://images/1"
        }
    }
}

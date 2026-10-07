package com.yzddmr6.prismspace.prism.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePublisherTest {

    private class FakeRows(private val failOutput: Boolean = false) : MediaRows {
        val rows = linkedMapOf<String, Boolean>() // uri -> pending
        val names = mutableMapOf<String, String>()
        val batches = mutableListOf<Pair<List<String>, List<Pair<String, String>>>>()
        val written = mutableMapOf<String, ByteArrayOutputStream>()
        private var next = 0

        override fun insert(collection: MediaCollection, displayName: String, mime: String, relativePath: String, pending: Boolean): String {
            val uri = "content://media/${collection.name}/${next++}"
            rows[uri] = pending
            names[uri] = displayName
            return uri
        }

        override fun openOutput(uri: String): OutputStream? {
            if (failOutput) return object : OutputStream() { override fun write(b: Int) = throw IOException("full") }
            return ByteArrayOutputStream().also { written[uri] = it }
        }

        override fun setPending(uri: String, pending: Boolean) { rows[uri] = pending }
        override fun delete(uri: String) { rows.remove(uri) }

        override fun applyBatch(deletes: List<String>, renames: List<Pair<String, String>>) {
            batches += deletes to renames
            deletes.forEach(rows::remove)
            renames.forEach { (uri, name) -> rows[uri] = false; names[uri] = name }
        }
    }

    @Test fun writeBytesPublishesTheRow() {
        val rows = FakeRows()
        val uri = MediaStorePublisher(rows).writeBytes(MediaCollection.Downloads, "a.txt", "text/plain", "Download/PrismSpace/", byteArrayOf(1, 2))

        assertEquals(false, rows.rows[uri])
        assertEquals(listOf<Byte>(1, 2), rows.written.getValue(uri).toByteArray().toList())
    }

    @Test fun failedWriteAbortsTheRow() {
        val rows = FakeRows(failOutput = true)

        val failure = runCatching {
            MediaStorePublisher(rows).stage(MediaCollection.Downloads, "a", "text/plain", "Download/PrismSpace/", ByteArrayInputStream(byteArrayOf(1)))
        }

        assertTrue(failure.isFailure)
        assertTrue("no pending row is left behind", rows.rows.isEmpty())
    }

    @Test fun failedSourceAbortsTheRow() {
        val rows = FakeRows()
        val broken = object : InputStream() { override fun read(): Int = throw IOException("revoked") }

        runCatching { MediaStorePublisher(rows).stage(MediaCollection.Images, "a.png", "image/png", "Pictures/PrismSpace/", broken) }

        assertTrue(rows.rows.isEmpty())
    }

    @Test fun openPendingRemovesTheRowWhenOpeningFails() {
        val rows = FakeRows()

        runCatching { MediaStorePublisher(rows).openPending(MediaCollection.Downloads, "a", "x/y", "Download/PrismSpace/") { null as String? } }

        assertTrue(rows.rows.isEmpty())
    }

    @Test fun publishClearsPendingAndAbortDeletes() {
        val rows = FakeRows()
        val publisher = MediaStorePublisher(rows)
        val first = publisher.openPending(MediaCollection.Downloads, "a", "x/y", "Download/PrismSpace/") { "handle" }
        val second = publisher.openPending(MediaCollection.Downloads, "b", "x/y", "Download/PrismSpace/") { "handle" }

        assertEquals(true, rows.rows[first.uri])
        publisher.publish(first.uri)
        publisher.abort(second.uri)

        assertEquals(mapOf(first.uri to false), rows.rows)
    }

    @Test fun suiteIsPublishedInOneBatch() {
        val rows = FakeRows()
        MediaStorePublisher(rows).publishSuite(listOf("old", "old"), listOf("new-0" to "pkg.apk", "new-1" to "pkg.split1.apk"))

        assertEquals(1, rows.batches.size)
        assertEquals(listOf("old"), rows.batches.single().first)
        assertEquals(listOf("pkg.apk", "pkg.split1.apk"), rows.batches.single().second.map { it.second })
    }
}

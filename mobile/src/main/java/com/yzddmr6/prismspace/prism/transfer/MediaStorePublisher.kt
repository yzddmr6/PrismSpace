package com.yzddmr6.prismspace.prism.transfer

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

internal enum class MediaCollection { Downloads, Images }

internal data class PendingRow<T>(val uri: String, val handle: T)

/** Raw MediaStore row operations; the Android implementation wraps ContentResolver, tests fake it. */
internal interface MediaRows {
    fun insert(collection: MediaCollection, displayName: String, mime: String, relativePath: String, pending: Boolean): String?
    fun openOutput(uri: String): OutputStream?
    fun setPending(uri: String, pending: Boolean)
    fun delete(uri: String)
    /** One provider transaction: delete [deletes], rename each staged row and clear its pending flag. */
    fun applyBatch(deletes: List<String>, renames: List<Pair<String, String>>)
}

/**
 * The single "insert pending → write → publish / abort" implementation for every MediaStore write
 * PrismSpace makes: cross-space write sessions, APK suites and the bridge self-test.
 */
internal class MediaStorePublisher(private val rows: MediaRows) {

    /** Inserts a pending row and opens it with [open]; the row is removed again when opening fails. */
    fun <T> openPending(
        collection: MediaCollection,
        displayName: String,
        mime: String,
        relativePath: String,
        open: (String) -> T?,
    ): PendingRow<T> {
        val uri = rows.insert(collection, displayName, mime, relativePath, pending = true)
            ?: error("Unable to create MediaStore entry")
        val handle = try {
            open(uri) ?: error("Unable to open MediaStore output")
        } catch (error: Throwable) {
            runCatching { rows.delete(uri) }
            throw error
        }
        return PendingRow(uri, handle)
    }

    /** Clears IS_PENDING; the row becomes visible to other apps. */
    fun publish(uri: String): String {
        rows.setPending(uri, pending = false)
        return uri
    }

    fun abort(uri: String) = rows.delete(uri)

    /** Inserts a pending row and streams [input] into it; failures delete the row. */
    fun stage(collection: MediaCollection, displayName: String, mime: String, relativePath: String, input: InputStream): String {
        val row = openPending(collection, displayName, mime, relativePath) { rows.openOutput(it) }
        try {
            row.handle.use { output -> input.copyTo(output, BUFFER_SIZE) }
        } catch (error: Throwable) {
            runCatching { rows.delete(row.uri) }
            throw error
        }
        return row.uri
    }

    /** Deletes [previous] and publishes every staged (uri, canonical name) row in one batch. */
    fun publishSuite(previous: List<String>, staged: List<Pair<String, String>>) =
        rows.applyBatch(previous.distinct(), staged)

    /** Self-test only: small in-memory payloads. */
    fun writeBytes(collection: MediaCollection, displayName: String, mime: String, relativePath: String, bytes: ByteArray): String {
        val uri = stage(collection, displayName, mime, relativePath, bytes.inputStream())
        return try {
            publish(uri)
        } catch (error: Throwable) {
            runCatching { rows.delete(uri) }
            throw error
        }
    }

    private companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

internal class AndroidMediaRows(context: Context) : MediaRows {
    private val resolver = context.contentResolver

    override fun insert(
        collection: MediaCollection,
        displayName: String,
        mime: String,
        relativePath: String,
        pending: Boolean,
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return resolver.insert(collection.contentUri(), values)?.toString()
    }

    override fun openOutput(uri: String): OutputStream? = resolver.openOutputStream(Uri.parse(uri))

    fun openDescriptor(uri: String): ParcelFileDescriptor? = resolver.openFileDescriptor(Uri.parse(uri), "w")

    override fun setPending(uri: String, pending: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        resolver.update(Uri.parse(uri), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }, null, null)
    }

    override fun delete(uri: String) {
        resolver.delete(Uri.parse(uri), null, null)
    }

    override fun applyBatch(deletes: List<String>, renames: List<Pair<String, String>>) {
        val operations = ArrayList<ContentProviderOperation>(deletes.size + renames.size)
        deletes.forEach { operations += ContentProviderOperation.newDelete(Uri.parse(it)).build() }
        renames.forEach { (uri, name) ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            operations += ContentProviderOperation.newUpdate(Uri.parse(uri)).withValues(values).build()
        }
        resolver.applyBatch(MediaStore.AUTHORITY, operations)
    }

    private fun MediaCollection.contentUri(): Uri = when (this) {
        MediaCollection.Downloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        MediaCollection.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}

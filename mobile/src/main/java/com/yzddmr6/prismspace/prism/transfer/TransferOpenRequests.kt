package com.yzddmr6.prismspace.prism.transfer

import android.app.Activity
import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog

/** Storage for the single pending-request slot; the Android version is a dedicated SharedPreferences file. */
internal interface OpenRequestSlot {
    fun read(): Map<String, String?>?
    fun write(fields: Map<String, String?>?)
}

internal sealed interface TakenRequest {
    data class Fresh(val request: TransferOpenRequest, val ageMs: Long) : TakenRequest
    data class Expired(val recordId: String?, val ageMs: Long) : TakenRequest
    data object Empty : TakenRequest
}

/** One slot, consumed once, valid for [ttlMs]; a newer request overwrites an older one. */
internal class TransferOpenRequestQueue(
    private val slot: OpenRequestSlot,
    private val now: () -> Long,
    private val ttlMs: Long = TTL_MS,
) {
    fun put(request: TransferOpenRequest) {
        slot.write(
            mapOf(
                KEY_ID to request.recordId,
                KEY_MODE to request.mode.name,
                KEY_URI to request.contentUri,
                KEY_MIME to request.mime,
                KEY_REL to request.relativePath,
                KEY_AT to now().toString(),
            ),
        )
    }

    fun take(): TakenRequest {
        val fields = slot.read() ?: return TakenRequest.Empty
        slot.write(null)
        val recordId = fields[KEY_ID]
        val at = fields[KEY_AT]?.toLongOrNull()
        val mode = fields[KEY_MODE]?.let { name -> OpenMode.entries.firstOrNull { it.name == name } }
        val age = at?.let { now() - it } ?: Long.MAX_VALUE
        if (recordId == null || mode == null || age < 0 || age > ttlMs) return TakenRequest.Expired(recordId, age)
        return TakenRequest.Fresh(
            TransferOpenRequest(recordId, mode, fields[KEY_URI], fields[KEY_MIME], fields[KEY_REL]),
            age,
        )
    }

    internal companion object {
        const val TTL_MS = 30_000L
        private const val KEY_ID = "id"
        private const val KEY_MODE = "mode"
        private const val KEY_URI = "uri"
        private const val KEY_MIME = "mime"
        private const val KEY_REL = "rel"
        private const val KEY_AT = "at"
        val KEYS = listOf(KEY_ID, KEY_MODE, KEY_URI, KEY_MIME, KEY_REL, KEY_AT)
    }
}

/**
 * Pending "open this file" requests delivered over the bridge into the owning user. The bridge
 * handler only parks the request (background activity starts are blocked); the owner's entry
 * screen drains it from its foreground onResume.
 */
object TransferOpenRequests {
    private const val TAG = "Prism.TransferOpen"
    private const val PREFS = "prism_transfer_open_requests"
    private val lock = Any()

    internal fun queue(context: Context, request: TransferOpenRequest) {
        synchronized(lock) { queueOf(context).put(request) }
        DiagnosticLog.i(TAG, "open.pending queued id=${request.recordId} mode=${request.mode}")
    }

    /** Consumes a fresh pending request, if any, and opens it from this foreground [activity]. */
    @JvmStatic
    fun drain(activity: Activity) {
        val appContext = activity.applicationContext
        Thread({
            val taken = synchronized(lock) { queueOf(appContext).take() }
            when (taken) {
                TakenRequest.Empty -> Unit
                is TakenRequest.Expired ->
                    DiagnosticLog.w(TAG, "open.pending expired id=${taken.recordId ?: "-"} ageMs=${taken.ageMs}")
                is TakenRequest.Fresh -> {
                    DiagnosticLog.i(TAG, "open.pending drained id=${taken.request.recordId} ageMs=${taken.ageMs}")
                    TransferOpener.openLocal(activity, taken.request)
                }
            }
        }, "Prism-transfer-open-drain").start()
    }

    private fun queueOf(context: Context) = TransferOpenRequestQueue(PrefsSlot(context), System::currentTimeMillis)

    private class PrefsSlot(context: Context) : OpenRequestSlot {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun read(): Map<String, String?>? {
            if (!prefs.contains("id")) return null
            return TransferOpenRequestQueue.KEYS.associateWith { prefs.getString(it, null) }
        }

        override fun write(fields: Map<String, String?>?) {
            val editor = prefs.edit().clear()
            fields?.forEach { (key, value) -> if (value != null) editor.putString(key, value) }
            // commit(): the bridge handler's process may be torn down right after it returns.
            editor.commit()
        }
    }
}

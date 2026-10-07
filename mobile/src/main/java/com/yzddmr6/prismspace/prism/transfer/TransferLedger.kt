package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import android.os.Process
import android.preference.PreferenceManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeTransferDirection
import com.yzddmr6.prismspace.bridge.BridgeTransferRole
import com.yzddmr6.prismspace.bridge.TransferLedgerDto
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-user transfer ledger in this user's default (credential-protected) SharedPreferences. Same key
 * and JSON-array shape as the former TransferHistoryStore, newest first.
 *
 * Writes use commit(), not apply(): rows are also written inside bridge command handlers, whose
 * process can be torn down right after the command returns. Every read-modify-write runs under one
 * in-process lock because the bridge thread and the UI thread write concurrently.
 */
object TransferLedger {

    private const val KEY = "prism_transfer_history"
    private const val TAG = "Prism.TransferLedger"
    private val lock = Any()

    /** Visible rows, newest first. */
    fun load(context: Context): List<TransferLedgerRecord> = TransferLedgerCodec.visible(readAll(context))

    /** Every APK-suite row including hidden index rows (the canonical source for pending installs). */
    fun apkSuites(context: Context): List<TransferLedgerRecord> =
        readAll(context).filter { it.kind == TransferKind.ApkSuite }

    fun record(context: Context, record: TransferLedgerRecord) {
        mutate(context) { TransferLedgerCodec.insert(it, record) }
        log(record)
    }

    fun upsertApkSuite(context: Context, record: TransferLedgerRecord) {
        mutate(context) { TransferLedgerCodec.upsertApkSuite(it, record) }
        log(record)
    }

    /** Includes hidden rows. */
    fun apkSuite(context: Context, packageName: String): TransferLedgerRecord? =
        TransferLedgerCodec.apkSuite(readAll(context), packageName)

    /** Removes a row; never touches the file. */
    fun remove(context: Context, id: String, reason: String = "user"): Boolean {
        var removed = false
        mutate(context) { records ->
            TransferLedgerCodec.remove(records, id, hideSuites = reason == "user").also { removed = it.second }.first
        }
        DiagnosticLog.i(TAG, "ledger.remove id=$id reason=$reason removed=$removed")
        return removed
    }

    /** Clears the visible list; files stay. The newest suite row per package survives hidden. */
    fun clear(context: Context) {
        var result: TransferLedgerCodec.ClearResult? = null
        mutate(context) { records -> TransferLedgerCodec.clear(records).also { result = it }.records }
        DiagnosticLog.i(TAG, "ledger.clear visible=${result?.visibleCleared} keptSuiteIndex=${result?.keptSuiteIndex}")
    }

    private fun log(record: TransferLedgerRecord) {
        DiagnosticLog.i(
            TAG,
            "ledger.record role=${record.role} kind=${record.kind} id=${record.id} user=${Process.myUserHandle().toId()}",
        )
    }

    private fun readAll(context: Context): List<TransferLedgerRecord> = synchronized(lock) { readLocked(context) }

    private fun mutate(context: Context, change: (List<TransferLedgerRecord>) -> List<TransferLedgerRecord>) {
        synchronized(lock) { writeLocked(context, change(readLocked(context))) }
    }

    private fun readLocked(context: Context): List<TransferLedgerRecord> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val entries = (0 until array.length()).map { index -> array.optJSONObject(index)?.toFieldMap() }
        val decoded = TransferLedgerCodec.decodeAll(entries, currentIsParent()) { UUID.randomUUID().toString() }
        if (decoded.migrated == 0 && decoded.dropped == 0) return decoded.records
        // Persist the generated ids once so "remove record" keeps addressing the same row.
        val migrated = TransferLedgerCodec.retain(decoded.records)
        writeLocked(context, migrated)
        DiagnosticLog.i(TAG, "ledger.migrate legacy=${decoded.migrated} dropped=${decoded.dropped}")
        return migrated
    }

    private fun writeLocked(context: Context, records: List<TransferLedgerRecord>) {
        val array = JSONArray()
        records.forEach { array.put(TransferLedgerCodec.encode(it).toJson()) }
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun currentIsParent(): Boolean =
        runCatching { Users.isParentProfile() }.getOrElse { Process.myUserHandle().toId() == 0 }

    private fun JSONObject.toFieldMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = opt(key)) {
            JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            else -> value
        }
    }

    private fun Map<String, Any?>.toJson(): JSONObject = JSONObject().also { json ->
        forEach { (key, value) ->
            when (value) {
                null -> Unit
                is List<*> -> json.put(key, JSONArray().also { array -> value.forEach(array::put) })
                else -> json.put(key, value)
            }
        }
    }
}

/** Ledger row for the receiving or the sending half of a file transfer. */
internal fun TransferLedgerDto.toLedgerRecord(contentUri: String, now: Long = System.currentTimeMillis()) =
    TransferLedgerRecord(
        id = transferId,
        displayName = displayName,
        mime = mime,
        sizeBytes = sizeBytes,
        contentUri = contentUri,
        relativePath = relativePath,
        direction = direction?.toTransferDirection(),
        role = when (role) {
            BridgeTransferRole.Sent -> TransferRole.Sent
            BridgeTransferRole.Received -> TransferRole.Received
        },
        kind = TransferKind.File,
        packageName = null,
        apkUris = emptyList(),
        timeMillis = now,
        legacy = false,
    )

internal fun BridgeTransferDirection.toTransferDirection(): TransferDirection = when (this) {
    BridgeTransferDirection.ToMain -> TransferDirection.ToMain
    BridgeTransferDirection.ToProfile -> TransferDirection.ToProfile
}

internal fun TransferDirection.toBridgeDirection(): BridgeTransferDirection = when (this) {
    TransferDirection.ToMain -> BridgeTransferDirection.ToMain
    TransferDirection.ToProfile -> BridgeTransferDirection.ToProfile
}

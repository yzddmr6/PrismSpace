package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.prism.service.TransferDirection

/**
 * Pure ledger encoding, legacy migration and retention. Input/output are plain maps so the JVM tests
 * do not depend on android.jar's stubbed org.json; [TransferLedger] owns the thin JSON adapter.
 *
 * New rows keep writing the legacy fields (`name / pkg / location / isImage / time / direction`) so a
 * rolled-back build still lists them.
 */
internal object TransferLedgerCodec {
    const val VERSION = 2
    const val MAX_VISIBLE_FILES = 50

    internal data class Decoded(val records: List<TransferLedgerRecord>, val migrated: Int, val dropped: Int)

    internal data class ClearResult(val records: List<TransferLedgerRecord>, val visibleCleared: Int, val keptSuiteIndex: Int)

    fun decodeAll(entries: List<Any?>, currentIsParent: Boolean, newId: () -> String): Decoded {
        var migrated = 0
        var dropped = 0
        val records = entries.mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            val fields = entry as? Map<String, Any?>
            if (fields == null) {
                dropped++
                return@mapNotNull null
            }
            if (needsMigration(fields)) migrated++
            decode(fields, currentIsParent, newId)
        }
        return Decoded(records, migrated, dropped)
    }

    /** True when the stored row lacks the v2 marker or a stable id and must be written back once. */
    fun needsMigration(fields: Map<String, Any?>): Boolean =
        fields["v"] == null || (fields["id"] as? String).isNullOrBlank()

    fun decode(fields: Map<String, Any?>, currentIsParent: Boolean, newId: () -> String): TransferLedgerRecord {
        val packageName = (fields["pkg"] as? String)?.takeIf { it.isNotBlank() }
        val id = (fields["id"] as? String)?.takeIf { it.isNotBlank() } ?: newId()
        val name = (fields["name"] as? String) ?: "file"
        val time = (fields["time"] as? Number)?.toLong() ?: 0L
        val direction = TransferDirection.fromWireValue(fields["direction"] as? String)
        val legacyIsImage = fields["isImage"] as? Boolean ?: false
        val version = (fields["v"] as? Number)?.toInt()
        if (version == null) {
            val kind = if (packageName != null) TransferKind.ApkSuite else TransferKind.File
            // Before v2 only the main-space clone flow wrote an outgoing row (with a package); every
            // other legacy row — including old "save here" / "save as" rows — landed in this user.
            val role = if (packageName != null && currentIsParent) TransferRole.Sent else TransferRole.Received
            val location = fields["location"] as? String
            val relativePath = when {
                location != null && location in TransferPaths.knownLocations -> location
                kind == TransferKind.ApkSuite -> TransferPaths.DOWNLOAD_LOCATION
                else -> null
            }
            return TransferLedgerRecord(
                id = id,
                displayName = name,
                mime = null,
                sizeBytes = null,
                contentUri = null,
                relativePath = relativePath,
                direction = direction,
                role = role,
                kind = kind,
                packageName = packageName,
                apkUris = emptyList(),
                timeMillis = time,
                legacy = true,
                isImage = legacyIsImage,
            )
        }
        val mime = (fields["mime"] as? String)?.takeIf { it.isNotBlank() }
        val kind = TransferKind.fromWire(fields["kind"] as? String)
            ?: if (packageName != null) TransferKind.ApkSuite else TransferKind.File
        return TransferLedgerRecord(
            id = id,
            displayName = name,
            mime = mime,
            sizeBytes = (fields["size"] as? Number)?.toLong()?.takeIf { it >= 0L },
            contentUri = (fields["uri"] as? String)?.takeIf { it.isNotBlank() },
            relativePath = (fields["rel"] as? String)?.takeIf { it.isNotBlank() },
            direction = direction,
            role = TransferRole.fromWire(fields["role"] as? String) ?: TransferRole.Received,
            kind = kind,
            packageName = packageName,
            apkUris = (fields["apk"] as? List<*>).orEmpty().filterIsInstance<String>(),
            timeMillis = time,
            legacy = false,
            hidden = fields["hidden"] as? Boolean ?: false,
            isImage = if (mime != null) TransferPaths.isImage(mime) else legacyIsImage,
        )
    }

    fun encode(record: TransferLedgerRecord): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        // Legacy fields first: an older build's load() reads exactly these.
        put("name", record.displayName)
        record.packageName?.takeIf { it.isNotBlank() }?.let { put("pkg", it) }
        put("location", record.relativePath.orEmpty())
        put("isImage", record.isImage)
        put("time", record.timeMillis)
        record.direction?.let { put("direction", it.wireValue) }
        put("v", VERSION)
        put("id", record.id)
        record.mime?.let { put("mime", it) }
        record.sizeBytes?.let { put("size", it) }
        record.contentUri?.let { put("uri", it) }
        record.relativePath?.let { put("rel", it) }
        put("role", record.role.wire)
        put("kind", record.kind.wire)
        if (record.apkUris.isNotEmpty()) put("apk", record.apkUris)
        if (record.hidden) put("hidden", true)
    }

    /**
     * Keeps at most [maxFiles] visible file rows and exactly the newest APK-suite row per
     * (package, role). Suites never consume the file quota: an evicted suite would leave its next
     * atomic replacement without the previous URIs. [records] are newest first.
     */
    fun retain(records: List<TransferLedgerRecord>, maxFiles: Int = MAX_VISIBLE_FILES): List<TransferLedgerRecord> {
        var files = 0
        val suites = HashSet<Pair<String?, TransferRole>>()
        return records.filter { record ->
            when (record.kind) {
                TransferKind.File -> !record.hidden && files++ < maxFiles
                TransferKind.ApkSuite -> suites.add(record.packageName to record.role)
            }
        }
    }

    fun insert(records: List<TransferLedgerRecord>, record: TransferLedgerRecord): List<TransferLedgerRecord> =
        retain(listOf(record) + records.filterNot { it.id == record.id })

    /** Replaces the previous suite row of the same package and role. */
    fun upsertApkSuite(records: List<TransferLedgerRecord>, record: TransferLedgerRecord): List<TransferLedgerRecord> {
        require(record.kind == TransferKind.ApkSuite) { "Only APK suites are upserted" }
        return retain(listOf(record) + records.filterNot {
            it.id == record.id ||
                (it.kind == TransferKind.ApkSuite && it.packageName == record.packageName && it.role == record.role)
        })
    }

    /**
     * Removes a row (never the file). An APK-suite row is only hidden so the next clone can still
     * replace that suite atomically; [hideSuites] = false removes it for real.
     */
    fun remove(
        records: List<TransferLedgerRecord>,
        id: String,
        hideSuites: Boolean = true,
    ): Pair<List<TransferLedgerRecord>, Boolean> {
        val target = records.firstOrNull { it.id == id } ?: return records to false
        val updated = if (hideSuites && target.kind == TransferKind.ApkSuite) {
            records.map { if (it.id == id) it.copy(hidden = true) else it }
        } else {
            records.filterNot { it.id == id }
        }
        return updated to true
    }

    /** Clears every visible row; the newest suite row per package survives as a hidden index row. */
    fun clear(records: List<TransferLedgerRecord>): ClearResult {
        val visible = records.count { !it.hidden }
        val kept = retain(records.filter { it.kind == TransferKind.ApkSuite }.map { it.copy(hidden = true) })
        return ClearResult(kept, visible, kept.size)
    }

    fun visible(records: List<TransferLedgerRecord>): List<TransferLedgerRecord> = records.filterNot { it.hidden }

    fun apkSuite(records: List<TransferLedgerRecord>, packageName: String): TransferLedgerRecord? =
        records.firstOrNull { it.kind == TransferKind.ApkSuite && it.packageName == packageName }
}

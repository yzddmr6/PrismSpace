package com.yzddmr6.prismspace.prism.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.prism.service.FileTransferPolicy
import java.io.FileNotFoundException

/**
 * Which user owns a received URI. The target space is always "the space that does not own the
 * source", so this is the fact the whole transfer is planned from — never the user the receiver
 * Activity happens to run in (vendor share proxies do not preserve the Personal/Work tab).
 */

/** Authority "10@media" → 10; unqualified authority → null. */
internal fun userIdFromAuthority(authority: String?): Int? {
    if (authority == null) return null
    val separator = authority.indexOf('@')
    if (separator <= 0) return null
    return authority.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 }
}

/**
 * Probe classes. [Empty] = the provider answered the metadata query without a row: MediaProvider
 * filters rows the caller cannot see instead of throwing, so an empty answer is not proof that the
 * URI belongs to this user and only counts after every stronger candidate failed.
 */
internal enum class ProbeResult { Readable, Empty, Denied, NotFound }

/**
 * 1. `N@authority` → N when that URI answers (a qualified URI is never re-qualified);
 * 2. the received URI readable here → current user;
 * 3. the paired-user qualified URI readable → paired user;
 * 4. otherwise unreadable. A candidate that only answered [ProbeResult.Empty] is accepted after
 *    steps 2–3 found no row, in the same order.
 * @param probe candidate index: 0 = received URI, 1 = paired-user qualified URI.
 */
internal fun resolveSourceUser(
    receivedAuthority: String?,
    currentUserId: Int,
    pairedUserId: Int?,
    probe: (candidateIndex: Int) -> ProbeResult,
): Pair<Int, SourceUserRule>? {
    userIdFromAuthority(receivedAuthority)?.let { qualified ->
        return when (probe(0)) {
            ProbeResult.Readable, ProbeResult.Empty -> qualified to SourceUserRule.QualifiedAuthority
            else -> null
        }
    }
    val hasPaired = pairedUserId != null && SourceUriPlanner.qualifiedAuthority(receivedAuthority, pairedUserId) != null
    val received = probe(0)
    if (received == ProbeResult.Readable) return currentUserId to SourceUserRule.CurrentUser
    val paired = if (hasPaired) probe(1) else ProbeResult.NotFound
    if (paired == ProbeResult.Readable) return pairedUserId!! to SourceUserRule.PairedUser
    if (received == ProbeResult.Empty) return currentUserId to SourceUserRule.CurrentUser
    if (paired == ProbeResult.Empty) return pairedUserId!! to SourceUserRule.PairedUser
    return null
}

private fun String.isWildcardMime(): Boolean = isBlank() || contains('*')

/** The item's own type wins; the batch `intent.type` only fills in when it is concrete. */
internal fun resolveItemMime(perUriType: String?, intentType: String?): String =
    perUriType?.takeUnless { it.isWildcardMime() }
        ?: intentType?.takeUnless { it.isWildcardMime() }
        ?: "application/octet-stream"

internal object SourceUriPlanner {
    fun qualifiedAuthority(authority: String?, pairedUserId: Int?): String? = when {
        authority.isNullOrBlank() || pairedUserId == null || pairedUserId < 0 || '@' in authority -> null
        else -> "$pairedUserId@$authority"
    }

    fun candidates(uri: Uri, pairedUserId: Int?): List<Uri> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return listOf(uri)
        val qualifiedAuthority = qualifiedAuthority(uri.encodedAuthority, pairedUserId) ?: return listOf(uri)
        return listOf(uri, uri.buildUpon().encodedAuthority(qualifiedAuthority).build())
    }
}

/**
 * Android adapter: metadata queries only, never a byte stream, so the transfer still opens its
 * source exactly once. Runs on an IO thread.
 */
internal class AndroidSourceResolver(
    private val context: Context,
    private val currentUserId: Int,
    private val pairedUserId: Int?,
) {
    private data class Probe(val result: ProbeResult, val name: String? = null, val size: Long? = null)

    fun ref(uri: Uri, intentType: String?): SourceRef =
        SourceRef(uri.toString(), SourceUriPlanner.candidates(uri, pairedUserId).map(Uri::toString), intentType)

    fun resolve(ref: SourceRef, index: Int): SourceResolution {
        val received = Uri.parse(ref.receivedUri)
        if (received.scheme != ContentResolver.SCHEME_CONTENT) {
            // A non-content URI would be read with PrismSpace's own file permissions (confused deputy).
            DiagnosticLog.w(TAG, "xfer.resolve idx=$index rule=Unreadable scheme=${received.scheme}")
            return SourceResolution.Unreadable
        }
        val candidates = ref.candidates.map(Uri::parse)
        val probes = HashMap<Int, Probe>()
        val owner = resolveSourceUser(received.encodedAuthority, currentUserId, pairedUserId) { candidate ->
            probes.getOrPut(candidate) { candidates.getOrNull(candidate)?.let(::probe) ?: Probe(ProbeResult.NotFound) }.result
        }
        if (owner == null) {
            DiagnosticLog.w(
                TAG,
                "xfer.resolve idx=$index rule=Unreadable authority=${received.encodedAuthority} " +
                    "probes=${probes.toSortedMap().values.joinToString(",") { it.result.name }}",
            )
            return SourceResolution.Unreadable
        }
        val (sourceUserId, rule) = owner
        val readableIndex = if (rule == SourceUserRule.PairedUser) 1 else 0
        val readable = candidates[readableIndex]
        val metadata = probes[readableIndex]
        val displayName = FileTransferPolicy.safeDisplayName(
            metadata?.name ?: received.lastPathSegment?.substringAfterLast('/'),
        )
        val perUriType = runCatching { context.contentResolver.getType(readable) }.getOrNull()
        DiagnosticLog.i(
            TAG,
            "xfer.resolve idx=$index rule=$rule authority=${received.encodedAuthority} source=$sourceUserId",
        )
        return SourceResolution.Resolved(
            sourceUserId = sourceUserId,
            rule = rule,
            readableUri = readable.toString(),
            displayName = displayName,
            mime = resolveItemMime(perUriType, ref.intentType),
            declaredSize = metadata?.size,
        )
    }

    private fun probe(uri: Uri): Probe {
        val resolver = context.contentResolver
        val client = try {
            resolver.acquireUnstableContentProviderClient(uri)
        } catch (_: SecurityException) {
            return Probe(ProbeResult.Denied)
        } catch (_: IllegalArgumentException) {
            return Probe(ProbeResult.Denied)
        } ?: return Probe(ProbeResult.NotFound)
        return try {
            client.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use Probe(ProbeResult.Empty)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Probe(
                    ProbeResult.Readable,
                    name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null,
                )
            } ?: Probe(ProbeResult.Empty)
        } catch (_: SecurityException) {
            Probe(ProbeResult.Denied)
        } catch (_: FileNotFoundException) {
            Probe(ProbeResult.Denied)
        } catch (_: IllegalArgumentException) {
            Probe(ProbeResult.Denied)
        } catch (_: Exception) {
            Probe(ProbeResult.Denied)
        } finally {
            runCatching { client.close() }
        }
    }

    private companion object {
        private const val TAG = "Prism.ImportToSpace"
        private val PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}

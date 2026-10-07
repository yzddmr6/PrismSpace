package com.yzddmr6.prismspace.prism.service

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeFileStore
import com.yzddmr6.prismspace.bridge.BridgeInspectResult
import com.yzddmr6.prismspace.bridge.BridgeOpenMode
import com.yzddmr6.prismspace.bridge.CrossProfileForwardingKind
import com.yzddmr6.prismspace.bridge.FileBridgePort
import com.yzddmr6.prismspace.bridge.ImportApkSet
import com.yzddmr6.prismspace.bridge.MAX_APK_PATH_COUNT
import com.yzddmr6.prismspace.bridge.PublishedFileDto
import com.yzddmr6.prismspace.bridge.RunBridgeSelfTest
import com.yzddmr6.prismspace.bridge.SelfTestResultDto
import com.yzddmr6.prismspace.bridge.TransferLedgerDto
import com.yzddmr6.prismspace.bridge.WriteSessionDto
import com.yzddmr6.prismspace.controller.ClonePreparationStore
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.transfer.AndroidMediaRows
import com.yzddmr6.prismspace.prism.transfer.MediaCollection
import com.yzddmr6.prismspace.prism.transfer.MediaStorePublisher
import com.yzddmr6.prismspace.prism.transfer.TransferKind
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferOpener
import com.yzddmr6.prismspace.prism.transfer.TransferPaths
import com.yzddmr6.prismspace.prism.transfer.TransferRole
import com.yzddmr6.prismspace.prism.transfer.toLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.withPublished
import com.yzddmr6.prismspace.util.DPM
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PrismLocale
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class FileBridgeSelfTestResult(
    val success: Boolean,
    val message: String,
    val cloneUri: String? = null,
    val mainUri: String? = null,
)

/** Result of the APK / install-entry / clone flows. File transfers report [com.yzddmr6.prismspace.prism.transfer.TransferOutcome]. */
data class FileTransferResult(
    val success: Boolean,
    val message: String,
    val displayName: String? = null,
    val targetUri: String? = null,
    val failureReason: FileTransferFailureReason? = null,
)

enum class FileTransferFailureReason {
    SpaceMissing,
    SpaceInactive,
    SourceUnreadable,
    BridgeNotReady,
    TimedOut,
    IOError,
    SpaceUnavailable,
    TargetWriteFailed,
    Cancelled,
}

enum class TransferDirection(val wireValue: String) {
    ToMain("toMain"),
    ToProfile("toProfile");

    companion object {
        fun fromWireValue(value: String?): TransferDirection? = entries.firstOrNull { it.wireValue == value }
    }
}

data class FileTransferDestination(val relativePath: String, val displayLocation: String, val isImage: Boolean)

internal object CrossSpaceFileTransferPolicy {
    fun destination(mimeType: String?): FileTransferDestination =
        if (TransferPaths.isImage(mimeType)) {
            FileTransferDestination(TransferPaths.PICTURES_RELATIVE_PATH, TransferPaths.PICTURES_LOCATION, true)
        } else {
            FileTransferDestination(TransferPaths.DOWNLOAD_RELATIVE_PATH, TransferPaths.DOWNLOAD_LOCATION, false)
        }
}

class TransferCancellationSignal {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

/** A single-use source for one transfer. It carries metadata, not a private cached copy. */
class TransferSource private constructor(
    val displayName: String,
    val mime: String,
    val declaredSize: Long?,
    private val opener: () -> InputStream,
) {
    private val opened = AtomicBoolean(false)

    fun openOnce(): InputStream {
        check(opened.compareAndSet(false, true)) { "Transfer source was already opened" }
        return opener()
    }

    companion object {
        fun fromUriCandidates(
            resolver: ContentResolver,
            uris: List<Uri>,
            displayName: String,
            mime: String,
            declaredSize: Long?,
        ): TransferSource = TransferSource(displayName, mime, declaredSize?.takeIf { it >= 0L }) {
            openFirstReadableCandidate(uris) { resolver.openInputStream(it) }.let { opened ->
                if (opened.index > 0) {
                    DiagnosticLog.i(
                        "Prism.FileSource",
                        "source URI opened with paired-user fallback authority=${opened.candidate.encodedAuthority}",
                    )
                }
                opened.stream
            }
        }

        internal fun testing(
            displayName: String = "test.bin",
            mime: String = "application/octet-stream",
            declaredSize: Long? = null,
            opener: () -> InputStream,
        ): TransferSource = TransferSource(displayName, mime, declaredSize, opener)
    }
}

internal data class OpenedSource<T>(
    val candidate: T,
    val index: Int,
    val stream: InputStream,
)

internal fun <T> openFirstReadableCandidate(
    candidates: List<T>,
    open: (T) -> InputStream?,
): OpenedSource<T> {
    require(candidates.isNotEmpty()) { "At least one source candidate is required" }
    var lastFailure: Exception? = null
    candidates.forEachIndexed { index, candidate ->
        try {
            val stream = open(candidate)
            if (stream != null) return OpenedSource(candidate, index, stream)
        } catch (failure: Exception) {
            lastFailure = failure
        }
    }
    throw FileNotFoundException("No readable source candidate").also { error ->
        lastFailure?.let(error::initCause)
    }
}

/**
 * Main-space facade for the APK clone pipeline, the profile install entry and the bridge self-test.
 * Generic file transfers live in [com.yzddmr6.prismspace.prism.transfer.TransferExecutor].
 */
class FileBridgeService {

    /** Localized user-facing message (follows the app's chosen language, not the system default). */
    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    fun runSelfTest(context: Context): FileBridgeSelfTestResult {
        return try {
            DiagnosticLog.i(TAG, "self-test start package=${context.packageName}")
            val payload = buildPayload(context)
            val cloneResult = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "self-test",
                command = RunBridgeSelfTest(payload),
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileBridgeSelfTestResult(
                    success = false,
                    message = str(context, R.string.fb_apk_transfer_failed),
                )
                else -> return FileBridgeSelfTestResult(
                    success = false,
                    message = bridgeFailureMessage(context, result, str(context, R.string.fb_space_not_ready)),
                )
            }
            DiagnosticLog.i(TAG, "shuttle returned bytes=${cloneResult.bytes.size} uri=${cloneResult.location}")

            DiagnosticLog.i(TAG, "main write start")
            val mainUri = MediaStorePublisher(AndroidMediaRows(context)).writeBytes(
                MediaCollection.Downloads,
                MAIN_FILE,
                MIME_TEXT,
                TransferPaths.DOWNLOAD_RELATIVE_PATH,
                cloneResult.bytes,
            )
            DiagnosticLog.i(TAG, "main write done uri=$mainUri")
            FileBridgeSelfTestResult(
                success = true,
                message = "文件桥自检通过：主空间 -> 双开空间 -> 主空间",
                cloneUri = cloneResult.location,
                mainUri = mainUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "self-test failed", e)
            FileBridgeSelfTestResult(
                success = false,
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    /**
     * 普通模式克隆: transfer a COMPLETE app — base + ALL split APKs — into the dual space's
     * Download/PrismSpace/. Copying the whole split set keeps split packages installable. Only the
     * bounded APK path list crosses the typed bridge; the profile process reads each /data/app file by
     * path (world-readable, same absolute path across users) and streams it into its own MediaStore —
     * avoiding the Binder byte cap and non-serializable PFDs, so it supports big APKs.
     */
    fun importApksToProfile(context: Context, apkFiles: List<java.io.File>, label: String, packageName: String): FileTransferResult {
        return try {
            val paths = completeReadableApkPaths(apkFiles) ?: return FileTransferResult(
                false,
                str(context, R.string.fb_apk_unreadable),
                failureReason = FileTransferFailureReason.SourceUnreadable,
            )
            val safeBase = FileTransferPolicy.safeDisplayName(packageName)
            val firstUriResult = runProfileBridgeOperation(
                context,
                TAG,
                "apk import package=$packageName apkCount=${apkFiles.size} readableCount=${paths.size}",
                command = ImportApkSet(paths, label, packageName, TransferPaths.DOWNLOAD_LOCATION),
            )
            val firstUri = when (firstUriResult) {
                is ProfileBridgeResult.Value -> firstUriResult.value ?: return FileTransferResult(
                    false,
                    str(context, R.string.fb_apk_transfer_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, firstUriResult, str(context, R.string.fb_apk_transfer_failed))
            }
            FileTransferResult(true, str(context, R.string.fb_apk_transferred, safeBase), safeBase, firstUri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "apks import failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_apk_transfer_failed), failureReason = FileTransferFailureReason.IOError)
        }
    }

    internal fun completeReadableApkPaths(
        apkFiles: List<File>,
        canRead: (File) -> Boolean = File::canRead,
    ): ArrayList<String>? {
        if (apkFiles.isEmpty() || apkFiles.any { !canRead(it) }) return null
        return ArrayList(apkFiles.map(File::getAbsolutePath))
    }

    fun openProfileInstallEntry(activity: Activity): FileTransferResult =
        ProfileDownloadsOpener().openInstallEntry(activity)

    private fun bridgeFailureResult(
        context: Context,
        result: ProfileBridgeResult<*>,
        fallbackMessage: String,
    ): FileTransferResult =
        FileTransferResult(
            success = false,
            message = bridgeFailureMessage(context, result, fallbackMessage),
            failureReason = result.failureReason(),
        )

    private fun bridgeFailureMessage(
        context: Context,
        result: ProfileBridgeResult<*>,
        fallbackMessage: String,
    ): String = profileBridgeFailureMessage(context, result, fallbackMessage)

    private fun buildPayload(context: Context): ByteArray {
        val text = buildString {
            appendLine("PrismSpace file bridge self-test")
            appendLine("package=${context.packageName}")
            appendLine("timestamp=${System.currentTimeMillis()}")
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private companion object {
        private const val TAG = "Prism.FileBridge"
        private const val MIME_TEXT = "text/plain"
        private const val MAIN_FILE = "prismspace-bridge-main.txt"
    }
}

internal object MobileFileBridgePort : FileBridgePort {
    override fun openWriteSession(
        context: Context,
        store: BridgeFileStore,
        safeName: String,
        mimeType: String,
        relativePath: String,
    ): WriteSessionDto {
        val rows = AndroidMediaRows(context)
        val row = MediaStorePublisher(rows).openPending(store.toCollection(), safeName, mimeType, relativePath) {
            rows.openDescriptor(it)
        }
        return WriteSessionDto(row.uri, row.handle)
    }

    /**
     * Publishes the row first; only a published file earns its "Received" ledger row, recorded under
     * the name / folder MediaStore actually gave it. The same facts go back to the caller for "Sent".
     */
    override fun finishWriteSession(
        context: Context,
        store: BridgeFileStore,
        targetUri: String,
        record: TransferLedgerDto?,
    ): PublishedFileDto {
        val rows = AndroidMediaRows(context)
        val uri = MediaStorePublisher(rows).publish(targetUri)
        val actual = rows.describe(uri)
        val published = PublishedFileDto(uri, actual?.first, actual?.second)
        record?.let { TransferLedger.record(context, it.withPublished(published).toLedgerRecord(uri)) }
        return published
    }

    /** Deletes the half-written row and the ledger row a timed-out finish may already have written. */
    override fun abortWriteSession(context: Context, store: BridgeFileStore, targetUri: String, transferId: String?) {
        MediaStorePublisher(AndroidMediaRows(context)).abort(targetUri)
        transferId?.let { TransferLedger.remove(context, it, reason = "abort") }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun importApkSet(
        context: Context,
        paths: List<String>,
        label: String,
        packageName: String,
        cloneLocation: String,
    ): String? {
        require(paths.size <= MAX_APK_PATH_COUNT) { "APK set exceeds $MAX_APK_PATH_COUNT entries" }
        val safeBase = FileTransferPolicy.safeDisplayName(packageName)
        // Only the suite this ledger published before is replaced; a plain file that merely shares
        // the "<package>.apk" name is never touched.
        val previous = TransferLedger.apkSuite(context, packageName)?.apkUris.orEmpty().map { PublishedApk(it, "") }
        val uris = ApkSuitePublisher(AndroidApkSuiteStore(context)).replace(
            paths,
            safeBase,
            TransferPaths.DOWNLOAD_RELATIVE_PATH,
            previous,
        )
        TransferLedger.upsertApkSuite(
            context,
            TransferLedgerRecord(
                id = UUID.randomUUID().toString(),
                displayName = label,
                mime = APK_MIME,
                sizeBytes = null,
                contentUri = uris.first(),
                relativePath = TransferPaths.DOWNLOAD_LOCATION,
                direction = TransferDirection.ToProfile,
                role = TransferRole.Received,
                kind = TransferKind.ApkSuite,
                packageName = packageName,
                apkUris = uris,
                timeMillis = System.currentTimeMillis(),
                legacy = false,
            ),
        )
        return uris.first()
    }

    override fun completeClonePreparation(context: Context, packageName: String): Boolean {
        ClonePreparationStore.remove(context, packageName)
        return true
    }

    override fun queryPendingClonePreparations(context: Context): List<String> =
        ClonePreparationStore.pendingPackages(context).sorted()

    override fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto? {
        val cloneUri = MediaStorePublisher(AndroidMediaRows(context)).writeBytes(
            MediaCollection.Downloads,
            SELF_TEST_CLONE_FILE,
            SELF_TEST_MIME_TEXT,
            TransferPaths.DOWNLOAD_RELATIVE_PATH,
            marker,
        )
        val uri = Uri.parse(cloneUri)
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            SelfTestResultDto(bytes, cloneUri)
        } finally {
            check(context.contentResolver.delete(uri, null, null) > 0) {
                "Unable to delete profile file-bridge self-test object: $cloneUri"
            }
        }
    }

    override fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind): Boolean {
        val policies = DevicePolicies(context)
        when (kind) {
            CrossProfileForwardingKind.ProfileDownloads -> {
                val filter = ProfileDownloadsLauncher.crossProfileActivityIntentFilter()
                policies.addCrossProfileIntentFilter(filter, ProfileDownloadsLauncher.crossProfileForwardingFlags())
                policies.execute(
                    DPM::addPersistentPreferredActivity,
                    filter,
                    ProfileDownloadsLauncher.crossProfilePreferredActivityComponent(context),
                )
            }
        }
        return true
    }

    override fun recordTransfer(context: Context, record: TransferLedgerDto, contentUri: String): Boolean {
        TransferLedger.record(context, record.toLedgerRecord(contentUri))
        return true
    }

    override fun inspectTransferredFile(
        context: Context,
        contentUri: String,
        mime: String?,
        mode: BridgeOpenMode,
    ): BridgeInspectResult = TransferOpener.inspect(context, contentUri, mime, mode)

    private fun BridgeFileStore.toCollection() = when (this) {
        BridgeFileStore.Downloads -> MediaCollection.Downloads
        BridgeFileStore.Media -> MediaCollection.Images
    }
}

private const val SELF_TEST_MIME_TEXT = "text/plain"
private const val SELF_TEST_CLONE_FILE = "prismspace-bridge-clone.txt"
private const val APK_MIME = "application/vnd.android.package-archive"

private class TransferCancelledException : java.io.IOException("Transfer cancelled")
private class SourceReadException(cause: Throwable) : java.io.IOException(cause)
private class TargetWriteException(cause: Throwable) : java.io.IOException(cause)

internal sealed interface SingleCopyTransferResult {
    data class Written(val bytes: Long) : SingleCopyTransferResult
    object SourceUnreadable : SingleCopyTransferResult
    object TargetWriteFailed : SingleCopyTransferResult
    object Cancelled : SingleCopyTransferResult
}

internal fun transferSingleCopy(
    source: TransferSource,
    output: OutputStream,
    cancellation: TransferCancellationSignal,
    onProgress: (Long) -> Unit = {},
    abort: () -> Unit,
): SingleCopyTransferResult {
    val input = try {
        source.openOnce()
    } catch (_: Exception) {
        runCatching(abort)
        runCatching { output.close() }
        return SingleCopyTransferResult.SourceUnreadable
    }
    val result = try {
        input.use {
            output.use {
                SingleCopyTransferResult.Written(copyCancellable(input, output, cancellation, onProgress))
            }
        }
    } catch (_: TransferCancelledException) {
        SingleCopyTransferResult.Cancelled
    } catch (_: SourceReadException) {
        SingleCopyTransferResult.SourceUnreadable
    } catch (_: TargetWriteException) {
        SingleCopyTransferResult.TargetWriteFailed
    } catch (_: Exception) {
        SingleCopyTransferResult.TargetWriteFailed
    }
    if (result !is SingleCopyTransferResult.Written) runCatching(abort)
    return result
}

internal fun copyCancellable(
    input: InputStream,
    output: OutputStream,
    cancellation: TransferCancellationSignal,
    onProgress: (Long) -> Unit = {},
): Long {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        if (cancellation.isCancelled()) throw TransferCancelledException()
        val read = try {
            input.read(buffer)
        } catch (e: Exception) {
            throw SourceReadException(e)
        }
        if (read < 0) break
        try {
            output.write(buffer, 0, read)
        } catch (e: Exception) {
            throw TargetWriteException(e)
        }
        copied += read
        onProgress(copied)
    }
    try {
        output.flush()
    } catch (e: Exception) {
        throw TargetWriteException(e)
    }
    return copied
}

private const val STREAM_BUFFER_SIZE = 64 * 1024

internal object ApkSuiteNames {
    fun canonical(safeBase: String, index: Int): String =
        if (index == 0) "$safeBase.apk" else "$safeBase.split$index.apk"

    fun pending(safeBase: String, token: String, index: Int): String =
        ".$safeBase.prism-pending-$token-$index.apk"
}

internal data class StagedApk(val uri: String, val canonicalName: String)
internal data class PublishedApk(val uri: String, val displayName: String)

internal interface ApkSuiteStore {
    fun stage(sourcePath: String, pendingName: String, canonicalName: String, relativePath: String): StagedApk
    fun replaceAtomically(previous: List<PublishedApk>, staged: List<StagedApk>)
    fun abort(staged: StagedApk)
}

/**
 * Publishes a complete APK suite: every file is staged as a pending row first; only when all copies
 * succeeded does one provider batch delete [previous] and publish the new rows. [previous] comes from
 * the ledger, never from a file-name pattern, so unrelated same-name files survive.
 */
internal class ApkSuitePublisher(
    private val store: ApkSuiteStore,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    /** @return every published URI of the new suite, base first. */
    fun replace(
        paths: List<String>,
        safeBase: String,
        relativePath: String,
        previous: List<PublishedApk>,
    ): List<String> {
        require(paths.isNotEmpty()) { "APK suite is empty" }
        val token = tokenFactory()
        val staged = ArrayList<StagedApk>(paths.size)
        try {
            paths.forEachIndexed { index, sourcePath ->
                val canonical = ApkSuiteNames.canonical(safeBase, index)
                staged += store.stage(
                    sourcePath,
                    ApkSuiteNames.pending(safeBase, token, index),
                    canonical,
                    relativePath,
                )
            }
            store.replaceAtomically(previous.distinctBy(PublishedApk::uri), staged)
            return staged.map(StagedApk::uri)
        } catch (error: Throwable) {
            staged.forEach { runCatching { store.abort(it) } }
            throw error
        }
    }
}

internal object FileTransferPolicy {
    fun safeDisplayName(name: String?): String {
        val normalized = name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        return normalized.ifBlank { "prismspace-import.bin" }
    }
}

private class AndroidApkSuiteStore(context: Context) : ApkSuiteStore {
    private val publisher = MediaStorePublisher(AndroidMediaRows(context))

    override fun stage(
        sourcePath: String,
        pendingName: String,
        canonicalName: String,
        relativePath: String,
    ): StagedApk = File(sourcePath).inputStream().use { input ->
        StagedApk(publisher.stage(MediaCollection.Downloads, pendingName, APK_MIME, relativePath, input), canonicalName)
    }

    override fun replaceAtomically(previous: List<PublishedApk>, staged: List<StagedApk>) =
        publisher.publishSuite(previous.map(PublishedApk::uri), staged.map { it.uri to it.canonicalName })

    override fun abort(staged: StagedApk) = publisher.abort(staged.uri)
}

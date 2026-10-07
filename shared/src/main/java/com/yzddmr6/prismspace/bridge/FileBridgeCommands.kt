package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val MAX_PROFILE_APP_PAGE_SIZE = 200
const val DEFAULT_PROFILE_APP_PAGE_SIZE = 200
const val MAX_APK_PATH_COUNT = 256

fun clampProfileAppPageSize(requested: Int): Int = requested.coerceIn(1, MAX_PROFILE_APP_PAGE_SIZE)

class ProfileAppPageAccumulator {
    private val mutableEntries = ArrayList<ProfileAppEntry>()
    val entries: List<ProfileAppEntry> get() = mutableEntries

    /** Returns true when another page is required. */
    fun accept(page: ProfileAppPage): Boolean {
        mutableEntries += page.entries
        return page.hasMore
    }
}

enum class BridgeFileStore { Downloads, Media }
enum class BridgeTransferDirection { ToMain, ToProfile }
enum class CrossProfileForwardingKind { ProfileDownloads }
enum class BridgeTransferRole { Sent, Received }

/**
 * One transfer-ledger entry as it crosses the bridge. The source side generates [transferId]; both
 * sides key their ledger rows on it. [sizeBytes] is the number of bytes actually written.
 */
@Parcelize
data class TransferLedgerDto(
    val transferId: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long?,
    val relativePath: String?,
    val direction: BridgeTransferDirection?,
    val role: BridgeTransferRole,
) : Parcelable

/**
 * What a finished write actually published: MediaStore may rename on a name collision
 * ("photo (1).png"), so the real [displayName] / [relativePath] come back with the URI.
 * Null fields mean the owner could not read them back.
 */
@Parcelize
data class PublishedFileDto(val uri: String, val displayName: String?, val relativePath: String?) : Parcelable

@Parcelize
data class WriteSessionDto(val uri: String, val descriptor: ParcelFileDescriptor) : Parcelable

@Parcelize
data class SelfTestResultDto(val bytes: ByteArray, val location: String) : Parcelable

/**
 * Minimal profile-app snapshot consumed by PrismAppInfo/AppInfo and clone flows.
 *
 * Fields come from PrismAppInfo/AppInfo reads: package/uid/flags/hidden/enabled/target SDK for
 * state and permission policy; label/icon for rendering; APK paths for clone export; launcher entry
 * and policy target for launchability and list rules. No complete ApplicationInfo crosses Binder.
 */
@Parcelize
data class ProfileAppEntry(
    val packageName: String,
    val uid: Int,
    val flags: Int,
    val hidden: Boolean,
    val enabled: Boolean,
    val targetSdkVersion: Int,
    val label: String,
    val iconResource: Int,
    val sourceDir: String?,
    val publicSourceDir: String?,
    val splitSourceDirs: List<String>,
    /** Has an enabled launcher activity in the profile (hidden packages included). */
    val launcherEntry: Boolean = false,
    /** The system app policy targets this package as unavailable ("not in this space"). */
    val policyHidden: Boolean = false,
    /** Action that opens the package's UI inside the profile when it has no launcher entry. */
    val entryAction: String? = null,
) : Parcelable

@Parcelize
data class ProfileAppPage(val entries: List<ProfileAppEntry>, val hasMore: Boolean) : Parcelable

@Parcelize
data class OpenWriteSession(
    val store: BridgeFileStore,
    val safeName: String,
    val mimeType: String,
    val relativePath: String,
) : DestinationCommand<WriteSessionDto> {
    override val id get() = "file.open_write_session"
    override fun encodeResult(result: WriteSessionDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): WriteSessionDto = src.requireParcelableResult()
}

@Parcelize
data class FinishWriteSession(
    val store: BridgeFileStore,
    val targetUri: String,
    val record: TransferLedgerDto? = null,
) : DestinationCommand<PublishedFileDto> {
    override val id get() = "file.finish_write_session"
    override fun encodeResult(result: PublishedFileDto, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): PublishedFileDto = src.requireParcelableResult()
}

@Parcelize
data class AbortWriteSession(
    val store: BridgeFileStore,
    val targetUri: String,
    val transferId: String? = null,
) : DestinationCommand<Unit> {
    override val id get() = "file.abort_write_session"
    override fun encodeResult(result: Unit, out: Bundle) = Unit
    override fun decodeResult(src: Bundle) = Unit
}

@Parcelize
data class ImportApkSet(
    val paths: List<String>,
    val label: String,
    val packageName: String,
    val cloneLocation: String,
) : ProfileCommand<String?> {
    init {
        require(paths.size <= MAX_APK_PATH_COUNT) { "APK set exceeds $MAX_APK_PATH_COUNT entries" }
    }

    override val id get() = "file.import_apk_set"
    override fun encodeResult(result: String?, out: Bundle) = out.putString(RESULT, result)
    override fun decodeResult(src: Bundle): String? = src.getString(RESULT)
}

@Parcelize
data object QueryPendingClonePreparations : ParentCommand<List<String>> {
    override val id get() = "file.query_pending_clone_preparations"
    override fun encodeResult(result: List<String>, out: Bundle) = out.putStringArrayList(RESULT, ArrayList(result))
    override fun decodeResult(src: Bundle): List<String> = requireNotNull(src.getStringArrayList(RESULT))
}

@Parcelize
data class CompleteClonePreparation(val packageName: String) : ParentCommand<Boolean> {
    override val id get() = "file.complete_clone_preparation"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class RunBridgeSelfTest(val marker: ByteArray) : ProfileCommand<SelfTestResultDto?> {
    override val id get() = "file.run_self_test"
    override fun encodeResult(result: SelfTestResultDto?, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): SelfTestResultDto? = src.parcelableResult()
}

@Parcelize
data class InstallCrossProfileForwarding(val kind: CrossProfileForwardingKind) : ProfileCommand<Boolean> {
    override val id get() = "file.install_cross_profile_forwarding"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class QueryProfileAppsPage(
    val pageIndex: Int,
    val pageSize: Int = DEFAULT_PROFILE_APP_PAGE_SIZE,
) : ProfileCommand<ProfileAppPage> {
    override val id get() = "apps.query_page"
    override fun encodeResult(result: ProfileAppPage, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): ProfileAppPage = src.requireParcelableResult()
}

internal val FILE_BRIDGE_COMMAND_SAMPLES: List<BridgeCommand<*>> = listOf(
    OpenWriteSession(BridgeFileStore.Downloads, "name", "type", "path"),
    FinishWriteSession(BridgeFileStore.Downloads, "content://target"),
    AbortWriteSession(BridgeFileStore.Downloads, "content://target"),
    ImportApkSet(listOf("/base.apk"), "label", "pkg", "Download/PrismSpace"),
    CompleteClonePreparation("pkg"),
    QueryPendingClonePreparations,
    RunBridgeSelfTest(byteArrayOf(1)),
    InstallCrossProfileForwarding(CrossProfileForwardingKind.ProfileDownloads),
    QueryProfileAppsPage(0),
)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.parcelableResult(): T? = getParcelable(RESULT)

private inline fun <reified T : Parcelable> Bundle.requireParcelableResult(): T =
    requireNotNull(parcelableResult()) { "Missing ${T::class.java.simpleName} bridge result" }

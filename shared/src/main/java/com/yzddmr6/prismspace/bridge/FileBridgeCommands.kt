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
enum class CrossProfileForwardingKind { ImagePicker, ProfileDownloads }

@Parcelize
data class TransferHistoryDto(
    val displayName: String,
    val displayLocation: String,
    val isImage: Boolean,
    val direction: BridgeTransferDirection?,
) : Parcelable

@Parcelize
data class WriteSessionDto(val uri: String, val descriptor: ParcelFileDescriptor) : Parcelable

@Parcelize
data class ReadSessionDto(
    val displayName: String,
    val mimeType: String,
    val descriptor: ParcelFileDescriptor,
) : Parcelable

@Parcelize
data class ProfileMediaEntryDto(val displayName: String, val mimeType: String, val uri: String) : Parcelable

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
    val history: TransferHistoryDto? = null,
) : DestinationCommand<String> {
    override val id get() = "file.finish_write_session"
    override fun encodeResult(result: String, out: Bundle) = out.putString(RESULT, result)
    override fun decodeResult(src: Bundle): String = requireNotNull(src.getString(RESULT))
}

@Parcelize
data class AbortWriteSession(val store: BridgeFileStore, val targetUri: String) : DestinationCommand<Unit> {
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
data object QueryLatestVisibleImage : ProfileCommand<ProfileMediaEntryDto?> {
    override val id get() = "file.query_latest_visible_image"
    override fun encodeResult(result: ProfileMediaEntryDto?, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): ProfileMediaEntryDto? = src.parcelableResult()
}

@Parcelize
data object OpenImagePickerInProfile : ProfileCommand<Boolean> {
    override val id get() = "file.open_image_picker"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class OpenLatestForRead(val store: BridgeFileStore) : ProfileCommand<ReadSessionDto?> {
    override val id get() = "file.open_latest_for_read"
    override fun encodeResult(result: ReadSessionDto?, out: Bundle) = out.putParcelable(RESULT, result)
    override fun decodeResult(src: Bundle): ReadSessionDto? = src.parcelableResult()
}

@Parcelize
data class WritePerAppShareMarker(val packageName: String) : ProfileCommand<String> {
    override val id get() = "file.write_per_app_share_marker"
    override fun encodeResult(result: String, out: Bundle) = out.putString(RESULT, result)
    override fun decodeResult(src: Bundle): String = requireNotNull(src.getString(RESULT))
}

@Parcelize
data class DeletePerAppShareMarker(val packageName: String) : ProfileCommand<Boolean> {
    override val id get() = "file.delete_per_app_share_marker"
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
    QueryLatestVisibleImage,
    OpenImagePickerInProfile,
    OpenLatestForRead(BridgeFileStore.Downloads),
    WritePerAppShareMarker("pkg"),
    DeletePerAppShareMarker("pkg"),
    RunBridgeSelfTest(byteArrayOf(1)),
    InstallCrossProfileForwarding(CrossProfileForwardingKind.ImagePicker),
    QueryProfileAppsPage(0),
)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.parcelableResult(): T? = getParcelable(RESULT)

private inline fun <reified T : Parcelable> Bundle.requireParcelableResult(): T =
    requireNotNull(parcelableResult()) { "Missing ${T::class.java.simpleName} bridge result" }

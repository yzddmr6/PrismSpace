package com.yzddmr6.prismspace.prism.service

import android.app.Activity
import android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.model.PerAppFileSharePolicy
import com.yzddmr6.prismspace.prism.model.PerAppFileShareSpec
import com.yzddmr6.prismspace.prism.model.PerAppShareDestination
import com.yzddmr6.prismspace.prism.ui.ProfileImagePickerActivity
import com.yzddmr6.prismspace.util.DPM
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PrismLocale
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class FileBridgeSelfTestResult(
    val success: Boolean,
    val message: String,
    val cloneUri: String? = null,
    val mainUri: String? = null,
)

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
}

data class PerAppShareFolderResult(
    val success: Boolean,
    val message: String,
    val relativePath: String? = null,
    val markerDisplayName: String? = null,
)

class FileBridgeService {

    /** Localized user-facing message (follows the app's chosen language, not the system default). */
    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    fun runSelfTest(context: Context): FileBridgeSelfTestResult {
        return try {
            DiagnosticLog.i(TAG, "self-test start package=${context.packageName}")
            val payload = buildPayload(context)
            val cloneResult = when (val result = runProfileBridgeOperation(context, TAG, "self-test") {
                DiagnosticLog.i(TAG, "profile write start")
                val cloneUri = writeDownload(
                    displayName = CLONE_FILE,
                    mimeType = MIME_TEXT,
                    bytes = payload,
                )
                DiagnosticLog.i(TAG, "profile write done uri=$cloneUri")
                val bytes = contentResolver.openInputStream(Uri.parse(cloneUri))?.use { it.readBytes() }
                    ?: return@runProfileBridgeOperation null
                DiagnosticLog.i(TAG, "profile read done bytes=${bytes.size}")
                BridgePayload(bytes, cloneUri)
            }) {
                is ProfileBridgeResult.Value -> result.value ?: return FileBridgeSelfTestResult(
                    success = false,
                    message = str(context, R.string.fb_apk_transfer_failed),
                )
                else -> return FileBridgeSelfTestResult(
                    success = false,
                    message = bridgeFailureMessage(context, result, str(context, R.string.fb_space_not_ready)),
                )
            }
            DiagnosticLog.i(TAG, "shuttle returned bytes=${cloneResult.bytes.size} uri=${cloneResult.uri}")

            DiagnosticLog.i(TAG, "main write start")
            val mainUri = context.writeDownload(
                displayName = MAIN_FILE,
                mimeType = MIME_TEXT,
                bytes = cloneResult.bytes,
            )
            DiagnosticLog.i(TAG, "main write done uri=$mainUri")
            FileBridgeSelfTestResult(
                success = true,
                message = "文件桥自检通过：主空间 -> 双开空间 -> 主空间",
                cloneUri = cloneResult.uri,
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

    fun importToProfile(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "import start uri=$sourceUri")
            val payload = readPayloadMetadata(context, sourceUri)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import file to profile",
                openSession = {
                    AndroidFileBridgeDownloadStore(this).openPendingWrite(
                        payload.displayName,
                        payload.mimeType,
                        FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
                    )
                },
                finishSession = { AndroidFileBridgeDownloadStore(this).finishPendingWrite(it) },
                abortSession = { AndroidFileBridgeDownloadStore(this).abortPendingWrite(it) },
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_import_file_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_import_file_failed))
            }
            DiagnosticLog.i(TAG, "import done display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_imported_to_dual, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "import failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_import_file_failed),
            )
        }
    }

    /**
     * Export from the dual space to the main space by copying the user-selected system URI into
     * the main user's MediaStore Downloads/PrismSpace folder. The system grants the main process
     * read access for the picked URI, so this stays local and avoids Binder-size limits.
     */
    fun importToMain(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            val payload = readPayloadMetadata(context, sourceUri)
            val uri = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                AndroidFileBridgeDownloadStore(context).insertFromStream(
                    payload.displayName,
                    payload.mimeType,
                    input,
                    FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
                )
            } ?: error(str(context, R.string.fb_read_selected_failed))
            FileTransferResult(true, str(context, R.string.fb_imported_to_main, payload.displayName), payload.displayName, uri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "import to main failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_import_file_failed))
        }
    }

    /** Export an image from the dual space to the main user's Pictures/PrismSpace folder. */
    fun importImageToMainGallery(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            val payload = readSharedMediaMetadata(context, sourceUri)
            val uri = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                AndroidFileBridgeMediaStore(context).insertFromStream(
                    payload.displayName,
                    payload.mimeType,
                    input,
                    FileBridgeMediaWriter.DEFAULT_RELATIVE_PATH,
                )
            } ?: error(str(context, R.string.fb_read_selected_image_failed))
            FileTransferResult(true, str(context, R.string.fb_imported_to_main_gallery, payload.displayName), payload.displayName, uri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "image import to main failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_import_image_failed))
        }
    }

    /**
     * 普通模式克隆: transfer a COMPLETE app — base + ALL split APKs — into the dual space's
     * Download/PrismSpace/, recording a single incoming history entry as "label-package". Copying
     * the whole split set keeps split packages installable. Only the APK path strings cross the Shuttle (Serializable); the profile process reads
     * each /data/app file by path (world-readable, same absolute path across users) and streams it into
     * its own MediaStore — avoiding the Binder byte cap and non-serializable PFDs, so it supports big APKs.
     */
    fun importApksToProfile(context: Context, apkFiles: List<java.io.File>, label: String, packageName: String): FileTransferResult {
        return try {
            val paths = ArrayList(apkFiles.filter { it.canRead() }.map { it.absolutePath })
            if (paths.isEmpty()) return FileTransferResult(
                false,
                str(context, R.string.fb_apk_unreadable),
                failureReason = FileTransferFailureReason.SourceUnreadable,
            )
            val safeBase = FileTransferPolicy.safeDisplayName("$label-$packageName")
            val cloneLocation = "Download/PrismSpace"
            val firstUriResult = runProfileBridgeOperation(
                context,
                TAG,
                "apk import package=$packageName apkCount=${apkFiles.size} readableCount=${paths.size}",
            ) {
                var first: String? = null
                paths.forEachIndexed { i, path ->
                    val name = if (i == 0) "$safeBase.apk" else "$safeBase.split$i.apk"
                    val uri = AndroidFileBridgeDownloadStore(this).insertFromFile(
                        name, "application/vnd.android.package-archive",
                        java.io.File(path), FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH)
                    if (first == null) first = uri
                }
                // Dual-space incoming half: recorded as label + package, shown as "label-package".
                TransferHistoryStore.record(this, label, cloneLocation, false, packageName)
                first
            }
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

    fun importImageToProfileGallery(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "media sync start uri=$sourceUri")
            val payload = readSharedMediaMetadata(context, sourceUri)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import image to profile gallery",
                openSession = {
                    AndroidFileBridgeMediaStore(this).openPendingWrite(
                        payload.displayName,
                        payload.mimeType,
                        FileBridgeMediaWriter.DEFAULT_RELATIVE_PATH,
                    )
                },
                finishSession = { AndroidFileBridgeMediaStore(this).finishPendingWrite(it) },
                abortSession = { AndroidFileBridgeMediaStore(this).abortPendingWrite(it) },
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_sync_photo_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_sync_photo_failed))
            }
            DiagnosticLog.i(TAG, "media sync done display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_synced_dual_gallery, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "media sync failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_sync_photo_failed),
            )
        }
    }

    fun importImageToPerAppShare(context: Context, sourceUri: Uri, packageName: String): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "shared media import start sourcePackage=$packageName uri=$sourceUri")
            val payload = readSharedMediaMetadata(context, sourceUri)
            val relativePath = PerAppShareDestination.mediaRelativePath(packageName)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import image to per-app share package=$packageName",
                openSession = {
                    AndroidFileBridgeMediaStore(this).openPendingWrite(
                        payload.displayName,
                        payload.mimeType,
                        relativePath,
                    )
                },
                finishSession = { AndroidFileBridgeMediaStore(this).finishPendingWrite(it) },
                abortSession = { AndroidFileBridgeMediaStore(this).abortPendingWrite(it) },
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_sync_photo_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_sync_photo_failed))
            }
            DiagnosticLog.i(TAG, "shared media import done sourcePackage=$packageName display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_synced_shared_gallery, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "shared media import failed sourcePackage=$packageName", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_sync_photo_failed),
            )
        }
    }

    fun importFileToPerAppShare(context: Context, sourceUri: Uri, packageName: String): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "shared file import start sourcePackage=$packageName uri=$sourceUri")
            val payload = readPayloadMetadata(context, sourceUri)
            val relativePath = PerAppShareDestination.downloadRelativePath(packageName)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import file to per-app share package=$packageName",
                openSession = {
                    AndroidFileBridgeDownloadStore(this).openPendingWrite(
                        payload.displayName,
                        payload.mimeType,
                        relativePath,
                    )
                },
                finishSession = { AndroidFileBridgeDownloadStore(this).finishPendingWrite(it) },
                abortSession = { AndroidFileBridgeDownloadStore(this).abortPendingWrite(it) },
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_import_file_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_import_file_failed))
            }
            DiagnosticLog.i(TAG, "shared file import done sourcePackage=$packageName display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_imported_shared_dir, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "shared file import failed sourcePackage=$packageName", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_import_file_failed),
            )
        }
    }

    fun verifyProfileGalleryVisibility(context: Context): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "media visibility start")
            val entry = when (val result = runProfileBridgeOperation(context, TAG, "media visibility") {
                FileBridgeMediaVisibilityVerifier(AndroidFileBridgeMediaQueryStore(this)).latestVisibleImage()
            }) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_no_dual_media),
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_check_media_failed))
            }
            DiagnosticLog.i(TAG, "media visibility found display=${entry.displayName} uri=${entry.uri}")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_dual_media_visible, entry.displayName),
                displayName = entry.displayName,
                targetUri = entry.uri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "media visibility failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_check_media_failed),
            )
        }
    }

    fun openProfileImagePicker(activity: Activity): FileTransferResult {
            val context = activity.applicationContext
            return try {
                DiagnosticLog.i(TAG, "profile image picker launch start")
            val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (val result = openProfileImagePickerViaForwarder(activity)) {
                    is ProfileBridgeResult.Value -> result.value == true
                    else -> return bridgeFailureResult(context, result, str(context, R.string.fb_open_image_picker_failed))
                }
            } else {
                when (val result = runProfileBridgeOperation(context, TAG, "profile image picker launch") {
                    ProfileImagePickerLauncher.open(this)
                    true
                }) {
                    is ProfileBridgeResult.Value -> result.value == true
                    else -> return bridgeFailureResult(context, result, str(context, R.string.fb_open_image_picker_failed))
                }
            }
            if (!launched) return FileTransferResult(
                success = false,
                message = str(context, R.string.fb_space_not_ready),
                failureReason = FileTransferFailureReason.IOError,
            )
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_opened_image_picker),
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "profile image picker launch failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_open_image_picker_failed),
            )
        }
    }

    private fun openProfileImagePickerViaForwarder(activity: Activity): ProfileBridgeResult<Boolean> {
        val context = activity.applicationContext
        val installResult = installProfileImagePickerForwarding(context)
        if (installResult !is ProfileBridgeResult.Value || installResult.value != true) return installResult
        val intent = prepareProfileImagePickerForwarderIntent(context) ?: return ProfileBridgeResult.Value(false)
        activity.startActivity(intent)
        return ProfileBridgeResult.Value(true)
    }

    fun openProfileDownloadsFolder(activity: Activity): FileTransferResult =
        ProfileDownloadsOpener().openDownloadsFolder(activity)

    fun openProfileInstallEntry(activity: Activity): FileTransferResult =
        ProfileDownloadsOpener().openInstallEntry(activity)

    fun openProfileInstallSourceSettings(activity: Activity, packageName: String): FileTransferResult =
        ProfileDownloadsOpener().openInstallSourceSettings(activity, packageName)

    private fun prepareProfileImagePickerForwarderIntent(context: Context): Intent? {
        val intent = ProfileImagePickerLauncher.buildCrossProfileActivityIntent()
        val forwarder = findCrossProfileForwarder(context, intent)
            ?: return null
        return intent.setComponent(forwarder).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private fun installProfileImagePickerForwarding(context: Context): ProfileBridgeResult<Boolean> {
        return runProfileBridgeOperation(context, TAG, "profile image picker forwarding install") {
            val filter = ProfileImagePickerLauncher.crossProfileActivityIntentFilter()
            val policies = DevicePolicies(this)
            policies.addCrossProfileIntentFilter(
                filter,
                ProfileImagePickerLauncher.crossProfileForwardingFlags(),
            )
            policies.execute(
                DPM::addPersistentPreferredActivity,
                filter,
                ProfileImagePickerLauncher.crossProfilePreferredActivityComponent(this),
            )
            true
        }
    }

    private fun findCrossProfileForwarder(context: Context, intent: Intent): ComponentName? {
        return context.packageManager.queryIntentActivities(
            Intent(intent).setComponent(null),
            MATCH_DISABLED_COMPONENTS or MATCH_DEFAULT_ONLY,
        )
            .firstOrNull { it.activityInfo.packageName == "android" }
            ?.activityInfo
            ?.run { ComponentName(packageName, name) }
    }

    fun exportLatestToMain(context: Context): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "export latest start")
            val payload = when (val result = runProfileBridgeOperation(context, TAG, "export latest to main") {
                AndroidFileBridgeDownloadStore(this).openLatestInPrismFolderForRead()
            }) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_no_files_to_export),
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_export_failed))
            }
            val displayName = payload.getString(BRIDGE_KEY_DISPLAY_NAME)
                ?: return FileTransferResult(false, str(context, R.string.fb_export_failed), failureReason = FileTransferFailureReason.IOError)
            val mimeType = payload.getString(BRIDGE_KEY_MIME_TYPE) ?: "application/octet-stream"
            val pfd = payload.getPfd()
                ?: return FileTransferResult(false, str(context, R.string.fb_export_failed), failureReason = FileTransferFailureReason.IOError)
            val targetUri = context.writeDownload(
                displayName = displayName,
                mimeType = mimeType,
                input = ParcelFileDescriptor.AutoCloseInputStream(pfd),
            )
            DiagnosticLog.i(TAG, "export latest done display=$displayName uri=$targetUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_exported_to_main, displayName),
                displayName = displayName,
                targetUri = targetUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "export latest failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_export_failed),
            )
        }
    }

    fun enablePerAppShareFolder(context: Context, packageName: String): PerAppShareFolderResult {
        return try {
            DiagnosticLog.i(TAG, "per-app share enable start package=$packageName")
            val spec = PerAppFileSharePolicy.specFor(packageName)
            val markerUri = when (val result = runProfileBridgeOperation(context, TAG, "per-app share enable package=$packageName") {
                AndroidPerAppShareFolderStore(this).writeMarker(spec)
            }) {
                is ProfileBridgeResult.Value -> result.value ?: return PerAppShareFolderResult(
                    success = false,
                    message = str(context, R.string.fb_prepare_shared_dir_failed),
                    relativePath = spec.relativePath,
                    markerDisplayName = spec.markerDisplayName,
                )
                else -> return bridgeFailureShareResult(context, result, spec, str(context, R.string.fb_prepare_shared_dir_failed))
            }
            DiagnosticLog.i(TAG, "per-app share enabled package=$packageName marker=$markerUri")
            PerAppShareFolderResult(
                success = true,
                message = str(context, R.string.fb_shared_dir_ready, spec.relativePath),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "per-app share enable failed package=$packageName", e)
            PerAppShareFolderResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_prepare_shared_dir_failed),
            )
        }
    }

    fun disablePerAppShareFolder(context: Context, packageName: String): PerAppShareFolderResult {
        return try {
            DiagnosticLog.i(TAG, "per-app share disable start package=$packageName")
            val spec = PerAppFileSharePolicy.specFor(packageName)
            val deleted = when (val result = runProfileBridgeOperation(context, TAG, "per-app share disable package=$packageName") {
                AndroidPerAppShareFolderStore(this).deleteMarker(spec)
                true
            }) {
                is ProfileBridgeResult.Value -> result.value == true
                else -> return bridgeFailureShareResult(context, result, spec, str(context, R.string.fb_restore_isolation_failed))
            }
            if (!deleted) return PerAppShareFolderResult(
                success = false,
                message = str(context, R.string.fb_restore_isolation_failed),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
            DiagnosticLog.i(TAG, "per-app share disabled package=$packageName")
            PerAppShareFolderResult(
                success = true,
                message = str(context, R.string.fb_isolation_restored),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "per-app share disable failed package=$packageName", e)
            PerAppShareFolderResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_restore_isolation_failed),
            )
        }
    }

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

    private fun bridgeFailureShareResult(
        context: Context,
        result: ProfileBridgeResult<*>,
        spec: PerAppFileShareSpec,
        fallbackMessage: String,
    ): PerAppShareFolderResult =
        PerAppShareFolderResult(
            success = false,
            message = bridgeFailureMessage(context, result, fallbackMessage),
            relativePath = spec.relativePath,
            markerDisplayName = spec.markerDisplayName,
        )

    private fun bridgeFailureMessage(
        context: Context,
        result: ProfileBridgeResult<*>,
        fallbackMessage: String,
    ): String = profileBridgeFailureMessage(context, result, fallbackMessage)

    @Suppress("DEPRECATION")
    private fun Bundle.getPfd(): ParcelFileDescriptor? = getParcelable(BRIDGE_KEY_FD)

    private fun streamUriToProfile(
        context: Context,
        sourceUri: Uri,
        operation: String,
        openSession: Context.() -> Bundle,
        finishSession: Context.(String) -> String,
        abortSession: Context.(String) -> Unit,
    ): ProfileBridgeResult<String> {
        val session = when (val result = runProfileBridgeOperation(context, TAG, "$operation open", block = openSession)) {
            is ProfileBridgeResult.Value -> result.value ?: return ProfileBridgeResult.Failed(
                IllegalStateException("Profile write session returned no descriptor")
            )
            else -> return result.asFailureResult()
        }
        val targetUri = session.getString(BRIDGE_KEY_URI)
            ?: return ProfileBridgeResult.Failed(IllegalStateException("Profile write session returned no URI"))
        val pfd = session.getPfd()
            ?: return ProfileBridgeResult.Failed(IllegalStateException("Profile write session returned no file descriptor"))
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                    input.copyTo(output, STREAM_BUFFER_SIZE)
                }
            } ?: return ProfileBridgeResult.Failed(IllegalStateException(str(context, R.string.fb_read_selected_failed)))
            when (val finish = runProfileBridgeOperation(context, TAG, "$operation finish", block = { finishSession(targetUri) })) {
                is ProfileBridgeResult.Value -> ProfileBridgeResult.Value(finish.value ?: targetUri)
                else -> finish.asFailureResult()
            }
        } catch (e: Throwable) {
            runCatching {
                runProfileBridgeOperation(context, TAG, "$operation abort", block = { abortSession(targetUri) })
            }
            ProfileBridgeResult.Failed(e)
        }
    }

    private fun readPayloadMetadata(context: Context, sourceUri: Uri): FileBridgeMetadata {
        val resolver = context.contentResolver
        val displayName = FileTransferPolicy.safeDisplayName(queryDisplayName(resolver, sourceUri))
        val mimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        return FileBridgeMetadata(displayName, mimeType)
    }

    private fun readSharedMediaMetadata(context: Context, sourceUri: Uri): FileBridgeMetadata {
        val resolver = context.contentResolver
        val displayName = FileTransferPolicy.safeDisplayName(queryDisplayName(resolver, sourceUri))
        val rawMimeType = resolver.getType(sourceUri)
        val mimeType = FileTransferPolicy.resolveSharedMediaMimeType(rawMimeType, displayName)
            ?: error(str(context, R.string.fb_select_image))
        if (!FileTransferPolicy.isSupportedSharedMediaMimeType(mimeType, displayName)) error(str(context, R.string.fb_select_image))
        return FileBridgeMetadata(displayName, mimeType)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }

    private fun buildPayload(context: Context): ByteArray {
        val text = buildString {
            appendLine("PrismSpace file bridge self-test")
            appendLine("package=${context.packageName}")
            appendLine("timestamp=${System.currentTimeMillis()}")
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private data class BridgePayload(
        val bytes: ByteArray,
        val uri: String,
    ) : java.io.Serializable

    private companion object {
        private const val TAG = "Prism.FileBridge"
        private const val MIME_TEXT = "text/plain"
        private const val CLONE_FILE = "prismspace-bridge-clone.txt"
        private const val MAIN_FILE = "prismspace-bridge-main.txt"
    }
}

private const val BRIDGE_KEY_URI = "uri"
private const val BRIDGE_KEY_FD = "fd"
private const val BRIDGE_KEY_DISPLAY_NAME = "display_name"
private const val BRIDGE_KEY_MIME_TYPE = "mime_type"
private const val STREAM_BUFFER_SIZE = 64 * 1024

private data class FileBridgeMetadata(
    val displayName: String,
    val mimeType: String,
) : java.io.Serializable

data class ProfileMediaEntry(
    val displayName: String,
    val mimeType: String,
    val uri: String,
) : java.io.Serializable

private fun ContentResolver.openPendingWriteSession(
    collectionUri: Uri,
    displayName: String,
    mimeType: String,
    relativePath: String,
): Bundle {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = insert(collectionUri, values) ?: error("Unable to create MediaStore entry")
    val pfd = openFileDescriptor(uri, "w") ?: error("Unable to open MediaStore output descriptor")
    return Bundle().apply {
        putString(BRIDGE_KEY_URI, uri.toString())
        putParcelable(BRIDGE_KEY_FD, pfd)
    }
}

private fun ContentResolver.finishPendingWrite(uriString: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        update(Uri.parse(uriString), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }
    return uriString
}

private fun ContentResolver.deleteMediaStoreUri(uriString: String) {
    delete(Uri.parse(uriString), null, null)
}

private fun ContentResolver.openReadSession(
    uri: Uri,
    displayName: String,
    mimeType: String,
): Bundle {
    val pfd = openFileDescriptor(uri, "r") ?: error("Unable to open MediaStore input descriptor")
    return Bundle().apply {
        putString(BRIDGE_KEY_URI, uri.toString())
        putString(BRIDGE_KEY_DISPLAY_NAME, displayName)
        putString(BRIDGE_KEY_MIME_TYPE, mimeType)
        putParcelable(BRIDGE_KEY_FD, pfd)
    }
}

private fun Context.writeDownload(displayName: String, mimeType: String, bytes: ByteArray): String {
    return FileBridgeDownloadWriter(AndroidFileBridgeDownloadStore(this)).write(displayName, mimeType, bytes)
}

private fun Context.writeDownload(displayName: String, mimeType: String, input: InputStream): String {
    return input.use {
        AndroidFileBridgeDownloadStore(this).insertFromStream(
            displayName,
            mimeType,
            it,
            FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
        )
    }
}

internal class FileBridgeDownloadWriter(private val store: FileBridgeDownloadStore) {

    fun write(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String = DEFAULT_RELATIVE_PATH,
    ): String {
        // Do NOT delete an existing same-name file — that silently destroyed the user's data.
        // MediaStore auto-appends " (1)" on a DISPLAY_NAME collision in the same RELATIVE_PATH (Q+),
        // so a second "report.pdf" becomes "report (1).pdf" and the original is preserved.
        return store.insert(displayName, mimeType, bytes, relativePath)
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Download/PrismSpace/"
    }
}

internal interface FileBridgeDownloadStore {
    fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String
}

internal class FileBridgeMediaWriter(private val store: FileBridgeMediaStore) {

    fun write(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String = DEFAULT_RELATIVE_PATH,
    ): String {
        // MediaStore auto-dedups the on-disk name on collision.
        return store.insert(displayName, mimeType, bytes, relativePath)
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Pictures/PrismSpace/"
    }
}

internal interface FileBridgeMediaStore {
    fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String
}

internal class FileBridgeMediaVisibilityVerifier(private val store: FileBridgeMediaQueryStore) {
    fun latestVisibleImage(): ProfileMediaEntry? = store.readLatestInPrismPictures()
}

internal interface FileBridgeMediaQueryStore {
    fun readLatestInPrismPictures(): ProfileMediaEntry?
}

internal object ProfileImagePickerLauncher {
    private const val ACTION_PROFILE_IMAGE_PICKER = "com.yzddmr6.prismspace.action.PROFILE_IMAGE_PICKER"

    fun intentSpec() = ProfileImagePickerIntentSpec(
        action = Intent.ACTION_GET_CONTENT,
        type = "image/*",
        categories = setOf(Intent.CATEGORY_OPENABLE),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK,
    )

    fun crossProfileActivityIntentSpec() = ProfileImagePickerActivityIntentSpec(
        action = ACTION_PROFILE_IMAGE_PICKER,
        categories = setOf(CrossProfile.CATEGORY_MANAGED_PROFILE, Intent.CATEGORY_DEFAULT),
    )

    fun buildIntent(): Intent {
        val spec = intentSpec()
        return Intent(spec.action)
            .setType(spec.type)
            .addFlags(spec.flags)
            .also { intent -> spec.categories.forEach(intent::addCategory) }
    }

    fun buildCrossProfileActivityIntent(): Intent {
        val spec = crossProfileActivityIntentSpec()
        return Intent(spec.action)
            .also { intent -> spec.categories.forEach(intent::addCategory) }
    }

    fun crossProfileActivityIntentFilter(): IntentFilter {
        val spec = crossProfileActivityIntentSpec()
        return IntentFilter(spec.action)
            .also { filter -> spec.categories.forEach(filter::addCategory) }
    }

    fun crossProfileForwardingFlags() = FLAG_MANAGED_CAN_ACCESS_PARENT

    fun crossProfilePreferredActivityClassName() = ProfileImagePickerActivity::class.java.name

    fun crossProfilePreferredActivityComponent(context: Context) = ComponentName(
        context.packageName,
        crossProfilePreferredActivityClassName(),
    )

    fun open(context: Context) {
        context.startActivity(buildIntent())
    }
}

internal data class ProfileImagePickerIntentSpec(
    val action: String,
    val type: String,
    val categories: Set<String>,
    val flags: Int,
)

internal data class ProfileImagePickerActivityIntentSpec(
    val action: String,
    val categories: Set<String>,
)

internal object FileTransferPolicy {
    // No fixed upper size limit: file bridge must handle large payloads such as APKs.
    // Large transfers stream via ParcelFileDescriptor to avoid Binder transaction size limits.
    fun isAllowedSize(size: Long) = size >= 0

    fun safeDisplayName(name: String?): String {
        val normalized = name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        return normalized.ifBlank { "prismspace-import.bin" }
    }

    fun isSupportedSharedMediaMimeType(mimeType: String?, displayName: String): Boolean =
        resolveSharedMediaMimeType(mimeType, displayName)?.startsWith("image/") == true

    fun resolveSharedMediaMimeType(mimeType: String?, displayName: String): String? {
        val normalized = mimeType?.lowercase()?.takeUnless { it == "application/octet-stream" }
        if (normalized?.startsWith("image/") == true) return normalized
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> normalized
        }
    }
}

private class AndroidFileBridgeDownloadStore(private val context: Context) : FileBridgeDownloadStore {

    override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open Downloads output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    /** Stream from a source File (read locally inside this — the profile — process) into a MediaStore
     *  Downloads entry. No full-memory load, no Binder byte limit. Used by the file-sync clone path to copy a
     *  shared, world-readable /data/app APK into the dual space for manual install. */
    fun insertFromFile(displayName: String, mimeType: String, src: java.io.File, relativePath: String): String {
        // No pre-delete: cloning the same app twice now yields "App (1).apk" instead of destroying the first.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads entry")
        src.inputStream().use { input ->
            resolver.openOutputStream(uri)?.use { output -> input.copyTo(output, 64 * 1024) }
                ?: error("Unable to open Downloads output stream")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun insertFromStream(displayName: String, mimeType: String, input: InputStream, relativePath: String): String {
        val resolver = context.contentResolver
        val session = resolver.openPendingWriteSession(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )
        val uri = session.getString(BRIDGE_KEY_URI) ?: error("Unable to create Downloads entry")
        @Suppress("DEPRECATION")
        val pfd = session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD)
            ?: error("Unable to open Downloads output descriptor")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            input.copyTo(output, STREAM_BUFFER_SIZE)
        }
        return resolver.finishPendingWrite(uri)
    }

    fun openPendingWrite(displayName: String, mimeType: String, relativePath: String): Bundle =
        context.contentResolver.openPendingWriteSession(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )

    fun finishPendingWrite(uri: String): String = context.contentResolver.finishPendingWrite(uri)

    fun abortPendingWrite(uri: String) {
        context.contentResolver.deleteMediaStoreUri(uri)
    }

    fun openLatestInPrismFolderForRead(): Bundle? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(PRISM_RELATIVE_PATH)
        } else {
            null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val displayName = FileTransferPolicy.safeDisplayName(
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                )
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val mimeType = if (mimeTypeIndex >= 0 && !cursor.isNull(mimeTypeIndex)) {
                    cursor.getString(mimeTypeIndex)
                } else {
                    "application/octet-stream"
                }
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                return resolver.openReadSession(uri, displayName, mimeType)
            }
        }
        return null
    }

    private companion object {
        private const val PRISM_RELATIVE_PATH = "Download/PrismSpace/"
    }
}

private class AndroidPerAppShareFolderStore(private val context: Context) {

    fun writeMarker(spec: PerAppFileShareSpec): String {
        deleteMarker(spec)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.markerDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create per-app share marker")
        resolver.openOutputStream(uri)?.use { it.write(spec.markerBytes) }
            ?: error("Unable to write per-app share marker")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun deleteMarker(spec: PerAppFileShareSpec) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(spec.markerDisplayName, spec.relativePath)
        } else {
            arrayOf(spec.markerDisplayName)
        }
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }
}

private class AndroidFileBridgeMediaStore(private val context: Context) : FileBridgeMediaStore {

    override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Images entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open Images output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun openPendingWrite(displayName: String, mimeType: String, relativePath: String): Bundle =
        context.contentResolver.openPendingWriteSession(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )

    fun insertFromStream(displayName: String, mimeType: String, input: InputStream, relativePath: String): String {
        val resolver = context.contentResolver
        val session = resolver.openPendingWriteSession(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )
        val uri = session.getString(BRIDGE_KEY_URI) ?: error("Unable to create Images entry")
        @Suppress("DEPRECATION")
        val pfd = session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD)
            ?: error("Unable to open Images output descriptor")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            input.copyTo(output, STREAM_BUFFER_SIZE)
        }
        return resolver.finishPendingWrite(uri)
    }

    fun finishPendingWrite(uri: String): String = context.contentResolver.finishPendingWrite(uri)

    fun abortPendingWrite(uri: String) {
        context.contentResolver.deleteMediaStoreUri(uri)
    }
}

private class AndroidFileBridgeMediaQueryStore(private val context: Context) : FileBridgeMediaQueryStore {

    override fun readLatestInPrismPictures(): ProfileMediaEntry? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(PRISM_PICTURES_RELATIVE_PATH)
        } else {
            null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val displayName = FileTransferPolicy.safeDisplayName(
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                )
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val mimeType = if (mimeTypeIndex >= 0 && !cursor.isNull(mimeTypeIndex)) {
                    cursor.getString(mimeTypeIndex)
                } else {
                    "image/*"
                }
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                return ProfileMediaEntry(displayName, mimeType, uri.toString())
            }
        }
        return null
    }

    private companion object {
        private const val PRISM_PICTURES_RELATIVE_PATH = "Pictures/PrismSpace/"
    }
}

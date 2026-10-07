package com.yzddmr6.prismspace.prism.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import android.provider.DocumentsContract
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeInspectResult
import com.yzddmr6.prismspace.bridge.BridgeOpenMode
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.service.SystemFileManagerLaunchPlanner
import com.yzddmr6.prismspace.prism.service.prepareDownloadsViewerUsable
import com.yzddmr6.prismspace.util.PrismLocale

internal enum class OpenSurfaceResult { Opened, Missing, NoViewer, Failed }

/**
 * Opens a transferred file or its folder in THIS user (the one that owns the MediaStore row).
 * Blocking metadata queries: call from a worker thread. Activities are started with
 * FLAG_ACTIVITY_NEW_TASK so viewers and file managers land in their own task.
 */
internal object TransferOpener {
    private const val TAG = "Prism.FileOpen"

    private enum class Presence { Exists, Missing, Unknown }

    fun inspect(context: Context, contentUri: String, mime: String?, mode: BridgeOpenMode): BridgeInspectResult {
        val uri = Uri.parse(contentUri)
        if (presence(context, uri) == Presence.Missing) return BridgeInspectResult.Missing
        if (mode == BridgeOpenMode.File && viewerChoice(context, uri, mime) == ViewerChoice.NoViewer) {
            return BridgeInspectResult.NoViewer
        }
        return BridgeInspectResult.Exists
    }

    fun openLocal(context: Context, request: TransferOpenRequest): OpenSurfaceResult = when (request.mode) {
        OpenMode.Folder -> {
            val uri = request.contentUri?.let(Uri::parse)
            if (uri != null && presence(context, uri) == Presence.Missing) {
                logSurface(request.mode, "missing", null)
                OpenSurfaceResult.Missing
            } else {
                openFolder(context, request.relativePath)
            }
        }
        OpenMode.File -> request.contentUri?.let { openFile(context, Uri.parse(it), request.mime) }
            ?: OpenSurfaceResult.Failed.also { logSurface(request.mode, "failed", null) }
    }

    /**
     * 1. DocumentsUI on the folder's document URI; 2. a vendor file manager resolving the same
     * directory intent; 3. the Downloads / Xiaomi file-manager chooser. Every step first restores the
     * surface inside the dual space (no-op in the main space).
     */
    fun openFolder(context: Context, relativePath: String?): OpenSurfaceResult {
        if (relativePath != null) {
            val intent = directoryIntent(relativePath)
            runCatching { prepareDownloadsViewerUsable(context, intent) }
            val handlers = queryHandlers(context, intent)
            TransferOpenPlanner.folderSurfaces(handlers).forEach { surface ->
                val launched = start(context, Intent(intent).setComponent(surface.handler.toComponent()))
                if (launched) {
                    val name = if (surface.kind == FolderSurfaceKind.DocumentsUi) "documentsui_dir" else "vendor_dir"
                    logSurface(OpenMode.Folder, name, surface.handler.packageName)
                    return OpenSurfaceResult.Opened
                }
            }
        }
        val title = PrismLocale.wrap(context).getString(R.string.lz_xfer_open_folder)
        val chooserReady = runCatching { prepareDownloadsViewerUsable(context) }.getOrDefault(false)
        if (chooserReady && start(context, SystemFileManagerLaunchPlanner.buildChooserIntent(context, title))) {
            logSurface(OpenMode.Folder, "downloads_chooser", null)
            return OpenSurfaceResult.Opened
        }
        logSurface(OpenMode.Folder, "failed", null)
        return OpenSurfaceResult.Failed
    }

    fun openFile(context: Context, uri: Uri, mime: String?): OpenSurfaceResult {
        if (presence(context, uri) == Presence.Missing) {
            logSurface(OpenMode.File, "missing", null)
            return OpenSurfaceResult.Missing
        }
        val intent = viewIntent(context, uri, mime)
        return when (val choice = TransferOpenPlanner.selectViewer(queryHandlers(context, intent))) {
            ViewerChoice.NoViewer -> OpenSurfaceResult.NoViewer.also { logSurface(OpenMode.File, "no_viewer", null) }
            is ViewerChoice.Single -> {
                if (start(context, Intent(intent).setComponent(choice.handler.toComponent()))) {
                    logSurface(OpenMode.File, "viewer_single", choice.handler.packageName)
                    OpenSurfaceResult.Opened
                } else {
                    OpenSurfaceResult.Failed.also { logSurface(OpenMode.File, "failed", choice.handler.packageName) }
                }
            }
            is ViewerChoice.Chooser -> {
                val title = PrismLocale.wrap(context).getString(R.string.lz_xfer_open_file)
                val chooser = Intent.createChooser(intent, title)
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, choice.excluded.map { it.toComponent() }.toTypedArray())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (start(context, chooser)) {
                    OpenSurfaceResult.Opened.also { logSurface(OpenMode.File, "viewer_chooser", null) }
                } else {
                    OpenSurfaceResult.Failed.also { logSurface(OpenMode.File, "failed", null) }
                }
            }
        }
    }

    private fun directoryIntent(relativePath: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(
            DocumentsContract.buildDocumentUri(
                TransferOpenPlanner.EXTERNAL_STORAGE_AUTHORITY,
                TransferOpenPlanner.folderDocumentId(relativePath),
            ),
            DocumentsContract.Document.MIME_TYPE_DIR,
        )
        .addCategory(Intent.CATEGORY_DEFAULT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun viewIntent(context: Context, uri: Uri, mime: String?): Intent {
        val type = mime?.takeIf { it.isNotBlank() }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun viewerChoice(context: Context, uri: Uri, mime: String?): ViewerChoice =
        TransferOpenPlanner.selectViewer(queryHandlers(context, viewIntent(context, uri, mime)))

    private fun queryHandlers(context: Context, intent: Intent): List<HandlerRef> = runCatching {
        context.packageManager.queryIntentActivities(Intent(intent).setComponent(null), PackageManager.MATCH_DEFAULT_ONLY)
            .map { HandlerRef(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())

    /** A row we cannot query is not declared missing: the start attempt then reports the real failure. */
    private fun presence(context: Context, uri: Uri): Presence = runCatching {
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) Presence.Exists else Presence.Missing
        } ?: Presence.Missing
    }.getOrElse { error ->
        DiagnosticLog.w(TAG, "open.presence unknown cause=${error.javaClass.simpleName}")
        Presence.Unknown
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrElse { error ->
        DiagnosticLog.w(TAG, "open.start failed cause=${error.javaClass.simpleName}")
        false
    }

    private fun HandlerRef.toComponent() = ComponentName(packageName, className)

    private fun logSurface(mode: OpenMode, surface: String, packageName: String?) {
        DiagnosticLog.i(TAG, "open.surface mode=$mode surface=$surface pkg=${packageName ?: "-"}")
    }
}

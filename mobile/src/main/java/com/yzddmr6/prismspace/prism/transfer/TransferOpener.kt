package com.yzddmr6.prismspace.prism.transfer

import android.content.ClipData
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
import com.yzddmr6.prismspace.util.Users

internal enum class OpenSurfaceResult { Opened, Missing, NoViewer, Failed }

/** [dropped]: items that vanished between the caller's pre-check and this user's own re-check. */
internal data class LocalShareResult(val result: OpenSurfaceResult, val dropped: Int)

/**
 * Opens a transferred file or its folder in THIS user (the one that owns the MediaStore row).
 * Blocking metadata queries: call from a worker thread. Activities are started with
 * FLAG_ACTIVITY_NEW_TASK so viewers and file managers land in their own task.
 */
internal object TransferOpener {
    private const val TAG = "Prism.FileOpen"

    private enum class Presence { Exists, Missing, Unknown }

    /**
     * Runs in the owner, before the caller forwards: the same ledger rule the receiver applies, so a
     * request this user would ignore is reported as [BridgeInspectResult.Unrecorded] instead of silence.
     */
    fun inspect(context: Context, contentUri: String, mime: String?, mode: BridgeOpenMode): BridgeInspectResult {
        if (!isRecordedHere(contentUri, TransferLedger.load(context))) return BridgeInspectResult.Unrecorded
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
        OpenMode.Share -> shareLocal(context, request.shareItems).result
    }

    /**
     * Starts the system share sheet in THIS user over files PrismSpace published here. Only the
     * system chooser is started (by PrismSpace, in this user): the target app is picked by the user
     * inside this user, so no cross-user launch of a third-party app happens. The cross-profile
     * forwarder and PrismSpace's own share receiver are excluded from the chooser.
     */
    fun shareLocal(context: Context, items: List<ShareItem>): LocalShareResult {
        // Re-check here: a file may have been deleted after the caller's pre-check. Unknown counts as present.
        val present = items.filter { presence(context, Uri.parse(it.contentUri)) != Presence.Missing }
        val dropped = items.size - present.size
        if (present.isEmpty()) {
            logSurface(OpenMode.Share, "missing", null)
            return LocalShareResult(OpenSurfaceResult.Missing, dropped)
        }
        val uris = present.map { Uri.parse(it.contentUri) }
        val mimes = present.mapIndexed { index, item -> resolveMime(context, uris[index], item.mime) }
        val action = if (present.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        val probe = Intent(action).setType(TransferOpenPlanner.shareMimeType(mimes))
        val handlers = queryHandlers(context, probe)
        val self = handlers.filter { it.packageName == context.packageName }
        return when (val plan = TransferOpenPlanner.planShareIntent(present, mimes, handlers, self)) {
            SharePlan.NoItems -> LocalShareResult(OpenSurfaceResult.Missing, dropped)
                .also { logSurface(OpenMode.Share, "missing", null) }
            SharePlan.NoTarget -> LocalShareResult(OpenSurfaceResult.NoViewer, dropped).also {
                DiagnosticLog.i(TAG, "xfer.share.intent action=${action.shareActionName()} items=${present.size} handlers=${handlers.size} result=no_target")
                logSurface(OpenMode.Share, "no_target", null)
            }
            is SharePlan.Ready -> {
                DiagnosticLog.i(
                    TAG,
                    "xfer.share.intent action=${plan.action.shareActionName()} items=${plan.uris.size} type=${plan.type} " +
                        "excluded=${plan.excluded.size} selfExcluded=${self.isNotEmpty()} localDropped=$dropped",
                )
                val target = Intent(plan.action).setType(plan.type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) target.putExtra(Intent.EXTRA_STREAM, uris.single())
                else target.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                // ClipData carries the read grant; createChooser moves it (and the grant flag) to the chooser.
                target.clipData = ClipData.newRawUri("", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                val strings = PrismLocale.wrap(context)
                val title = strings.getString(R.string.lz_xfer_share_chooser_title, strings.getString(currentSpaceNameRes()))
                val chooser = Intent.createChooser(target, title)
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, plan.excluded.map { it.toComponent() }.toTypedArray())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (start(context, chooser)) {
                    logSurface(OpenMode.Share, "share_chooser", null)
                    LocalShareResult(OpenSurfaceResult.Opened, dropped)
                } else {
                    logSurface(OpenMode.Share, "failed", null)
                    LocalShareResult(OpenSurfaceResult.Failed, dropped)
                }
            }
        }
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
        // CLEAR_TASK: an already running DocumentsUI task would otherwise just come to the front at
        // whatever folder it last showed (observed on HyperOS 3 / Android 16), not at this one.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun resolveMime(context: Context, uri: Uri, mime: String?): String =
        mime?.takeIf { it.isNotBlank() }
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"

    private fun currentSpaceNameRes(): Int =
        if (runCatching { Users.isParentProfile() }.getOrDefault(true)) R.string.lz_xfer_space_main
        else R.string.lz_xfer_space_dual

    private fun String.shareActionName() = if (this == Intent.ACTION_SEND_MULTIPLE) "SEND_MULTIPLE" else "SEND"

    private fun viewIntent(context: Context, uri: Uri, mime: String?): Intent {
        val type = resolveMime(context, uri, mime)
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

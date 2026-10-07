package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.bridge.BridgeInspectResult
import com.yzddmr6.prismspace.bridge.BridgeOpenMode
import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate

internal enum class OpenMode { Folder, File, Share }

/** One published file to share, in the user that owns it. Strings only (JVM tests). */
internal data class ShareItem(val contentUri: String, val mime: String?)

/**
 * "Open" for one ledger row, executed in the user that owns the file. [OpenMode.Share] carries its
 * files in [shareItems] (≥ 1; [recordId] is the ledger id or a hand-off id) and leaves the single-file
 * fields null; the other modes leave [shareItems] empty.
 */
internal data class TransferOpenRequest(
    val recordId: String,
    val mode: OpenMode,
    val contentUri: String?,
    val mime: String?,
    val relativePath: String?,
    val shareItems: List<ShareItem> = emptyList(),
)

internal data class HandlerRef(val packageName: String, val className: String)

internal sealed interface ViewerChoice {
    data object NoViewer : ViewerChoice
    data class Single(val handler: HandlerRef) : ViewerChoice
    /** System chooser over the real viewers; [excluded] are the cross-profile forwarder entries. */
    data class Chooser(val excluded: List<HandlerRef>) : ViewerChoice
}

internal enum class FolderSurfaceKind { DocumentsUi, Vendor }

internal data class FolderSurface(val handler: HandlerRef, val kind: FolderSurfaceKind)

internal sealed interface SharePlan {
    data object NoItems : SharePlan
    /** Nothing but the cross-profile forwarder and PrismSpace itself can receive the files. */
    data object NoTarget : SharePlan
    data class Ready(
        /** [TransferOpenPlanner.ACTION_SEND] or [TransferOpenPlanner.ACTION_SEND_MULTIPLE]. */
        val action: String,
        val type: String,
        val uris: List<String>,
        /** Forwarder entries plus PrismSpace's own share receiver, hidden from the chooser. */
        val excluded: List<HandlerRef>,
    ) : SharePlan
}

/** Pre-check fold over the owner's per-item inspect answers. */
internal sealed interface ShareFilter {
    /** [items] may be empty (every file was deleted); [dropped] counts the missing ones. */
    data class Kept(val items: List<ShareItem>, val dropped: Int) : ShareFilter
    /** At least one inspect call failed on the bridge: the outcome cannot be trusted. */
    data object BridgeFailed : ShareFilter
}

/** One delivery route: here, through the system cross-profile intent forwarder, or not at all. */
internal sealed interface OpenRoute {
    data object Local : OpenRoute
    data class Forwarded(val ownerUserId: Int) : OpenRoute
    data class Blocked(val guidance: String) : OpenRoute
}

/** Pure decisions behind "open folder" / "open file". */
internal object TransferOpenPlanner {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** The system cross-profile intent forwarder; resolving to it would leave the owning space. */
    const val FORWARDER_PACKAGE = "android"

    val DOCUMENTS_UI_PACKAGES = listOf("com.google.android.documentsui", "com.android.documentsui")

    /** `Download/PrismSpace/` → `primary:Download/PrismSpace` (external-storage document id). */
    fun folderDocumentId(relativePath: String): String = "primary:" + relativePath.trim().trim('/')

    /** Images land in Pictures/PrismSpace, everything else in Download/PrismSpace. */
    fun folderFor(mime: String?): String = TransferPaths.locationFor(mime)

    /** Folder handlers in launch order: DocumentsUI first, then any vendor file manager; never the forwarder. */
    fun folderSurfaces(handlers: List<HandlerRef>): List<FolderSurface> {
        val real = handlers.filter { it.packageName != FORWARDER_PACKAGE }.distinct()
        val documentsUi = real.filter { it.packageName in DOCUMENTS_UI_PACKAGES }
            .sortedBy { DOCUMENTS_UI_PACKAGES.indexOf(it.packageName) }
            .map { FolderSurface(it, FolderSurfaceKind.DocumentsUi) }
        val vendor = real.filterNot { it.packageName in DOCUMENTS_UI_PACKAGES }
            .map { FolderSurface(it, FolderSurfaceKind.Vendor) }
        return documentsUi + vendor
    }

    /** 0 viewers → NoViewer; 1 → that component explicitly; more → chooser excluding the forwarder. */
    fun selectViewer(handlers: List<HandlerRef>): ViewerChoice {
        val forwarders = handlers.filter { it.packageName == FORWARDER_PACKAGE }.distinct()
        val viewers = handlers.filter { it.packageName != FORWARDER_PACKAGE }.distinct()
        return when (viewers.size) {
            0 -> ViewerChoice.NoViewer
            1 -> ViewerChoice.Single(viewers.single())
            else -> ViewerChoice.Chooser(forwarders)
        }
    }

    /**
     * Received rows are opened here. Sent rows live in the paired space: the dual-space owner is
     * gated first (only computable in the main space), then the request is forwarded by the system
     * cross-profile intent forwarder to PrismSpace in the owner (the profile owner's native route).
     */
    fun planOpenRoute(role: TransferRole, ownerUserId: Int?, ownerGate: SpaceActionGate?): OpenRoute {
        if (role == TransferRole.Received) return OpenRoute.Local
        if (ownerGate != null && !ownerGate.enabled) return OpenRoute.Blocked(ownerGate.guidance.orEmpty())
        val owner = ownerUserId ?: return OpenRoute.Blocked(ownerGate?.guidance.orEmpty())
        return OpenRoute.Forwarded(owner)
    }

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /**
     * "Continue sharing in the other space". When the files already live in this user (a vendor share
     * proxy ran the receiver in the target user) the sheet opens here; otherwise exactly the route of
     * opening a Sent row.
     */
    fun planShareRoute(ownerUserId: Int?, currentUserId: Int, ownerGate: SpaceActionGate?): OpenRoute =
        if (ownerUserId != null && ownerUserId == currentUserId) OpenRoute.Local
        else planOpenRoute(TransferRole.Sent, ownerUserId, ownerGate)

    // All equal: that type. Same top-level type: the "image/ *" style wildcard (without the space).
    // Otherwise, or nothing known: the any-type wildcard. Comparison is case-insensitive.
    fun shareMimeType(mimes: List<String>): String {
        val normalized = mimes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty() || normalized.size != mimes.size) return "*/*"
        if (normalized.distinct().size == 1) return mimes.first().trim()
        val tops = normalized.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1 && tops.single().isNotEmpty() && tops.single() != "*") "${tops.single()}/*" else "*/*"
    }

    /**
     * 1 item → SEND, more → SEND_MULTIPLE over [resolvedMimes]. The cross-profile forwarder (package
     * "android") and PrismSpace's own share receiver [self] are excluded from the chooser: the first
     * would leave the owning user, the second would send the files straight back. Nothing else left →
     * [SharePlan.NoTarget].
     */
    fun planShareIntent(
        items: List<ShareItem>,
        resolvedMimes: List<String>,
        handlers: List<HandlerRef>,
        self: List<HandlerRef>,
    ): SharePlan {
        if (items.isEmpty()) return SharePlan.NoItems
        val forwarders = handlers.filter { it.packageName == FORWARDER_PACKAGE }.distinct()
        val selfDistinct = self.distinct()
        val excluded = (forwarders + selfDistinct).distinct()
        val targets = handlers.distinct().filterNot { it in excluded }
        if (targets.isEmpty()) return SharePlan.NoTarget
        return SharePlan.Ready(
            action = if (items.size == 1) ACTION_SEND else ACTION_SEND_MULTIPLE,
            type = shareMimeType(resolvedMimes),
            uris = items.map { it.contentUri },
            excluded = excluded,
        )
    }

    /**
     * Parallel uri / mime lists as carried by the forwarded intent's extras ("" = unknown mime)
     * back to items; null when empty, containing a blank uri, or of unequal length.
     */
    fun shareItemsOf(uris: List<String>?, mimes: List<String>?): List<ShareItem>? {
        if (uris.isNullOrEmpty() || mimes == null || uris.size != mimes.size) return null
        if (uris.any { it.isBlank() }) return null
        return uris.mapIndexed { index, uri -> ShareItem(uri, mimes[index].ifEmpty { null }) }
    }

    /**
     * Missing / Unrecorded → dropped (the owner would not share it); Exists / NoViewer → kept; any null
     * (a bridge failure) → [ShareFilter.BridgeFailed].
     */
    fun keepPresent(items: List<ShareItem>, results: List<BridgeInspectResult?>): ShareFilter {
        if (results.size != items.size || results.any { it == null }) return ShareFilter.BridgeFailed
        val kept = items.filterIndexed { index, _ ->
            results[index] != BridgeInspectResult.Missing && results[index] != BridgeInspectResult.Unrecorded
        }
        return ShareFilter.Kept(kept, items.size - kept.size)
    }
}

internal fun OpenMode.toBridge(): BridgeOpenMode = when (this) {
    OpenMode.Folder -> BridgeOpenMode.Folder
    OpenMode.File -> BridgeOpenMode.File
    OpenMode.Share -> BridgeOpenMode.Share
}

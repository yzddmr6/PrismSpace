package com.yzddmr6.prismspace.prism.transfer

import com.yzddmr6.prismspace.bridge.BridgeOpenMode
import com.yzddmr6.prismspace.bridge.TransferOpenRequestDto
import com.yzddmr6.prismspace.prism.compose.vm.SpaceActionGate

internal enum class OpenMode { Folder, File }

/** "Open" for one ledger row, executed in the user that owns the file. */
internal data class TransferOpenRequest(
    val recordId: String,
    val mode: OpenMode,
    val contentUri: String?,
    val mime: String?,
    val relativePath: String?,
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

internal sealed interface OpenRoute {
    data object Local : OpenRoute
    data class CrossProfileStart(val ownerUserId: Int) : OpenRoute
    data class QueuedEntry(val ownerUserId: Int) : OpenRoute
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
     * gated first (only computable in the main space); then a platform cross-profile start when
     * allowed (API 30+ and granted), otherwise a queued request plus launching the owner's entry.
     */
    fun planOpenRoute(
        role: TransferRole,
        ownerUserId: Int?,
        sdkInt: Int,
        canInteractAcrossProfiles: Boolean,
        ownerGate: SpaceActionGate?,
    ): OpenRoute {
        if (role == TransferRole.Received) return OpenRoute.Local
        if (ownerGate != null && !ownerGate.enabled) return OpenRoute.Blocked(ownerGate.guidance.orEmpty())
        val owner = ownerUserId ?: return OpenRoute.Blocked(ownerGate?.guidance.orEmpty())
        return if (sdkInt >= 30 && canInteractAcrossProfiles) OpenRoute.CrossProfileStart(owner)
        else OpenRoute.QueuedEntry(owner)
    }
}

internal fun OpenMode.toBridge(): BridgeOpenMode = when (this) {
    OpenMode.Folder -> BridgeOpenMode.Folder
    OpenMode.File -> BridgeOpenMode.File
}

internal fun BridgeOpenMode.toOpenMode(): OpenMode = when (this) {
    BridgeOpenMode.Folder -> OpenMode.Folder
    BridgeOpenMode.File -> OpenMode.File
}

internal fun TransferOpenRequest.toDto() = TransferOpenRequestDto(recordId, mode.toBridge(), contentUri, mime, relativePath)

internal fun TransferOpenRequestDto.toOpenRequest() = TransferOpenRequest(recordId, mode.toOpenMode(), contentUri, mime, relativePath)

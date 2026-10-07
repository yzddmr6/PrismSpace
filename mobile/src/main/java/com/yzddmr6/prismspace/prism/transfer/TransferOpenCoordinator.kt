package com.yzddmr6.prismspace.prism.transfer

import android.app.Activity
import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.Build
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeInspectResult
import com.yzddmr6.prismspace.bridge.BridgeOpenMode
import com.yzddmr6.prismspace.bridge.BridgeTarget
import com.yzddmr6.prismspace.bridge.InspectTransferredFile
import com.yzddmr6.prismspace.bridge.QueueTransferOpen
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.SpaceRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.space.SpaceStateRepository
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.compose.vm.GateAction
import com.yzddmr6.prismspace.prism.compose.vm.fileTransferGate
import com.yzddmr6.prismspace.prism.compose.vm.prismResolver
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.prism.service.runDestinationBridgeOperation
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the caller's screen tells the user after an "open" tap. */
internal sealed interface OpenOutcome {
    data object Opened : OpenOutcome
    /** The open was handed to PrismSpace in [owner]; it completes there. */
    data class Handoff(val owner: SpaceRole) : OpenOutcome
    data object Missing : OpenOutcome
    data object NoViewer : OpenOutcome
    data class Blocked(val guidance: String) : OpenOutcome
    data class Failed(val message: String) : OpenOutcome
}

/** Where "continue sharing in the other space" was tapped (diagnostics only). */
internal enum class ShareOrigin { Result, Ledger }

/** What the caller's screen tells the user after a "continue sharing in the other space" tap. */
internal sealed interface ShareOutcome {
    /** The share sheet started in this user; [dropped] files had vanished and were left out. */
    data class Opened(val dropped: Int) : ShareOutcome
    /** Handed to PrismSpace in [owner], which opens the share sheet there. */
    data class Handoff(val owner: SpaceRole, val dropped: Int) : ShareOutcome
    /** Nothing left to share. */
    data object Missing : ShareOutcome
    data object NoTarget : ShareOutcome
    data class Blocked(val guidance: String) : ShareOutcome
    data class Failed(val message: String) : ShareOutcome
}

private const val STATE_COLLECTION_TIMEOUT_MS = 4_000L

/** Fresh dual-space usability, the same source as clone launch. Main space only (`@OwnerUser`). */
@WorkerThread
internal fun currentDualUsability(context: Context): SpaceUsability {
    // Collect fresh facts: a cold process (the share receiver) has none yet, and a warm one may hold
    // a stale "healthy" snapshot after the user paused work apps (both observed on device).
    SpaceStateRepository(context.applicationContext).refreshBlocking("transfer_gate", STATE_COLLECTION_TIMEOUT_MS)
    val repo = SpaceRepositoryProvider.get(context.applicationContext)
    return repo.dualSpace()?.let { repo.usabilityOf(it) } ?: SpaceUsability.NotProvisioned
}

/**
 * Caller side of "open folder / open file" for one ledger row. Received rows open here; Sent rows
 * live in the paired space and are executed by PrismSpace's own foreground activity there.
 * Blocking work runs on IO; activity starts happen on the caller's main thread.
 */
internal object TransferOpenCoordinator {
    private const val TAG = "Prism.TransferOpen"

    suspend fun open(activity: Activity, record: TransferLedgerRecord, mode: OpenMode): OpenOutcome {
        val currentIsParent = runCatching { Users.isParentProfile() }.getOrDefault(true)
        val request = TransferOpenRequest(record.id, mode, record.contentUri, record.mime, record.relativePath)
        val owner = if (record.role == TransferRole.Received) {
            if (currentIsParent) SpaceRole.Main else SpaceRole.Dual
        } else {
            if (currentIsParent) SpaceRole.Dual else SpaceRole.Main
        }
        val ownerUserId = when {
            record.role == TransferRole.Received -> Users.currentId()
            currentIsParent -> Users.profile?.toId()
            else -> runCatching { Users.parentProfile.toId() }.getOrNull()
        }
        DiagnosticLog.i(
            TAG,
            "open.request id=${record.id} role=${record.role} mode=$mode owner=${ownerUserId ?: "-"} " +
                "current=${Users.currentId()} uriKnown=${record.contentUri != null}",
        )
        val failedText = failedMessage(activity, owner)

        if (record.role == TransferRole.Received) {
            DiagnosticLog.i(TAG, "open.route route=local")
            return when (withContext(Dispatchers.IO) { TransferOpener.openLocal(activity, request) }) {
                OpenSurfaceResult.Opened -> OpenOutcome.Opened
                OpenSurfaceResult.Missing -> OpenOutcome.Missing
                OpenSurfaceResult.NoViewer -> OpenOutcome.NoViewer
                OpenSurfaceResult.Failed -> OpenOutcome.Failed(failedText)
            }
        }

        // Only the main space can compute dual-space usability; inside the dual space the main
        // space is necessarily running, and bridge failures are classified below.
        val usability = if (currentIsParent) withContext(Dispatchers.IO) { currentDualUsability(activity) } else null
        val gate = usability?.let { fileTransferGate(it, prismResolver(activity), GateAction.Open) }
        val canInteract = canInteractAcrossProfiles(activity)
        val route = TransferOpenPlanner.planOpenRoute(record.role, ownerUserId, Build.VERSION.SDK_INT, canInteract, gate)
        DiagnosticLog.i(
            TAG,
            "open.route route=${route.logName()} sdk=${Build.VERSION.SDK_INT} canInteract=$canInteract gate=${usability ?: "-"}",
        )
        if (route is OpenRoute.Blocked) return OpenOutcome.Blocked(route.guidance)
        val ownerId = ownerUserId ?: return OpenOutcome.Failed(failedText)
        val target = bridgeTargetFor(ownerId) ?: return OpenOutcome.Failed(failedText)

        record.contentUri?.let { uri ->
            val inspected = withContext(Dispatchers.IO) {
                runDestinationBridgeOperation(
                    activity,
                    TAG,
                    "open inspect owner=$ownerId",
                    target,
                    command = InspectTransferredFile(uri, record.mime, mode.toBridge()),
                )
            }
            val result = (inspected as? ProfileBridgeResult.Value)?.value
            DiagnosticLog.i(TAG, "open.inspect result=${result ?: "bridge:${inspected.javaClass.simpleName}"}")
            when (result) {
                BridgeInspectResult.Missing -> return OpenOutcome.Missing
                BridgeInspectResult.NoViewer -> return OpenOutcome.NoViewer
                BridgeInspectResult.Exists -> Unit
                null -> return OpenOutcome.Failed(profileBridgeFailureMessage(activity, inspected, failedText))
            }
        }

        return when (val delivery = deliver(activity, request, route, owner, ownerId, target, failedText)) {
            Delivery.Delivered -> OpenOutcome.Handoff(owner)
            is Delivery.Failed -> OpenOutcome.Failed(delivery.message)
        }
    }

    /**
     * "Continue sharing in the other space": the system share sheet is started by PrismSpace's own
     * foreground activity in [ownerUserId] (the user holding the published files), never by launching
     * a third-party app across users. The dual-space owner is gated first (main space only); every
     * file is pre-checked in the owner and vanished ones are left out.
     */
    suspend fun share(
        activity: Activity,
        origin: ShareOrigin,
        owner: SpaceRole,
        ownerUserId: Int?,
        items: List<ShareItem>,
        handoffId: String,
    ): ShareOutcome {
        val currentIsParent = runCatching { Users.isParentProfile() }.getOrDefault(true)
        val currentUserId = Users.currentId()
        DiagnosticLog.i(
            TAG,
            "xfer.share.request origin=${origin.name.lowercase()} id=$handoffId items=${items.size} " +
                "owner=${ownerUserId ?: "-"} current=$currentUserId",
        )
        val failedText = shareFailedMessage(activity, owner)
        if (items.isEmpty()) return ShareOutcome.Missing

        // Only the main space can compute dual-space usability, and only a dual-space owner needs it.
        val gated = currentIsParent && ownerUserId != currentUserId
        val usability = if (gated) withContext(Dispatchers.IO) { currentDualUsability(activity) } else null
        val gate = usability?.let { fileTransferGate(it, prismResolver(activity), GateAction.Share) }
        val canInteract = canInteractAcrossProfiles(activity)
        val route = TransferOpenPlanner.planShareRoute(ownerUserId, currentUserId, Build.VERSION.SDK_INT, canInteract, gate)
        DiagnosticLog.i(
            TAG,
            "xfer.share.route route=${route.logName()} sdk=${Build.VERSION.SDK_INT} canInteract=$canInteract gate=${usability ?: "-"}",
        )
        when (route) {
            is OpenRoute.Blocked -> return ShareOutcome.Blocked(route.guidance)
            OpenRoute.Local -> {
                val local = withContext(Dispatchers.IO) { TransferOpener.shareLocal(activity, items) }
                return when (local.result) {
                    OpenSurfaceResult.Opened -> ShareOutcome.Opened(local.dropped)
                    OpenSurfaceResult.Missing -> ShareOutcome.Missing
                    OpenSurfaceResult.NoViewer -> ShareOutcome.NoTarget
                    OpenSurfaceResult.Failed -> ShareOutcome.Failed(failedText)
                }
            }
            is OpenRoute.CrossProfileStart, is OpenRoute.QueuedEntry -> Unit
        }
        val ownerId = ownerUserId ?: return ShareOutcome.Failed(failedText)
        val target = bridgeTargetFor(ownerId) ?: return ShareOutcome.Failed(failedText)

        val results = ArrayList<BridgeInspectResult?>(items.size)
        for (item in items) {
            val inspected = withContext(Dispatchers.IO) {
                runDestinationBridgeOperation(
                    activity,
                    TAG,
                    "share inspect owner=$ownerId",
                    target,
                    command = InspectTransferredFile(item.contentUri, item.mime, BridgeOpenMode.Share),
                )
            }
            val value = (inspected as? ProfileBridgeResult.Value)?.value
            if (value == null) {
                DiagnosticLog.w(TAG, "xfer.share.inspect kept=- dropped=- result=bridge:${inspected.javaClass.simpleName}")
                return ShareOutcome.Failed(profileBridgeFailureMessage(activity, inspected, failedText))
            }
            results += value
        }
        val kept = when (val filter = TransferOpenPlanner.keepPresent(items, results)) {
            ShareFilter.BridgeFailed -> return ShareOutcome.Failed(failedText)
            is ShareFilter.Kept -> filter
        }
        DiagnosticLog.i(TAG, "xfer.share.inspect kept=${kept.items.size} dropped=${kept.dropped} result=ok")
        if (kept.items.isEmpty()) return ShareOutcome.Missing

        val request = TransferOpenRequest(handoffId, OpenMode.Share, null, null, null, kept.items)
        return when (val delivery = deliver(activity, request, route, owner, ownerId, target, failedText)) {
            Delivery.Delivered -> ShareOutcome.Handoff(owner, kept.dropped)
            is Delivery.Failed -> ShareOutcome.Failed(delivery.message)
        }
    }

    /** Ledger-row variant: a Sent file row, owned by the paired space (same owner rule as [open]). */
    suspend fun shareRecord(activity: Activity, record: TransferLedgerRecord): ShareOutcome {
        val currentIsParent = runCatching { Users.isParentProfile() }.getOrDefault(true)
        val owner = if (currentIsParent) SpaceRole.Dual else SpaceRole.Main
        val ownerUserId = if (currentIsParent) Users.profile?.toId() else runCatching { Users.parentProfile.toId() }.getOrNull()
        val uri = record.contentUri ?: return ShareOutcome.Failed(shareFailedMessage(activity, owner))
        return share(activity, ShareOrigin.Ledger, owner, ownerUserId, listOf(ShareItem(uri, record.mime)), record.id)
    }

    private sealed interface Delivery {
        data object Delivered : Delivery
        data class Failed(val message: String) : Delivery
    }

    /**
     * Hands [request] to PrismSpace in [ownerId]: a platform cross-profile start of its own trampoline
     * when [route] allows, otherwise the request is parked over the bridge and the owner's entry screen
     * is launched to drain it.
     */
    private suspend fun deliver(
        activity: Activity,
        request: TransferOpenRequest,
        route: OpenRoute,
        owner: SpaceRole,
        ownerId: Int,
        target: BridgeTarget,
        failedText: String,
    ): Delivery {
        if (route is OpenRoute.CrossProfileStart && startCrossProfile(activity, request, ownerId)) {
            return Delivery.Delivered
        }
        if (route is OpenRoute.CrossProfileStart) DiagnosticLog.w(TAG, "open.route route=route_fallback")

        val queued = withContext(Dispatchers.IO) {
            runDestinationBridgeOperation(
                activity,
                TAG,
                "open queue owner=$ownerId",
                target,
                command = QueueTransferOpen(request.toDto()),
            )
        }
        if (queued !is ProfileBridgeResult.Value || queued.value != true) {
            return Delivery.Failed(profileBridgeFailureMessage(activity, queued, failedText))
        }
        val launched = if (owner == SpaceRole.Dual) {
            UserHandles.of(ownerId)?.let { ProfileEntryLauncher.start(activity, it) } ?: false
        } else {
            ParentEntryLauncher.start(activity)
        }
        return if (launched) Delivery.Delivered else Delivery.Failed(failedText)
    }

    private fun startCrossProfile(activity: Activity, request: TransferOpenRequest, ownerUserId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val apps = activity.getSystemService(CrossProfileApps::class.java) ?: return false
            val owner = UserHandles.of(ownerUserId) ?: return false
            apps.startActivity(TransferOpenActivity.intent(activity, request), owner, activity)
            DiagnosticLog.i(TAG, "open.route route=cross_profile_start owner=$ownerUserId")
            true
        }.onFailure { DiagnosticLog.w(TAG, "open.cross_profile_start failed cause=${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    private fun canInteractAcrossProfiles(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            context.getSystemService(CrossProfileApps::class.java)?.canInteractAcrossProfiles() == true
        }.getOrDefault(false)
    }

    private fun shareFailedMessage(context: Context, owner: SpaceRole): String {
        val strings = PrismLocale.wrap(context)
        return strings.getString(R.string.lz_xfer_share_failed, strings.getString(owner.sentenceNameRes()))
    }

    private fun failedMessage(context: Context, owner: SpaceRole): String {
        val strings = PrismLocale.wrap(context)
        return strings.getString(R.string.lz_xfer_open_failed, strings.getString(owner.sentenceNameRes()))
    }

    private fun OpenRoute.logName() = when (this) {
        OpenRoute.Local -> "local"
        is OpenRoute.CrossProfileStart -> "cross_profile_start"
        is OpenRoute.QueuedEntry -> "queued_entry"
        is OpenRoute.Blocked -> "gate_blocked"
    }
}

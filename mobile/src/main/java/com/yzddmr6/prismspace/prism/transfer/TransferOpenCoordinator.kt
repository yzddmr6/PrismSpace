package com.yzddmr6.prismspace.prism.transfer

import android.app.Activity
import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.Build
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgeInspectResult
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

        if (route is OpenRoute.CrossProfileStart && startCrossProfile(activity, request, ownerId)) {
            return OpenOutcome.Handoff(owner)
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
            return OpenOutcome.Failed(profileBridgeFailureMessage(activity, queued, failedText))
        }
        val launched = if (owner == SpaceRole.Dual) {
            UserHandles.of(ownerId)?.let { ProfileEntryLauncher.start(activity, it) } ?: false
        } else {
            ParentEntryLauncher.start(activity)
        }
        return if (launched) OpenOutcome.Handoff(owner) else OpenOutcome.Failed(failedText)
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

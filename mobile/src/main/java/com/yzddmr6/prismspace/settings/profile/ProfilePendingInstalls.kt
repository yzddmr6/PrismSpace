package com.yzddmr6.prismspace.settings.profile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.QueryPendingClonePreparations
import com.yzddmr6.prismspace.prism.transfer.TransferKind
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.TransferRole
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome

/** Null tasks mean the canonical source was unavailable, not that there are no tasks. */
internal data class ProfileTransfers(val history: List<TransferLedgerRecord>, val pending: List<TransferLedgerRecord>?)

/** Run off main: the parent owns workflow state; the ledger only supplies display metadata. */
internal fun loadProfileTransfers(context: Context): ProfileTransfers {
    val history = TransferLedger.load(context)
    val pending = try {
        val target = BridgeTargets.parentFresh(context) ?: return ProfileTransfers(history, null)
        val result = Bridge.inParent(context, target).execute(QueryPendingClonePreparations)
        val packages = when (result) {
            is ShuttleOutcome.Value -> result.value ?: return ProfileTransfers(history, null)
            else -> return ProfileTransfers(history, null)
        }
        // Suite rows survive "clear" as hidden index rows, so a cleared list keeps its install task.
        pendingProfileInstalls(TransferLedger.apkSuites(context), packages.toSet(), isInstalled = { pkg ->
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                info.flags and ApplicationInfo.FLAG_INSTALLED != 0
            } catch (_: PackageManager.NameNotFoundException) { false }
        }, hasApks = { item ->
            ProfileApkInstaller.hasCopiedApkSet(context, item.packageName!!, item.displayName)
        })
    } catch (error: RuntimeException) {
        DiagnosticLog.w("Prism.ProfileTasks", "pending task query failed exception=${error.javaClass.simpleName}")
        null
    }
    return ProfileTransfers(history, pending)
}

internal fun pendingProfileInstalls(
    transfers: List<TransferLedgerRecord>,
    pendingPackages: Set<String>,
    isInstalled: (String) -> Boolean,
    hasApks: (TransferLedgerRecord) -> Boolean,
): List<TransferLedgerRecord> {
    val latest = transfers
        .filter { it.kind == TransferKind.ApkSuite && it.packageName != null }
        .sortedByDescending { it.timeMillis }
        .distinctBy { it.packageName }
        .associateBy { it.packageName }
    return pendingPackages.filter { it.isNotBlank() && !isInstalled(it) }.map { pkg ->
        // A task must stay reachable even without a ledger row (older versions, trimmed history).
        latest[pkg] ?: placeholderSuite(pkg)
    }.filter(hasApks).sortedByDescending { it.timeMillis }
}

private fun placeholderSuite(pkg: String) = TransferLedgerRecord(
    id = "pending:$pkg",
    displayName = pkg,
    mime = null,
    sizeBytes = null,
    contentUri = null,
    relativePath = null,
    direction = null,
    role = TransferRole.Received,
    kind = TransferKind.ApkSuite,
    packageName = pkg,
    apkUris = emptyList(),
    timeMillis = 0L,
    legacy = false,
)

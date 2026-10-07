package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import androidx.annotation.WorkerThread
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.QueryVendorCloneProfilePresence
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.util.CloneProfilePresence
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.VendorCloneProfiles

/**
 * Whether to show the system-level dual-apps notice in this space. The local read-only check comes
 * first; inside the dual space it cannot read sibling profile types (observed: Unknown on HyperOS),
 * so an Unknown answer asks the main space over the bridge. A definitive bridge answer is cached for
 * the process; a failed or unavailable bridge yields Unknown (no notice), never an error.
 */
internal object VendorCloneNotice {
    private const val TAG = "Prism.Users"
    private const val BRIDGE_TIMEOUT_MS = 3_000L

    @Volatile private var mainSpaceAnswer: CloneProfilePresence? = null

    @WorkerThread
    fun presence(context: Context): CloneProfilePresence =
        VendorCloneProfiles.resolve(Users.vendorCloneProfilePresence(context)) { askMainSpace(context) }

    @WorkerThread
    fun show(context: Context): Boolean = presence(context) == CloneProfilePresence.Present

    private fun askMainSpace(context: Context): CloneProfilePresence? {
        mainSpaceAnswer?.let { return it }
        // In the main space the local answer already is the main-space answer.
        if (runCatching { Users.isParentProfile() }.getOrDefault(true)) return null
        val target = BridgeTargets.parent() ?: return null.also {
            DiagnosticLog.i(TAG, "clone_profile main_space_query result=no_target")
        }
        val outcome = runCatching {
            Bridge.inParent(context, target).execute(QueryVendorCloneProfilePresence, BRIDGE_TIMEOUT_MS)
        }.getOrElse { ShuttleOutcome.Failed(it) }
        val value = (outcome as? ShuttleOutcome.Value)?.value
        DiagnosticLog.i(TAG, "clone_profile main_space_query result=${value ?: "bridge:${outcome.javaClass.simpleName}"}")
        if (value != null && value != CloneProfilePresence.Unknown) mainSpaceAnswer = value
        return value
    }
}

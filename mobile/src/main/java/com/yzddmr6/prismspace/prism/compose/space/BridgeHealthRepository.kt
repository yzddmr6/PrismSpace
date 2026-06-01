package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.os.SystemClock
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shuttle.ShuttleHealth
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.concurrent.ConcurrentHashMap

internal class SpaceBridgeHealthStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class Entry(val health: ShuttleHealth, val updatedAtMs: Long)

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun update(health: ShuttleHealth, nowMs: Long) {
        entries[health.profileId] = Entry(health, nowMs)
    }

    fun cached(profileId: Int, nowMs: Long): ShuttleHealth? {
        val entry = entries[profileId] ?: return null
        return if (nowMs - entry.updatedAtMs <= ttlMs) entry.health else null
    }

    fun invalidate(profileId: Int) {
        entries.remove(profileId)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_TTL_MS = 10_000L
    }
}

internal object SpaceBridgeHealthStores {
    val app = SpaceBridgeHealthStore()
}

internal class BridgeHealthRepository(
    private val appContext: Context,
    private val store: SpaceBridgeHealthStore = SpaceBridgeHealthStores.app,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    fun cachedHealth(profile: UserHandle): ShuttleHealth? =
        store.cached(profile.toId(), nowMs())

    fun refreshHealth(profile: UserHandle): ShuttleHealth {
        val health = ShuttleProvider.health(appContext, profile)
        store.update(health, nowMs())
        DiagnosticLog.i(TAG, "bridge health refreshed ${health.diagnosticLine()}")
        return health
    }

    fun invalidate(profile: UserHandle) {
        store.invalidate(profile.toId())
    }

    private companion object {
        const val TAG = "Prism.BridgeHealth"
    }
}

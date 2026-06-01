package com.yzddmr6.prismspace.prism.compose

import com.yzddmr6.prismspace.prism.compose.space.SpaceBridgeHealthStore
import com.yzddmr6.prismspace.shuttle.ShuttleHealth
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceBridgeHealthStoreTest {
    @Test fun cachedHealthIsReturnedBeforeTtlExpires() {
        val store = SpaceBridgeHealthStore(ttlMs = 10_000L)
        val health = health(available = true)

        store.update(health, nowMs = 1_000L)

        assertEquals(health, store.cached(profileId = 10, nowMs = 10_999L))
    }

    @Test fun cachedHealthExpiresAfterTtl() {
        val store = SpaceBridgeHealthStore(ttlMs = 10_000L)

        store.update(health(available = true), nowMs = 1_000L)

        assertNull(store.cached(profileId = 10, nowMs = 11_001L))
    }

    @Test fun cacheCanBeInvalidatedForOneProfile() {
        val store = SpaceBridgeHealthStore(ttlMs = 10_000L)
        store.update(health(profileId = 10, available = true), nowMs = 1_000L)
        store.update(health(profileId = 11, available = true), nowMs = 1_000L)

        store.invalidate(profileId = 10)

        assertNull(store.cached(profileId = 10, nowMs = 1_500L))
        assertEquals(11, store.cached(profileId = 11, nowMs = 1_500L)?.profileId)
    }

    private fun health(profileId: Int = 10, available: Boolean): ShuttleHealth =
        ShuttleHealth(
            profileId = profileId,
            running = true,
            quietMode = false,
            unlocked = true,
            forwardGrant = available,
            backwardGrant = available,
            ping = if (available) ShuttleOutcome.Value(true) else ShuttleOutcome.NotReady(
                com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause.PermissionDenied,
            ),
        )
}

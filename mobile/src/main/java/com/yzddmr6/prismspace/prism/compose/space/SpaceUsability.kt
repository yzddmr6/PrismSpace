package com.yzddmr6.prismspace.prism.compose.space

/** Single source of truth for whether the dual space is usable RIGHT NOW
 *  (CE-unlock-aware — not just profile-owner/running). */
enum class SpaceUsability { NotProvisioned, Suspended, LockedNeedsUnlock, BridgeNotReady, Unknown, Usable }

/** Pure, unit-tested. Precedence: NotProvisioned > Suspended(not running) >
 *  Suspended(quiet mode) > LockedNeedsUnlock(running but CE-locked) > BridgeNotReady/Unknown > Usable. */
fun spaceUsability(
    provisioned: Boolean,
    running: Boolean,
    unlocked: Boolean,
    bridgeReady: Boolean? = true,
    quietMode: Boolean = false,
): SpaceUsability =
    when {
        !provisioned -> SpaceUsability.NotProvisioned
        !running || quietMode -> SpaceUsability.Suspended
        !unlocked    -> SpaceUsability.LockedNeedsUnlock
        bridgeReady == null -> SpaceUsability.Unknown
        !bridgeReady -> SpaceUsability.BridgeNotReady
        else         -> SpaceUsability.Usable
    }

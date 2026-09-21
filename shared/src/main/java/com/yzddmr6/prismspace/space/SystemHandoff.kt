package com.yzddmr6.prismspace.space

/**
 * Verdict about one hand-off to a system surface.
 *
 * PrismSpace repeatedly hands control to an Android surface it does not own (ManagedProvisioning,
 * the package installer confirmation, ...) and then has to interpret a single result code. Vendor
 * ROMs do not honour the AOSP contract: they return `RESULT_CANCELED` for their own refusal, and
 * sometimes return a cancel code after the action actually succeeded. The only honest reading
 * combines the code with verified real-world facts and the time the user had to interact.
 */
enum class HandoffVerdict { Succeeded, UserCancelled, SystemRefused, Pending }

/**
 * Evidence about one hand-off to a system surface (ManagedProvisioning, PackageInstaller confirm ...).
 * @param codeSignalsCancel the surface returned a cancel/abort code (RESULT_CANCELED, STATUS_FAILURE_ABORTED "User rejected").
 * @param goalReached true when the real-world goal is verified reached, false when verified not reached, null when unknown.
 * @param elapsedMs time from launching the surface to receiving the code; null when unknown.
 * @param precheckRefused the pre-flight already said the platform disallows this action.
 * @param userInteractionThresholdMs below this a human could not have interacted; the code is the system's own refusal.
 */
data class HandoffEvidence(
    val codeSignalsCancel: Boolean,
    val goalReached: Boolean?,
    val elapsedMs: Long?,
    val precheckRefused: Boolean = false,
    val userInteractionThresholdMs: Long,
)

fun classifyHandoff(e: HandoffEvidence): HandoffVerdict = when {
    e.goalReached == true -> HandoffVerdict.Succeeded            // regardless of code (vendor ROMs return cancel after installing/creating)
    !e.codeSignalsCancel -> HandoffVerdict.Pending               // OK or unknown code but goal not yet verified: keep waiting
    e.goalReached == null -> HandoffVerdict.Pending              // cancel code but facts unavailable: do not invent a verdict
    e.precheckRefused -> HandoffVerdict.SystemRefused
    e.elapsedMs != null && e.elapsedMs < e.userInteractionThresholdMs -> HandoffVerdict.SystemRefused
    else -> HandoffVerdict.UserCancelled
}

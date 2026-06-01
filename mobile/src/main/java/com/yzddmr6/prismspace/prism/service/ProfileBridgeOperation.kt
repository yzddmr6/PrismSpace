package com.yzddmr6.prismspace.prism.service

import android.content.Context
import android.os.UserHandle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.shuttle.Shuttle
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.shuttle.ShuttleProvider
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId

internal sealed class ProfileBridgeResult<out R> {
    data class Value<out R>(val value: R?) : ProfileBridgeResult<R>()
    data object SpaceMissing : ProfileBridgeResult<Nothing>()
    data class SpaceInactive(val reason: String) : ProfileBridgeResult<Nothing>()
    data class BridgeNotReady(val cause: ShuttleNotReadyCause?) : ProfileBridgeResult<Nothing>()
    data object TimedOut : ProfileBridgeResult<Nothing>()
    data class Failed(val error: Throwable) : ProfileBridgeResult<Nothing>()

    companion object {
        fun <R> from(outcome: ShuttleOutcome<R>): ProfileBridgeResult<R> =
            when (outcome) {
                is ShuttleOutcome.Value -> Value(outcome.value)
                is ShuttleOutcome.NotReady -> BridgeNotReady(outcome.cause)
                ShuttleOutcome.TimedOut -> TimedOut
                is ShuttleOutcome.Failed -> Failed(outcome.error)
                is ShuttleOutcome.Skipped -> SpaceInactive(outcome.reason)
            }
    }
}

internal fun <R> runProfileBridgeOperation(
    context: Context,
    tag: String,
    operation: String,
    target: UserHandle? = Users.profile,
    timeoutMs: Long? = null,
    block: Context.() -> R,
): ProfileBridgeResult<R> {
    val profile = target ?: return ProfileBridgeResult.SpaceMissing
    if (profile == Users.current()) {
        DiagnosticLog.i(tag, "$operation local profile=${profile.toId()}")
        val outcome = Shuttle(context, to = profile).invokeOutcome(block)
        return ProfileBridgeResult.from(outcome)
    }
    val health = ShuttleProvider.health(context, profile)
    DiagnosticLog.i(
        tag,
        "$operation preflight profile=${profile.toId()} ${health.diagnosticLine()}",
    )
	if (!health.available) {
		return ProfileBridgeResult.from(health.ping).asFailureResult()
	}
    val outcome = if (timeoutMs != null) {
        Shuttle(context, to = profile).invokeOutcomeWithin(timeoutMs, block)
    } else {
        Shuttle(context, to = profile).invokeOutcome(block)
    }
    return ProfileBridgeResult.from(outcome)
}

internal fun ProfileBridgeResult<*>.failureReason(): FileTransferFailureReason? =
    when (this) {
        ProfileBridgeResult.SpaceMissing -> FileTransferFailureReason.SpaceMissing
        is ProfileBridgeResult.SpaceInactive -> FileTransferFailureReason.SpaceInactive
        is ProfileBridgeResult.BridgeNotReady -> FileTransferFailureReason.BridgeNotReady
        ProfileBridgeResult.TimedOut -> FileTransferFailureReason.TimedOut
        is ProfileBridgeResult.Failed -> FileTransferFailureReason.IOError
        is ProfileBridgeResult.Value -> null
    }

internal fun <R> ProfileBridgeResult<*>.asFailureResult(): ProfileBridgeResult<R> =
    when (this) {
        ProfileBridgeResult.SpaceMissing -> ProfileBridgeResult.SpaceMissing
        is ProfileBridgeResult.SpaceInactive -> ProfileBridgeResult.SpaceInactive(reason)
        is ProfileBridgeResult.BridgeNotReady -> ProfileBridgeResult.BridgeNotReady(cause)
        ProfileBridgeResult.TimedOut -> ProfileBridgeResult.TimedOut
        is ProfileBridgeResult.Failed -> ProfileBridgeResult.Failed(error)
        is ProfileBridgeResult.Value -> error("Value result cannot be converted to failure")
    }

internal fun profileBridgeFailureMessage(
    context: Context,
    result: ProfileBridgeResult<*>,
    fallbackMessage: String,
): String {
    val strings = PrismLocale.wrap(context)
    return when (result) {
        ProfileBridgeResult.SpaceMissing -> strings.getString(R.string.fb_need_create_space)
        is ProfileBridgeResult.SpaceInactive -> strings.getString(R.string.fb_space_inactive)
        is ProfileBridgeResult.BridgeNotReady -> strings.getString(R.string.fb_space_bridge_repair_needed)
        ProfileBridgeResult.TimedOut -> strings.getString(R.string.fb_space_not_ready)
        is ProfileBridgeResult.Failed -> result.error.message ?: fallbackMessage
        is ProfileBridgeResult.Value -> fallbackMessage
    }
}

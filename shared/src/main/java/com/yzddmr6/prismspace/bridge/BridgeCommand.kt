package com.yzddmr6.prismspace.bridge

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

sealed interface BridgeCommand<R> : Parcelable {
    val id: String
    fun encodeResult(result: R, out: Bundle)
    fun decodeResult(src: Bundle): R
}

sealed interface ProfileCommand<R> : BridgeCommand<R>
sealed interface ParentCommand<R> : BridgeCommand<R>
sealed interface DestinationCommand<R> : BridgeCommand<R>

@Parcelize
data object Ping : ProfileCommand<Boolean> {
    @IgnoredOnParcel
    override val id = "profile.ping"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class SetAppFrozen(val packageName: String, val frozen: Boolean) : ProfileCommand<Boolean> {
    @IgnoredOnParcel
    override val id = "app.set_frozen"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class EnsureAppHiddenState(val packageName: String, val hidden: Boolean) : ProfileCommand<Boolean> {
    @IgnoredOnParcel
    override val id = "app.ensure_hidden_state"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class SetPackageSuspended(val packageName: String, val suspended: Boolean) : ProfileCommand<Boolean> {
    @IgnoredOnParcel
    override val id = "app.set_suspended"
    override fun encodeResult(result: Boolean, out: Bundle) = out.putBoolean(RESULT, result)
    override fun decodeResult(src: Bundle) = src.getBoolean(RESULT)
}

@Parcelize
data class SetPackagesSuspended(val packageNames: List<String>, val suspended: Boolean) : ProfileCommand<Array<String>> {
    @IgnoredOnParcel
    override val id = "app.set_many_suspended"
    override fun encodeResult(result: Array<String>, out: Bundle) = out.putStringArray(RESULT, result)
    override fun decodeResult(src: Bundle): Array<String> =
        requireNotNull(src.getStringArray(RESULT)) { "Missing failed-package result for $id" }
}

@Parcelize
data class SetPackagesFrozen(val packageNames: List<String>, val frozen: Boolean) : ProfileCommand<Array<String>> {
    @IgnoredOnParcel
    override val id = "app.set_many_frozen"
    override fun encodeResult(result: Array<String>, out: Bundle) = out.putStringArray(RESULT, result)
    override fun decodeResult(src: Bundle): Array<String> =
        requireNotNull(src.getStringArray(RESULT)) { "Missing failed-package result for $id" }
}

@Parcelize
data class EnsureAppFreeToLaunch(val packageName: String) : ProfileCommand<String> {
    @IgnoredOnParcel
    override val id = "app.ensure_free_to_launch"
    override fun encodeResult(result: String, out: Bundle) = out.putString(RESULT, result)
    override fun decodeResult(src: Bundle): String =
        requireNotNull(src.getString(RESULT)) { "Missing launch-readiness result for $id" }
}

/** Single audit surface for stable wire identifiers and protocol tests. */
object BridgeCommandCatalog {
    val all: List<BridgeCommand<*>> = listOf(
        Ping,
        SetAppFrozen("example", true),
        EnsureAppHiddenState("example", true),
        SetPackageSuspended("example", true),
        SetPackagesSuspended(listOf("example"), true),
        SetPackagesFrozen(listOf("example"), true),
        EnsureAppFreeToLaunch("example"),
    ) + FILE_BRIDGE_COMMAND_SAMPLES + SPACE_AND_SHORTCUT_COMMAND_SAMPLES + DIAGNOSTICS_COMMAND_SAMPLES +
        SYSTEM_APP_COMMAND_SAMPLES + TRANSFER_COMMAND_SAMPLES
}

internal const val RESULT = "result"

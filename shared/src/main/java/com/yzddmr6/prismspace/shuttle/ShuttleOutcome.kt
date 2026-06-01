package com.yzddmr6.prismspace.shuttle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ShuttleNotReadyCause {
	PermissionDenied,
	UnknownAuthority,
	PermissionPresentCallFailed,
}

sealed class ShuttleOutcome<out R> {
	data class Value<out R>(val value: R?) : ShuttleOutcome<R>()
	data class NotReady(val cause: ShuttleNotReadyCause) : ShuttleOutcome<Nothing>()
	data object TimedOut : ShuttleOutcome<Nothing>()
	data class Failed(val error: Throwable) : ShuttleOutcome<Nothing>()
	data class Skipped(val reason: String) : ShuttleOutcome<Nothing>()

	fun isAvailable() = this is Value<*>

	fun diagnosticValue(): String = when (this) {
		is Value -> "ok"
		is NotReady -> "not_ready:$cause"
		TimedOut -> "timed_out"
		is Failed -> "failed:${error.javaClass.simpleName}:${error.message.orEmpty()}"
		is Skipped -> "skipped:$reason"
	}
}

fun classifyShuttleNotReadyCause(error: RuntimeException, permissionGranted: Boolean): ShuttleNotReadyCause? =
	when (error) {
		is SecurityException ->
			if (permissionGranted) ShuttleNotReadyCause.PermissionPresentCallFailed
			else ShuttleNotReadyCause.PermissionDenied
		is IllegalArgumentException ->
			if (error.message?.contains("Unknown authority", ignoreCase = true) == true) {
				ShuttleNotReadyCause.UnknownAuthority
			} else if (permissionGranted) {
				ShuttleNotReadyCause.PermissionPresentCallFailed
			} else {
				ShuttleNotReadyCause.PermissionDenied
			}
		else -> null
	}

internal fun <R> runBoundedOutcome(timeoutMs: Long, block: () -> ShuttleOutcome<R>): ShuttleOutcome<R> {
	val latch = CountDownLatch(1)
	val value = AtomicReference<ShuttleOutcome<R>>()
	val error = AtomicReference<Throwable?>()
	val finished = AtomicBoolean(false)
	Thread {
		try {
			value.set(block())
			finished.set(true)
		} catch (e: Throwable) {
			error.set(e)
		} finally {
			latch.countDown()
		}
	}.apply { isDaemon = true; name = "Shuttle-outcome-bounded" }.start()
	val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
	if (!completed) return ShuttleOutcome.TimedOut
	error.get()?.let { return ShuttleOutcome.Failed(it) }
	return if (finished.get()) value.get() else ShuttleOutcome.TimedOut
}

data class ShuttleHealth(
	val profileId: Int,
	val running: Boolean,
	val quietMode: Boolean,
	val unlocked: Boolean,
	val forwardGrant: Boolean,
	val backwardGrant: Boolean,
	val ping: ShuttleOutcome<Boolean>,
) {
	val available: Boolean get() = running && !quietMode && unlocked && ping is ShuttleOutcome.Value

	fun diagnosticLine(): String =
		"shuttleHealth profile=$profileId running=$running quietMode=$quietMode unlocked=$unlocked " +
			"forwardGrant=$forwardGrant backwardGrant=$backwardGrant ping=${ping.diagnosticValue()} available=$available"
}

package com.yzddmr6.prismspace.shuttle

import android.content.Context
import android.os.UserHandle
import com.yzddmr6.prismspace.util.Users
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Shuttle(val context: Context, val to: UserHandle) {

	/** @return Job if launched in coroutine, otherwise null. */
	fun launch(function: Context.() -> Unit) =
			if (to == Users.current()) { function(context) } else shuttle(function)
	fun launchNoThrows(function: Context.() -> Unit): Boolean =
			if (to == Users.current()) { function(context); true } else shuttleNoThrows(function)
	fun <R> invoke(function: Context.() -> R) =
			if (to == Users.current()) context.function() else shuttle(function)
	/** If profile is not ready, just show a toast and do nothing else */
	fun <R> invokeNoThrows(function: Context.() -> R) =
			if (to == Users.current()) context.function() else shuttleNoThrows(function)

	/* Helpers to avoid redundant local variables. ("inline" is used to ensure only "Context.() -> R" function is shuttled) */
	inline fun <A> launch(with: A, crossinline function: Context.(A) -> Unit) { launch { function(with) }}
	inline fun <A> launchNoThrows(with: A, crossinline function: Context.(A) -> Unit) = launchNoThrows { function(with) }
	inline fun <A, R> invoke(with: A, crossinline function: Context.(A) -> R) = invoke { this.function(with) }
	inline fun <A, R> invokeNoThrows(with: A, crossinline function: Context.(A) -> R) = invokeNoThrows { this.function(with) }

	private fun <R> shuttle(function: Context.() -> R): R {
		val result = ShuttleProvider.call(context, to, function)
		if (result.isNotReady()) throw IllegalStateException("Shuttle not ready")
		return result.get()
	}

	private fun shuttleNoThrows(function: Context.() -> Unit): Boolean {
		val result = ShuttleProvider.call(context, to, function)
		return ! result.isNotReady()
	}

	private fun <R> shuttleNoThrows(function: Context.() -> R): R? {
		val result = ShuttleProvider.call(context, to, function)
		if (result.isNotReady()) return null
		return result.get()
	}

	/** Like [invokeNoThrows] but bounds the cross-profile IPC (returns null
	 *  on not-ready OR on timeout). Same-user runs in-process (no worker/timeout). */
	fun <R> invokeNoThrowsWithin(timeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS,
			function: Context.() -> R): R? =
			if (to == Users.current()) context.function()
			else runBounded(timeoutMs) { shuttleNoThrows(function) }

	inline fun <A, R> invokeNoThrowsWithin(timeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS,
			with: A, crossinline function: Context.(A) -> R): R? =
			invokeNoThrowsWithin(timeoutMs) { this.function(with) }

	fun <R> invokeOutcome(function: Context.() -> R): ShuttleOutcome<R> =
			if (to == Users.current()) try { ShuttleOutcome.Value(context.function()) }
			catch (e: Throwable) { ShuttleOutcome.Failed(e) }
			else shuttleOutcome(function)

	inline fun <A, R> invokeOutcome(with: A, crossinline function: Context.(A) -> R): ShuttleOutcome<R> =
			invokeOutcome { this.function(with) }

	fun <R> invokeOutcomeWithin(timeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS,
			function: Context.() -> R): ShuttleOutcome<R> =
			if (to == Users.current()) invokeOutcome(function)
			else runBoundedOutcome(timeoutMs) { shuttleOutcome(function) }

	inline fun <A, R> invokeOutcomeWithin(timeoutMs: Long = DEFAULT_SYNC_TIMEOUT_MS,
			with: A, crossinline function: Context.(A) -> R): ShuttleOutcome<R> =
			invokeOutcomeWithin(timeoutMs) { this.function(with) }

	private fun <R> shuttleOutcome(function: Context.() -> R): ShuttleOutcome<R> = try {
		val result = ShuttleProvider.call(context, to, function)
		if (result.isNotReady()) ShuttleOutcome.NotReady(result.notReadyCause!!)
		else ShuttleOutcome.Value(result.getOrNull())
	} catch (e: Throwable) {
		ShuttleOutcome.Failed(e)
	}
}

/** Default bound for one synchronous cross-profile Shuttle IPC.
 *  ~500ms under the ~5s ANR threshold; lets a slow/cold-but-ready profile finish. */
const val DEFAULT_SYNC_TIMEOUT_MS = 4_500L

/** Runs [block] on a single-use daemon thread, waiting at most [timeoutMs].
 *  in time -> block's value (legitimate null returned as null); throws in time
 *  -> rethrown on caller thread; timeout -> null, worker ABANDONED (a blocked
 *  binder transaction is not interruptible; the daemon self-terminates when the
 *  IPC returns or the process dies — bounded, non-leaking). internal = test seam. */
internal fun <R> runBounded(timeoutMs: Long, block: () -> R?): R? {
	val latch = CountDownLatch(1)
	val value = AtomicReference<R?>(null)
	val error = AtomicReference<Throwable?>(null)
	val finished = AtomicBoolean(false)
	Thread {
		try { value.set(block()); finished.set(true) }
		catch (e: Throwable) { error.set(e) }
		finally { latch.countDown() }
	}.apply { isDaemon = true; name = "Shuttle-bounded" }.start()
	val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
	if (!completed) return null
	error.get()?.let { throw it }
	return if (finished.get()) value.get() else null
}

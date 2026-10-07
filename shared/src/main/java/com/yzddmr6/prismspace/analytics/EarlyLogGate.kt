package com.yzddmr6.prismspace.analytics

import android.content.Context

/**
 * Ordering / buffering core of [DiagnosticLog]; JVM-testable (no android.util or Process calls).
 *
 * A line offered before a [Context] is obtainable is kept in a bounded buffer (oldest dropped and
 * counted on overflow). The first time a context is obtained — lazily through `acquire`, or through
 * [attach] — the buffered lines are handed to [sink] in order BEFORE the context is published, so no
 * later line can overtake them.
 */
internal class EarlyLogGate(private val capacity: Int, private val sink: (Context, String) -> Unit) {

    data class Attached(val buffered: Int, val dropped: Int)

    private val lock = Any()
    private val early = ArrayDeque<String>()
    private var dropped = 0
    @Volatile var context: Context? = null
        private set

    /** Returns non-null only when this call published the context (lazy initialisation happened here). */
    fun offer(line: String, acquire: () -> Context?): Attached? {
        context?.let { sink(it, line); return null }
        synchronized(lock) {
            context?.let { sink(it, line); return null }
            val ctx = acquire()
            if (ctx == null) {
                if (capacity <= 0) { dropped++; return null }
                if (early.size >= capacity) { early.removeFirst(); dropped++ }
                early.addLast(line)
                return null
            }
            val attached = publishLocked(ctx)
            sink(ctx, line)
            return attached
        }
    }

    /** Idempotent: null when a context was already published. */
    fun attach(ctx: Context): Attached? = synchronized(lock) { if (context != null) null else publishLocked(ctx) }

    private fun publishLocked(ctx: Context): Attached {
        val result = Attached(early.size, dropped)
        early.forEach { sink(ctx, it) }
        early.clear()
        dropped = 0
        context = ctx
        return result
    }
}

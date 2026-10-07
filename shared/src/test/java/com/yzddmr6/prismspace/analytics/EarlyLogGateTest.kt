package com.yzddmr6.prismspace.analytics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EarlyLogGateTest {

    private val context: Context = mock(Context::class.java)
    private val written = Collections.synchronizedList(ArrayList<String>())
    private fun gate(capacity: Int = 64) = EarlyLogGate(capacity) { _, line -> written += line }

    @Test fun earlyLinesAreBufferedThenWrittenInOrderBeforeTheTriggeringLine() {
        val gate = gate()
        assertNull(gate.offer("a") { null })
        assertNull(gate.offer("b") { null })
        assertEquals(emptyList<String>(), written)

        val attached = gate.offer("c") { context }
        assertEquals(EarlyLogGate.Attached(buffered = 2, dropped = 0), attached)
        assertEquals(listOf("a", "b", "c"), written)
        assertSame(context, gate.context)
    }

    @Test fun firstWriteInitialisesLazilyWithoutAnyExplicitInit() {
        val gate = gate()
        assertEquals(EarlyLogGate.Attached(0, 0), gate.offer("first") { context })
        assertNull(gate.offer("second") { error("must not acquire again") })
        assertEquals(listOf("first", "second"), written)
    }

    @Test fun overflowDropsTheOldestAndCountsThem() {
        val gate = gate(capacity = 2)
        listOf("1", "2", "3", "4").forEach { gate.offer(it) { null } }
        assertEquals(EarlyLogGate.Attached(buffered = 2, dropped = 2), gate.attach(context))
        assertEquals(listOf("3", "4"), written)
    }

    @Test fun repeatedAttachIsIdempotent() {
        val gate = gate()
        gate.offer("early") { null }
        assertEquals(EarlyLogGate.Attached(1, 0), gate.attach(context))
        assertNull(gate.attach(mock(Context::class.java)))
        assertSame(context, gate.context)
        gate.offer("late") { null }
        assertEquals(listOf("early", "late"), written)
    }

    @Test fun lazyInitialisationAfterExplicitAttachNeverHappens() {
        val gate = gate()
        gate.attach(context)
        assertNull(gate.offer("x") { error("must not acquire after attach") })
        assertEquals(listOf("x"), written)
    }

    @Test fun concurrentWritersNeverOvertakeBufferedLines() {
        repeat(20) {
            written.clear()
            val gate = gate()
            (0 until 10).forEach { i -> gate.offer("early-$i") { null } }
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val done = CountDownLatch(8)
            repeat(8) { t ->
                pool.execute {
                    start.await()
                    repeat(50) { i -> gate.offer("t$t-$i") { context } }
                    done.countDown()
                }
            }
            start.countDown()
            assertEquals(true, done.await(10, TimeUnit.SECONDS))
            pool.shutdown()
            assertEquals((0 until 10).map { "early-$it" }, written.take(10))
            assertEquals(10 + 8 * 50, written.size)
            // Each writer's own lines keep their order.
            repeat(8) { t -> assertEquals((0 until 50).map { "t$t-$it" }, written.filter { it.startsWith("t$t-") }) }
        }
    }
}

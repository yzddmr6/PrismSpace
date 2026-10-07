package com.yzddmr6.prismspace.prism.compose.nav

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A request delivered to exactly one collection and then gone. Unlike a StateFlow it is never
 * replayed to a collector started later (e.g. after the Activity is recreated in the same process),
 * so an old request can never act again. A request emitted while nobody collects waits for the next
 * collector; a newer request replaces it.
 */
class OneShotSignal<T : Any> {
    private val channel = Channel<T>(Channel.CONFLATED)
    val requests: Flow<T> = channel.receiveAsFlow()
    fun emit(value: T) { channel.trySend(value) }
}

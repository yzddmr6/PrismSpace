package com.yzddmr6.prismspace.prism.compose.vm

import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** User choices belong to a real space, independently of its latest application snapshot. */
data class SpaceBrowseOptions(
    val query: String = "",
    val systemQuery: String = "",
    val sort: SortOrder = SortOrder.Name,
    val filter: CloneFilter = CloneFilter.All,
    val showSystem: Boolean = false,
) : Serializable {
    private companion object { const val serialVersionUID = 1L }
}

internal data class SpaceReloadRequest(
    /** Null means all spaces; otherwise reload only these Android users. */
    val users: Set<Int>? = null,
    val checkSpaceFacts: Boolean = false,
    /** Diagnostics only: why the snapshot is re-read (load/resume/callback/policy). */
    val reason: String = "load",
) {
    fun merge(other: SpaceReloadRequest) = SpaceReloadRequest(
        users = if (users == null || other.users == null) null else users + other.users,
        checkSpaceFacts = checkSpaceFacts || other.checkSpaceFacts,
        reason = (reason.split('+') + other.reason.split('+')).distinct().joinToString("+"),
    )
}

/** One serial consumer; requests arriving during work are retained and merged for the next pass. */
internal class SpaceReloadQueue(
    scope: CoroutineScope,
    private val debounceMs: Long = 50,
    private val onFailure: (Exception) -> Unit,
    load: suspend (SpaceReloadRequest) -> Unit,
) {
    private val lock = Any()
    private var pending: SpaceReloadRequest? = null
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in signals) {
                if (debounceMs > 0) delay(debounceMs)
                val request = synchronized(lock) { pending.also { pending = null } } ?: continue
                try { load(request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure(error) }
            }
        }
    }

    fun request(request: SpaceReloadRequest) {
        synchronized(lock) { pending = pending?.merge(request) ?: request }
        signals.trySend(Unit)
    }
}

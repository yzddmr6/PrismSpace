package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.UserHandle
import android.os.UserManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.ProfileProvisioningFactsDto
import com.yzddmr6.prismspace.bridge.QueryProfileProvisioningFacts
import com.yzddmr6.prismspace.data.helper.installed
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.shuttle.ShuttleNotReadyCause
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome
import com.yzddmr6.prismspace.space.SpaceBridgeCause
import com.yzddmr6.prismspace.space.SpaceFacts
import com.yzddmr6.prismspace.space.SpaceProfileFacts
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.space.SpaceStateClassifier
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.util.Users.Companion.toId
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SpaceSnapshot {
    object Loading : SpaceSnapshot
    data class Loaded(val state: SpaceState) : SpaceSnapshot
    data class Failed(val failure: SpaceSnapshotFailure, val lastKnown: SpaceState?) : SpaceSnapshot
}

data class SpaceSnapshotFailure(val type: String, val detail: String?) {
    companion object {
        fun from(error: Throwable) = SpaceSnapshotFailure(
            type = error.javaClass.simpleName.ifBlank { error.javaClass.name },
            detail = error.message?.replace(Regex("\\s+"), " ")?.trim()?.take(160),
        )
    }
}

/** Process-wide observable space state. Reading [state] never performs IO. */
class SpaceStateRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = SpaceStateStores.app(appContext)
    private val facts = SpaceStateFactCollector(appContext)

    val state: StateFlow<SpaceSnapshot> get() = store.state

    suspend fun refresh(reason: String): Boolean = store.refresh(reason)

    /** A creation attempt is authorized only by its own successful fact collection. */
    suspend fun preflightCreate(): SpaceState? = store.refreshAndRead("preflight_create")

    /** A destructive route is authorized only by facts collected for that exact attempt. */
    suspend fun preflightDelete(): SpaceState? = store.refreshAndRead("preflight_delete")

    /** Most recent facts line behind the current classification; diagnostics-export only. */
    fun lastFactsDiagnosticLine(): String? = store.lastFactsLine

    /** Java bridge for legacy callers. Invoke from a worker thread only. */
    fun preflightCreateBlocking(): SpaceState? = runBlocking { preflightCreate() }

    /** Initial Activity routing may wait off-main, but must not guess if collection keeps failing. */
    fun awaitInitialStateBlocking(timeoutMs: Long): SpaceState? = runBlocking {
        withTimeoutOrNull(timeoutMs) { store.refreshAndRead("initial_route") }
    }

    /** Fresh facts for a gate decision; null when collection failed or timed out. Worker thread only. */
    fun refreshBlocking(reason: String, timeoutMs: Long): SpaceState? = runBlocking {
        withTimeoutOrNull(timeoutMs) { store.refreshAndRead(reason) }
    }

    /** Non-blocking adapter for legacy repository APIs. */
    fun currentState(): SpaceState? = (state.value as? SpaceSnapshot.Loaded)?.state

    fun userHandle(userId: Int): UserHandle? = facts.userHandle(userId)

    fun profileOwnerPackage(userId: Int): String? = facts.profileOwnerPackage(userId)

    internal companion object {
        @JvmStatic fun shouldOpenSetup(state: SpaceState?): Boolean = state == SpaceState.NoProfile
        const val TAG = "Prism.SpaceState"
    }
}

internal class SpaceStateStore(
    private val collector: suspend (String) -> SpaceState,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300L,
    private val retryMs: Long = 1_000L,
    private val onCollectionFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow<SpaceSnapshot>(SpaceSnapshot.Loading)
    private val refreshMutex = Mutex()
    private var inFlight: Deferred<Boolean>? = null
    private val invalidations = Channel<String>(Channel.CONFLATED)

    val state: StateFlow<SpaceSnapshot> = mutableState.asStateFlow()
    @Volatile var lastFactsLine: String? = null; internal set

    init {
        scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) invalidations.trySend(
                        if (mutableState.value == SpaceSnapshot.Loading) "initial" else "subscriber_resumed",
                    )
                }
        }
        scope.launch {
            for (firstReason in invalidations) {
                if (mutableState.subscriptionCount.value == 0) continue
                var reason = firstReason
                if (reason != "initial") {
                    delay(debounceMs)
                    while (true) {
                        reason = invalidations.tryReceive().getOrNull() ?: break
                    }
                }
                while (mutableState.subscriptionCount.value > 0 && !refreshOnce(reason)) {
                    delay(retryMs)
                    reason = "retry:$reason"
                }
            }
        }
    }

    suspend fun refresh(reason: String): Boolean = refreshOnce(reason)

    suspend fun refreshAndRead(reason: String): SpaceState? {
        if (!refreshOnce(reason)) return null
        return (mutableState.value as? SpaceSnapshot.Loaded)?.state
    }

    private suspend fun refreshOnce(reason: String): Boolean {
        val work = refreshMutex.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async {
                try {
                    mutableState.value = SpaceSnapshot.Loaded(collector(reason))
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    runCatching { onCollectionFailure(reason, error) }
                    val lastKnown = when (val previous = mutableState.value) {
                        is SpaceSnapshot.Loaded -> previous.state
                        is SpaceSnapshot.Failed -> previous.lastKnown
                        SpaceSnapshot.Loading -> null
                    }
                    mutableState.value = SpaceSnapshot.Failed(SpaceSnapshotFailure.from(error), lastKnown)
                    false
                }
            }.also { inFlight = it }
        }
        return try {
            work.await()
        } finally {
            refreshMutex.withLock {
                if (inFlight === work) inFlight = null
            }
        }
    }

    fun invalidate(reason: String) {
        invalidations.trySend(reason)
    }
}

private object SpaceStateStores {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var appStore: SpaceStateStore? = null

    fun app(context: Context): SpaceStateStore = appStore ?: synchronized(this) {
        appStore ?: create(context.applicationContext).also { appStore = it }
    }

    private fun create(context: Context): SpaceStateStore {
        lateinit var store: SpaceStateStore
        store = SpaceStateStore(
            collector = { reason ->
                withContext(Dispatchers.IO) {
                    val facts = SpaceStateFactCollector(context).facts()
                    SpaceStateClassifier.classify(facts, Modules.MODULE_ENGINE).also { state ->
                        val line = "reason=$reason ${facts.diagnosticLine()} -> $state"
                        store.lastFactsLine = line
                        DiagnosticLog.i(SpaceStateRepository.TAG, line)
                        SpaceBridgeAutoRecovery.maybeRecover(context, state) { userId ->
                            // The carrier exchange completes in ~1s; nudge a re-verification so the
                            // UI flips back to Healthy promptly instead of waiting out the health TTL.
                            scope.launch {
                                delay(2_500L)
                                SpaceBridgeHealthStores.app.invalidate(userId)
                                store.invalidate("auto_recover_recheck:user=$userId")
                            }
                        }
                    }
                }
            },
            scope = scope,
            onCollectionFailure = { reason, error ->
                DiagnosticLog.e(SpaceStateRepository.TAG, "state collection failed reason=$reason", error)
            },
        )
        Users.addChangeListener { action ->
            when (action) {
                android.content.Intent.ACTION_MANAGED_PROFILE_ADDED ->
                    SpaceProvisioningTracker.finished(ProvisioningEndSignal.ProfileAdded)
                android.app.admin.DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED ->
                    SpaceProvisioningTracker.finished(ProvisioningEndSignal.ProfileOwnerChanged)
            }
            store.invalidate("users:$action")
        }
        SpaceBridgeHealthStores.app.addAvailabilityListener { profileId, available ->
            store.invalidate("bridge_health:user=$profileId,available=$available")
        }
        SpaceProvisioningTracker.addListener { reason -> store.invalidate(reason) }
        return store
    }
}

/** Collects Android facts; all policy remains in the shared pure classifier. */
private class SpaceStateFactCollector(private val appContext: Context) {
    private val bridgeHealth = BridgeHealthRepository(appContext)

    fun facts(): SpaceFacts {
        runCatching { Users.refreshUsers(appContext) }
            .onFailure { DiagnosticLog.w(SpaceStateRepository.TAG, "refresh users before classification failed", it) }
        val userManager = appContext.getSystemService(UserManager::class.java)
            ?: return SpaceFacts(emptyList())
        val launcherApps = appContext.getSystemService(LauncherApps::class.java)
            ?: return SpaceFacts(emptyList())
        val profiles = runCatching { userManager.userProfiles.orEmpty() }
            .onFailure { DiagnosticLog.w(SpaceStateRepository.TAG, "profile enumeration failed", it) }
            .getOrDefault(emptyList())
            .filterNot { it == Users.current() }
        return SpaceFacts(profiles.map { profile ->
            collectProfileFacts(userManager, launcherApps, profile)
        })
    }

    fun userHandle(userId: Int): UserHandle? =
        appContext.getSystemService(UserManager::class.java)?.userProfiles
            ?.firstOrNull { it.toId() == userId }

    fun profileOwnerPackage(userId: Int): String? = userHandle(userId)?.let { profile ->
        DevicePolicies.getProfileOwnerAsUser(appContext, profile)?.orElse(null)?.packageName
    }

    private fun collectProfileFacts(
        userManager: UserManager,
        launcherApps: LauncherApps,
        profile: UserHandle,
    ): SpaceProfileFacts {
        val userId = profile.toId()
        val packagePresent = runCatching {
            LauncherAppsCompat.getApplicationInfoNoThrows(
                launcherApps, Modules.MODULE_ENGINE, MATCH_UNINSTALLED_PACKAGES, profile,
            )?.installed == true
        }.onFailure {
            DiagnosticLog.w(SpaceStateRepository.TAG, "package fact unavailable user=$userId", it)
        }.getOrDefault(false)
        val markerPresent = runCatching {
            launcherApps.getActivityList(Modules.MODULE_ENGINE, profile).orEmpty()
                .any { it.componentName.className == PrismSettingsActivity::class.java.name }
        }.onFailure {
            DiagnosticLog.w(SpaceStateRepository.TAG, "marker fact unavailable user=$userId", it)
        }.getOrDefault(false)
        val ownerPackage = runCatching {
            DevicePolicies.getProfileOwnerAsUser(appContext, profile)?.orElse(null)?.packageName
        }.onFailure {
            DiagnosticLog.w(SpaceStateRepository.TAG, "profile owner fact unavailable user=$userId", it)
        }.getOrNull()
        val running = runCatching { Users.isProfileRunning(appContext, profile) }.getOrDefault(false)
        val quiet = runCatching { Users.isProfileQuietModeEnabled(appContext, profile) }.getOrDefault(false)
        val unlocked = runCatching { userManager.isUserUnlocked(profile) }.getOrDefault(false)
        val cachedHealth = bridgeHealth.cachedHealth(profile) ?: if (
            packagePresent && running && unlocked && !quiet
        ) {
            runCatching { bridgeHealth.refreshHealth(profile) }
                .onFailure {
                    DiagnosticLog.w(SpaceStateRepository.TAG, "bridge health unavailable user=$userId", it)
                }
                .getOrNull()
        } else null
        val bridgeReady = cachedHealth?.available
        val exact = if (packagePresent && !markerPresent && bridgeReady == true) exactProfileFacts(profile) else null
        if (exact?.profileOwner == true && exact.provisionComplete) {
            SpaceProvisioningTracker.finished(ProvisioningEndSignal.ProvisioningProbe)
        }

        return SpaceProfileFacts(
            userId = userId,
            prismPackagePresent = packagePresent,
            ownershipMarkerPresent = markerPresent,
            provisioningActive = SpaceProvisioningTracker.isActive(),
            running = running,
            unlocked = unlocked,
            quietMode = quiet,
            bridgeReady = bridgeReady,
            bridgeCause = cachedHealth?.ping?.toBridgeCause() ?: SpaceBridgeCause.NotChecked,
            profileOwner = exact?.profileOwner,
            provisionComplete = exact?.provisionComplete,
            profileOwnerPackage = ownerPackage,
        )
    }

    private fun exactProfileFacts(profile: UserHandle): ProfileProvisioningFactsDto? {
        val target = BridgeTargets.profile(profile.toId()) ?: return null
        return when (val result = Bridge.inProfile(appContext, target)
            .execute(QueryProfileProvisioningFacts, timeoutMs = 1_500L)) {
            is ShuttleOutcome.Value -> result.value
            else -> null
        }
    }

    private fun ShuttleOutcome<Boolean>.toBridgeCause(): SpaceBridgeCause = when (this) {
        is ShuttleOutcome.Value -> SpaceBridgeCause.NotChecked
        is ShuttleOutcome.NotReady -> when (cause) {
            ShuttleNotReadyCause.PermissionDenied -> SpaceBridgeCause.PermissionDenied
            ShuttleNotReadyCause.UnknownAuthority -> SpaceBridgeCause.ProviderUnavailable
            ShuttleNotReadyCause.PermissionPresentCallFailed -> SpaceBridgeCause.Failed
        }
        ShuttleOutcome.TimedOut -> SpaceBridgeCause.TimedOut
        is ShuttleOutcome.Failed -> SpaceBridgeCause.Failed
        is ShuttleOutcome.Skipped -> SpaceBridgeCause.ProfileUnavailable
    }
}

private fun SpaceFacts.diagnosticLine(): String = profiles.joinToString(
    prefix = "Prism.SpaceState facts(profiles=[",
    postfix = "])",
) { p ->
    "${p.userId}:pkg=${p.prismPackagePresent},marker=${p.ownershipMarkerPresent}," +
        "owner=${p.profileOwnerPackage ?: "-"},running=${p.running},unlocked=${p.unlocked}," +
        "quiet=${p.quietMode},bridge=${p.bridgeReady}"
}

internal enum class ProvisioningEndSignal {
    ActivityResult,
    ProfileAdded,
    ProfileOwnerChanged,
    ProvisioningProbe,
}

internal class ProvisioningStateMachine(
    private val nowMs: () -> Long,
    private val timeoutMs: Long,
    private val onTimeout: () -> Unit,
) {
    private var deadlineMs = 0L

    @Synchronized fun start() {
        deadlineMs = nowMs() + timeoutMs
    }

    @Synchronized fun finish(@Suppress("UNUSED_PARAMETER") signal: ProvisioningEndSignal): Boolean {
        if (deadlineMs == 0L) return false
        if (expireIfNeeded()) return false
        deadlineMs = 0L
        return true
    }

    @Synchronized fun clear(): Boolean {
        if (deadlineMs == 0L) return false
        if (expireIfNeeded()) return false
        deadlineMs = 0L
        return true
    }

    @Synchronized fun isActive(): Boolean {
        if (deadlineMs == 0L) return false
        if (!expireIfNeeded()) return true
        return false
    }

    private fun expireIfNeeded(): Boolean {
        if (nowMs() < deadlineMs) return false
        deadlineMs = 0L
        onTimeout()
        return true
    }
}

/** Parent-process lifecycle signal for system and root provisioning hand-offs. */
object SpaceProvisioningTracker {
    private const val TIMEOUT_MS = 10 * 60_000L
    private const val TAG = "Prism.SpaceProvisioning"
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val state = ProvisioningStateMachine(
        nowMs = android.os.SystemClock::elapsedRealtime,
        timeoutMs = TIMEOUT_MS,
        onTimeout = {
            DiagnosticLog.w(TAG, "reason=provisioning_timeout")
            notifyChanged("provisioning_timeout")
        },
    )

    @JvmStatic fun markStarted() {
        state.start()
        notifyChanged("provisioning_started")
    }

    @JvmStatic fun markReturnedSuccess() = finished(ProvisioningEndSignal.ActivityResult)

    @JvmStatic fun clear() {
        if (state.clear()) {
            notifyChanged("provisioning_cleared")
        }
    }

    internal fun finished(signal: ProvisioningEndSignal) {
        if (state.finish(signal)) {
            notifyChanged("provisioning_finished:${signal.name}")
        }
    }

    fun isActive(): Boolean = state.isActive()

    internal fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    private fun notifyChanged(reason: String) {
        listeners.forEach { it(reason) }
    }
}

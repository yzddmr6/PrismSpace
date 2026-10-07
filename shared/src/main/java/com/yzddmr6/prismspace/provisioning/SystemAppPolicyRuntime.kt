package com.yzddmr6.prismspace.provisioning

import android.content.Context
import android.os.SystemClock
import android.preference.PreferenceManager
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.SelectionFinish
import com.yzddmr6.prismspace.bridge.SystemAppApplyReportDto
import com.yzddmr6.prismspace.bridge.SystemAppChoice
import com.yzddmr6.prismspace.bridge.SystemAppOverrideChange
import com.yzddmr6.prismspace.bridge.SystemAppSelectionEntry
import com.yzddmr6.prismspace.bridge.SystemAppSelectionPage
import com.yzddmr6.prismspace.bridge.clampProfileAppPageSize
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.ProfileUser
import com.yzddmr6.prismspace.util.Users

enum class ConvergeReason { Provision, Repair, Incremental, Selection }

/**
 * [HiddenOnly]: an unavailable target whose hide took effect while the platform refused the suspend
 * (HyperOS refuses it for contacts, which also carries the dialer [推测: protected role]). Hidden
 * already takes the app out of the space, so it counts as applied and is not retried every pass.
 */
enum class PackageOutcome { Ok, Unchanged, Absent, Failed, HiddenOnly }

data class PackageResult(val target: SystemAppTarget, val outcome: PackageOutcome)

data class ApplyReport(
    val results: Map<String, PackageResult>,
    val applied: Int,
    val skippedUnchanged: Int,
) {
    fun count(outcome: PackageOutcome) = results.values.count { it.outcome == outcome }
    companion object { val EMPTY = ApplyReport(emptyMap(), 0, 0) }
}

/** What the profile app list needs from the policy, computed once per page query. */
data class SystemAppListSnapshot(
    val policyHidden: Set<String>,
    val enabledLauncherPackages: Set<String>,
    val entryActions: Map<String, String> = emptyMap(),
    /** Targeted Available by the user's selection or the confirmed default set; critical excluded. */
    val policyEnabled: Set<String> = emptySet(),
) {
    companion object { val EMPTY = SystemAppListSnapshot(emptySet(), emptySet()) }
}

/**
 * Policy engine without Android dependencies: read store → seed when needed → facts → evaluate →
 * plan → execute → persist → diagnostics. Not thread-safe by itself; [SystemAppPolicyRuntime]
 * serializes every entry.
 */
internal class SystemAppPolicyEngine(
    private val persistence: SystemAppPolicyPersistence,
    private val facts: (extraPackages: Set<String>) -> SystemAppFacts,
    private val port: SystemAppStatePort,
    private val log: (String) -> Unit,
    private val userId: Int = 0,
    private val clock: () -> Long = { 0L },
) {

    fun converge(reason: ConvergeReason, provisionState: Int): ApplyReport {
        val stored = persistence.read()
        val collected = facts(stored.overrides.keys + SystemAppDefaults.packages)
        val state = ensureInitialized(stored, collected, provisionState)
        return applyPolicy(state, collected, reason, force = emptySet())
    }

    fun applySelection(
        changes: List<SystemAppOverrideChange>,
        finish: SelectionFinish?,
        provisionState: Int,
    ): SystemAppApplyReportDto {
        val stored = persistence.read()
        val collected = facts(stored.overrides.keys + SystemAppDefaults.packages + changes.map { it.pkg })
        val initial = ensureInitialized(stored, collected, provisionState)
        val ignoredCritical = changes.filter { it.pkg in collected.critical }.map { it.pkg }.distinct()
        val accepted = changes.filterNot { it.pkg in collected.critical }
        val overrides = LinkedHashMap(initial.overrides)
        accepted.forEach { change ->
            when (change.choice) {
                SystemAppChoice.Enabled -> overrides[change.pkg] = SystemAppOverride.Enabled
                SystemAppChoice.Disabled -> overrides[change.pkg] = SystemAppOverride.Disabled
                SystemAppChoice.Clear -> overrides.remove(change.pkg)
            }
        }
        val status = when (finish) {
            SelectionFinish.Confirm -> SelectionStatus.Confirmed
            // "Later" never revokes an earlier confirmation: it only ends a pending first-run prompt.
            SelectionFinish.Defer -> if (initial.status == SelectionStatus.Pending) SelectionStatus.Deferred else initial.status
            null -> initial.status
        }
        val updated = initial.copy(status = status, overrides = overrides)
        if (!persistence.write(updated)) error("Failed to persist system app selection")
        val force = accepted.mapTo(LinkedHashSet()) { it.pkg }
        val report = applyPolicy(updated, collected, ConvergeReason.Selection, force)
        // Report the packages this selection touched plus any whose target flipped with the status.
        val reported = LinkedHashSet<String>(force).apply { addAll(report.results.keys) }
        val available = ArrayList<String>(); val unavailable = ArrayList<String>()
        val absent = ArrayList<String>(); val failed = ArrayList<String>()
        reported.forEach { pkg ->
            val result = report.results[pkg]
            when {
                result == null -> when (overrides[pkg]) {
                    // Disabled on a package not installed in this space: it is truthfully not available.
                    SystemAppOverride.Disabled -> unavailable += pkg
                    // Enabled on a package the policy cannot manage (not a system package here).
                    SystemAppOverride.Enabled -> failed += pkg
                    null -> Unit
                }
                result.outcome == PackageOutcome.Absent -> absent += pkg
                result.outcome == PackageOutcome.Failed -> failed += pkg
                result.target == SystemAppTarget.Available -> available += pkg
                else -> unavailable += pkg
            }
        }
        return SystemAppApplyReportDto(available, unavailable, absent, failed, ignoredCritical)
    }

    fun selectionPage(provisionState: Int, pageIndex: Int, pageSize: Int): SystemAppSelectionPage {
        val stored = persistence.read()
        val collected = facts(stored.overrides.keys + SystemAppDefaults.packages)
        val state = ensureInitialized(stored, collected, provisionState)
        val targets = evaluate(state, collected)
        val factsByPkg = collected.facts.associateBy(PackageFact::pkg)
        val pkgs = sortedSetOf<String>().apply {
            collected.facts.filter { it.isSystem && it.hasLauncherEntry }.mapTo(this) { it.pkg }
            addAll(state.overrides.keys)
            addAll(SystemAppDefaults.packages)
            // Critical packages are always listed (flagged) so the main space never offers them,
            // including OEM ones shipped outside the system image (e.g. HyperOS DownloadProviderUi).
            addAll(collected.critical)
        }.filter { it in collected.critical || factsByPkg[it]?.isSystem != false }
        val size = clampProfileAppPageSize(pageSize)
        val start = pageIndex.coerceAtLeast(0).toLong() * size
        if (start >= pkgs.size) return SystemAppSelectionPage(state.status, emptyList(), false)
        val end = minOf(start.toInt() + size, pkgs.size)
        val entries = pkgs.subList(start.toInt(), end).map { pkg ->
            val fact = factsByPkg[pkg]
            SystemAppSelectionEntry(
                pkg = pkg,
                override = state.overrides[pkg],
                target = targets[pkg]?.kind(),
                critical = pkg in collected.critical,
                inDefault = pkg in SystemAppDefaults.packages,
                installed = fact?.installed == true,
                hasLauncherEntry = fact?.hasLauncherEntry == true,
            )
        }
        return SystemAppSelectionPage(state.status, entries, hasMore = end < pkgs.size)
    }

    fun listSnapshot(provisionState: Int): SystemAppListSnapshot {
        val stored = persistence.read()
        val collected = facts(stored.overrides.keys + SystemAppDefaults.packages)
        val state = ensureInitialized(stored, collected, provisionState)
        val targets = evaluate(state, collected)
        val hidden = targets.filterValues { it.kind() == SystemAppTarget.Unavailable }.keys
        // R5/R6: available because of the user's selection (explicit or confirmed default). Critical
        // packages (R1) are infrastructure kept by the platform invariant, never a user's clone.
        val enabled = targets.filter { (pkg, target) -> target.kind() == SystemAppTarget.Available && pkg !in collected.critical }.keys
        return SystemAppListSnapshot(hidden, collected.enabledLauncherPackages, collected.entryActions, enabled)
    }

    private fun evaluate(state: SystemAppPolicyState, collected: SystemAppFacts) = SystemAppPolicy.evaluate(PolicyInputs(
        defaultSet = effectiveDefaultSet(state.status),
        criticalSet = collected.critical,
        exemptSet = collected.exempt,
        overrides = state.overrides,
        facts = collected.facts,
    ))

    /** First contact with a space: zero-diff seeding for completed spaces, Pending for fresh ones. */
    private fun ensureInitialized(stored: SystemAppPolicyState, collected: SystemAppFacts, provisionState: Int): SystemAppPolicyState {
        if (stored.status != null) return stored
        val initialized = if (needsPolicySeeding(provisionState, stored.status)) seed(stored, collected)
            else stored.copy(status = SelectionStatus.Pending)
        if (!persistence.write(initialized)) error("Failed to persist system app policy state")
        return initialized
    }

    private fun seed(stored: SystemAppPolicyState, collected: SystemAppFacts): SystemAppPolicyState {
        val overrides = LinkedHashMap(stored.overrides)
        val lastApplied = LinkedHashMap(stored.lastApplied)
        var enabled = 0; var disabled = 0
        collected.facts.filter { it.isSystem && it.hasLauncherEntry && it.installed &&
                it.pkg !in collected.critical && it.pkg !in collected.exempt }.forEach { fact ->
            val current = safeState(fact.pkg) ?: return@forEach
            // Hidden ∧ suspended is the "removed" encoding; anything else (visible, user-frozen,
            // whole-space pause) stays available without any DPM write, preserving the freeze.
            if (current.hidden && current.suspended) {
                overrides[fact.pkg] = SystemAppOverride.Disabled; lastApplied[fact.pkg] = SystemAppTarget.Unavailable; disabled++
            } else {
                overrides[fact.pkg] = SystemAppOverride.Enabled; lastApplied[fact.pkg] = SystemAppTarget.Available; enabled++
            }
        }
        log("policy_seed u=$userId enabled=$enabled disabled=$disabled")
        return SystemAppPolicyState(SelectionStatus.Deferred, overrides, lastApplied)
    }

    private fun applyPolicy(
        state: SystemAppPolicyState,
        collected: SystemAppFacts,
        reason: ConvergeReason,
        force: Set<String>,
    ): ApplyReport {
        val started = clock()
        val defaults = effectiveDefaultSet(state.status)
        log("policy_inputs u=$userId reason=${reason.name.lowercase()} status=${state.status} facts=${collected.facts.size}" +
            " launcherSystem=${collected.facts.count { it.isSystem && it.hasLauncherEntry }} critical=${collected.critical.size}" +
            " default=${defaults.size} exempt=${collected.exempt.size}" +
            " overridesOn=${state.overrides.count { it.value == SystemAppOverride.Enabled }}" +
            " overridesOff=${state.overrides.count { it.value == SystemAppOverride.Disabled }}")
        val targets = evaluate(state, collected)
        val candidates = packagesToConverge(targets, state.lastApplied, collected.critical, force)
        val current = candidates.associateWith { safeState(it) }
        val plans = planSystemAppTransitions(targets, current, state.lastApplied, collected.critical, force)
        val results = LinkedHashMap<String, PackageResult>()
        var applied = 0
        plans.forEach { plan ->
            // A critical package absent from this device is re-checked every pass; log it only once.
            val quiet = plan.pkg in collected.critical && state.lastApplied[plan.pkg] == plan.target
            val outcome = if (plan.steps.isEmpty()) PackageOutcome.Unchanged else execute(plan, targets.getValue(plan.pkg), quiet)
            if (plan.steps.isNotEmpty()) applied++
            // Report every forced or target-changed package; healthy critical packages stay quiet.
            if (plan.pkg in force || state.lastApplied[plan.pkg] != plan.target ||
                    (outcome != PackageOutcome.Unchanged && !(quiet && outcome == PackageOutcome.Absent)))
                results[plan.pkg] = PackageResult(plan.target, outcome)
        }
        val lastApplied = LinkedHashMap<String, SystemAppTarget>()
        targets.forEach { (pkg, target) ->
            // A failed write is retried by the next pass; everything else records the decision.
            val failed = results[pkg]?.outcome == PackageOutcome.Failed
            val previous = state.lastApplied[pkg]
            if (!failed) lastApplied[pkg] = target.kind() else if (previous != null) lastApplied[pkg] = previous
        }
        if (lastApplied != state.lastApplied && !persistence.write(state.copy(lastApplied = lastApplied)))
            log("policy_persist_failed u=$userId")
        val report = ApplyReport(results, applied, skippedUnchanged = targets.size - candidates.size)
        log("policy_summary u=$userId applied=$applied skippedUnchanged=${report.skippedUnchanged}" +
            " absent=${report.count(PackageOutcome.Absent)} failed=${report.count(PackageOutcome.Failed)}" +
            " hiddenOnly=${report.count(PackageOutcome.HiddenOnly)}" +
            " ms=${(clock() - started).coerceAtLeast(0)}")
        return report
    }

    private fun execute(plan: PackagePlan, target: TargetState, quiet: Boolean): PackageOutcome {
        val executed = ArrayList<SystemAppStep>()
        val attempted = try {
            runSteps(plan.pkg, plan.steps, executed) ?: verify(plan, target, executed)
        } catch (e: IllegalArgumentException) {
            PackageOutcome.Absent      // Package vanished or is not a system package of the parent user.
        } catch (e: RuntimeException) {
            log("policy_step_error pkg=${plan.pkg} exception=${e.javaClass.simpleName}")
            PackageOutcome.Failed
        }
        val outcome = if (attempted == PackageOutcome.Failed && target == TargetState.UNAVAILABLE &&
                safeState(plan.pkg)?.let { it.installed && it.hidden } == true) PackageOutcome.HiddenOnly else attempted
        val from = plan.from?.let { "${it.installed.bit()},${it.hidden.bit()},${it.suspended.bit()}" } ?: "-"
        val result = when (outcome) {
            PackageOutcome.Ok, PackageOutcome.Unchanged -> "ok"
            PackageOutcome.Absent -> "absent"
            PackageOutcome.HiddenOnly -> "hidden_only"
            PackageOutcome.Failed -> "failed:${executed.lastOrNull() ?: plan.steps.first()}"
        }
        if (!(quiet && outcome == PackageOutcome.Absent))
            log("policy_step pkg=${plan.pkg} target=${if (plan.target == SystemAppTarget.Available) "A" else "U"}" +
                " from=$from steps=${executed.joinToString(",")} result=$result")
        return outcome
    }

    /** Both dimensions are verified after writing; DPM can report success while a flag stays set. */
    private fun verify(plan: PackagePlan, target: TargetState, executed: MutableList<SystemAppStep>): PackageOutcome {
        var after = safeState(plan.pkg)
        // Enabling installs a package whose hidden/suspended bits were unknown beforehand: re-plan once.
        if (SystemAppStep.Enable in plan.steps && !after.satisfies(target)) {
            runSteps(plan.pkg, stepsFor(target, after).filter { it != SystemAppStep.Enable }, executed)?.let { return it }
            after = safeState(plan.pkg)
        }
        return if (after.satisfies(target)) PackageOutcome.Ok else PackageOutcome.Failed
    }

    /** @return null when every step succeeded, otherwise the terminal outcome. */
    private fun runSteps(pkg: String, steps: List<SystemAppStep>, executed: MutableList<SystemAppStep>): PackageOutcome? {
        for (step in steps) {
            executed += step
            val ok = when (step) {
                SystemAppStep.Enable -> if (port.enable(pkg)) true else return PackageOutcome.Absent
                SystemAppStep.Unhide -> port.setHidden(pkg, false)
                SystemAppStep.Hide -> port.setHidden(pkg, true)
                SystemAppStep.Unsuspend -> port.setSuspended(pkg, false)
                SystemAppStep.Suspend -> port.setSuspended(pkg, true)
            }
            if (!ok) return PackageOutcome.Failed
        }
        return null
    }

    private fun safeState(pkg: String): PackageState? = try { port.state(pkg) }
        catch (e: IllegalArgumentException) { null }
        catch (e: SecurityException) { null }

    private fun Boolean.bit() = if (this) 1 else 0
}

/**
 * Profile-side entry of the system app policy. Every call is serialized: the incremental
 * provisioning worker thread and bridge commands can arrive concurrently.
 */
@ProfileUser object SystemAppPolicyRuntime {

    @JvmStatic @Synchronized
    fun converge(context: Context, policies: DevicePolicies, reason: ConvergeReason, provisionState: Int): ApplyReport {
        if (!insideOwnedProfile(policies)) return convergeCriticalOnly(context, policies, reason)
        return engine(context, policies).converge(reason, provisionState)
    }

    @JvmStatic @Synchronized
    fun applySelection(context: Context, changes: List<SystemAppOverrideChange>, finish: SelectionFinish?): SystemAppApplyReportDto {
        val policies = DevicePolicies(context)
        check(insideOwnedProfile(policies)) { "System app selection only applies inside a dual space" }
        return engine(context, policies).applySelection(changes, finish, provisionState(context))
    }

    @JvmStatic @Synchronized
    fun querySelectionPage(context: Context, pageIndex: Int, pageSize: Int): SystemAppSelectionPage {
        val policies = DevicePolicies(context)
        check(insideOwnedProfile(policies)) { "System app selection only applies inside a dual space" }
        return engine(context, policies).selectionPage(provisionState(context), pageIndex, pageSize)
    }

    @JvmStatic @Synchronized
    fun listSnapshot(context: Context): SystemAppListSnapshot {
        val policies = DevicePolicies(context)
        if (!insideOwnedProfile(policies)) return SystemAppListSnapshot.EMPTY
        return engine(context, policies).listSnapshot(provisionState(context))
    }

    /** Pruning only ever happens in a managed profile PrismSpace owns; never in the parent user. */
    private fun insideOwnedProfile(policies: DevicePolicies): Boolean =
        !Users.isParentProfile() && runCatching { policies.isProfileOwner && policies.isManagedProfile }.getOrDefault(false)

    /** The parent user is never pruned: only the PR-A critical invariant is kept there. */
    private fun convergeCriticalOnly(context: Context, policies: DevicePolicies, reason: ConvergeReason): ApplyReport {
        if (!runCatching { policies.isProfileOrDeviceOwnerOnCallingUser }.getOrDefault(false)) return ApplyReport.EMPTY
        val critical = SystemAppsManager.detectCriticalSystemPackages(context.packageManager)
        val engine = SystemAppPolicyEngine(
            persistence = InMemorySystemAppPolicyPersistence(SystemAppPolicyState(status = SelectionStatus.Deferred)),
            facts = { SystemAppFacts(emptyList(), critical, emptySet(), emptySet()) },
            port = DpmSystemAppStatePort(policies, context.packageManager),
            log = { DiagnosticLog.i(TAG, it) },
            userId = Users.currentId(),
            clock = SystemClock::elapsedRealtime,
        )
        DiagnosticLog.i(TAG, "policy_parent_critical_only reason=${reason.name.lowercase()}")
        return engine.converge(reason, provisionState = FIRST_COMPLETED_POST_PROVISION_STATE)
    }

    private fun engine(context: Context, policies: DevicePolicies) = SystemAppPolicyEngine(
        persistence = SharedPrefsSystemAppPolicyPersistence(context),
        facts = { extra -> SystemAppFactsCollector.collect(context, extra) },
        port = DpmSystemAppStatePort(policies, context.packageManager),
        log = { DiagnosticLog.i(TAG, it) },
        userId = Users.currentId(),
        clock = SystemClock::elapsedRealtime,
    )

    /** Same default-preference key the engine module's provisioning state machine owns. */
    private fun provisionState(context: Context): Int =
        @Suppress("DEPRECATION") PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_KEY_PROVISION_STATE, 0)

    private const val PREF_KEY_PROVISION_STATE = "provision.state"
    private const val TAG = "Prism.SysAppPolicy"
}

internal class InMemorySystemAppPolicyPersistence(private var state: SystemAppPolicyState = SystemAppPolicyState()) :
    SystemAppPolicyPersistence {
    var writes = 0; private set
    override fun read() = state
    override fun write(state: SystemAppPolicyState): Boolean { this.state = state; writes++; return true }
}

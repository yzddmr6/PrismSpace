package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.content.pm.LauncherApps
import android.os.SystemClock
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.DeviceAdmins
import com.yzddmr6.prismspace.util.Hacks
import com.yzddmr6.prismspace.util.Modules
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import com.yzddmr6.prismspace.prism.compose.vm.CapabilityRepositoryProvider
import com.yzddmr6.prismspace.prism.compose.vm.ShizukuUtil
import com.yzddmr6.prismspace.prism.service.ProfileEntryLauncher
import com.yzddmr6.prismspace.settings.PrismSettingsActivity
import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.space.SpaceStateClassifier
import eu.chainfire.libsuperuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Privileged-transport-gated create/delete of PrismSpace-managed profile spaces. PUBLIC APIs only;
 *  pure parsing/decisions delegated to SpaceProvisioningParsers. Activity-free.
 *  The command transaction is transport-agnostic; only the shell channel differs (Shizuku or su). */
object SpaceProvisioningEngine {

    private const val DEFAULT_MAX_USERS_SETPROP = 10  // historical fw.max_users AOSP default; used only when the real cap is Unknown

    private fun probeSu(): Boolean = isRootOutput(Shell.SU.run("id"))

    /** Resolves exactly one transport per operation; never falls back mid-transaction. */
    private fun resolveShell(context: Context): PrivilegedShell? {
        val capabilities = CapabilityRepositoryProvider.get(context)
        val shizukuAuthorized = ShizukuUtil.isAuthorized()
        var suAvailable: Boolean? = null  // null = not probed (Shizuku already won)
        val transport = chooseTransport(shizukuAuthorized) { probeSu().also { suAvailable = it } }
        DiagnosticLog.i(
            TAG,
            "transport resolve: shizukuAuthorized=$shizukuAuthorized suAvailable=$suAvailable chosen=${transport?.name ?: "none"}",
        )
        return when (transport) {
            PrivilegedTransport.SHIZUKU -> {
                capabilities.markShizukuReady()
                ShizukuPrivilegedShell()
            }
            PrivilegedTransport.SU -> {
                capabilities.markRootReady()
                SuPrivilegedShell()
            }
            null -> {
                capabilities.markRootUnavailable()
                null
            }
        }
    }

    private fun maxUsersProperty(): String? =
        (Hacks.SystemProperties_get.invoke(MAX_USERS_PROPERTY).statically() as? String)?.takeIf { it.isNotBlank() }

    private fun maxUsers(): Int? {
        val property = maxUsersProperty()?.toIntOrNull()
        val resource = runCatching {
                val r = android.content.res.Resources.getSystem()
                val id = r.getIdentifier("config_multiuserMaximumUsers", "integer", "android")
                if (id == 0) null else r.getInteger(id)
            }.getOrNull()
        return effectiveMaxUsers(property, resource)
    }

    fun probeMaxSpaces(): SpaceCapProbe =
        computeCap(maxUsers(), Users.getProfilesManagedByPrism().size)

    @JvmStatic fun createSpaceBlocking(context: Context): CreateSpaceResult =
        runBlocking { createSpace(context) }

    suspend fun createSpace(context: Context): CreateSpaceResult = withContext(Dispatchers.IO) {
        val stateRepository = SpaceStateRepository(context)
        val preflight = stateRepository.preflightCreate()
            ?: return@withContext CreateSpaceResult.StateRefreshFailed
        if (!SpaceStateClassifier.ownProfileAbsent(preflight)) {
            DiagnosticLog.w(TAG, "privileged create blocked by state=$preflight")
            return@withContext CreateSpaceResult.BlockedByState(preflight)
        }
        val shell = resolveShell(context) ?: return@withContext CreateSpaceResult.RootUnavailable
        val probe = probeMaxSpaces()
        (probe as? SpaceCapProbe.Known)
            ?.takeIf { it.current >= it.max }
            ?.let { return@withContext CreateSpaceResult.CapReached(it.max) }
        val cap = (probe as? SpaceCapProbe.Known)?.max ?: DEFAULT_MAX_USERS_SETPROP
        val maxUsersOriginal = maxUsersProperty()
        val admin = DeviceAdmins.getComponentName(context).flattenToString()
        val command = buildRootProvisioningCommand(
            RootProvisioningCommandInput(
                parentUserId = Users.currentId(),
                temporaryMaxUsers = cap,
                packageName = Modules.MODULE_ENGINE,
                adminComponent = admin,
                maxUsersOriginal = maxUsersOriginal,
            ),
        )
        ProvisioningSideEffects.logMaxUsersWrite(maxUsersOriginal, cap)
        SpaceProvisioningTracker.markStarted()
        val output = try {
            runDetachedProvisioningTransaction(shell, command)
        } catch (e: RuntimeException) {
            DiagnosticLog.e(TAG, "privileged provisioning shell failed transport=${shell.name}", e)
            SpaceProvisioningTracker.clear()
            return@withContext CreateSpaceResult.Failed(e.message, analyticsPhase = 2)
        }
        val create = parsePmCreateOutput(output)
        when (create) {
            PmCreateOutcome.LimitReached -> {
                DiagnosticLog.w(TAG, "privileged create blocked: device max-users cap=$cap transport=${shell.name}")
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.CapReached(cap)
            }
            PmCreateOutcome.ManagedProfileLimit -> {
                DiagnosticLog.w(TAG, "privileged create blocked: managed profile slot occupied transport=${shell.name}")
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.ManagedProfileLimitReached
            }
            is PmCreateOutcome.Failed -> {
                SpaceProvisioningTracker.clear()
                return@withContext CreateSpaceResult.Failed(create.reason, analyticsPhase = 1)
            }
            is PmCreateOutcome.Created -> Unit
        }
        val pid = (create as PmCreateOutcome.Created).userId
        if (!provisioningCompleted(output, pid)) {
            SpaceProvisioningTracker.clear()
            val reason = provisioningFailure(output) ?: "provisioning transaction incomplete"
            DiagnosticLog.w(TAG, "privileged create incomplete user=$pid transport=${shell.name} reason=$reason")
            return@withContext CreateSpaceResult.Failed(reason, analyticsPhase = 2)
        }
        // The privileged path never runs the system provisioning flow, so no provisioning
        // broadcast is guaranteed (HyperOS 1 evidence). Drive profile-side convergence through
        // the always-on trampoline — the channel that does not depend on ROM behavior.
        val pending = UserHandles.of(pid)
        ProfileEntryLauncher.startConvergence(context, pending)
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        // Success is convergence, not "transaction exited 0": wait until the profile-side
        // launcher entry is actually enabled (the marker definition itself). Package
        // resolvability alone passed on a half-provisioned HyperOS 1 profile — never again.
        if (!awaitProfileConvergence(la, pending)) {
            SpaceProvisioningTracker.clear()
            DiagnosticLog.w(TAG, "privileged create convergence timeout user=$pid transport=${shell.name}")
            return@withContext CreateSpaceResult.ConvergenceTimeout(pid)
        }
        SpaceProvisioningTracker.markReturnedSuccess()
        DiagnosticLog.i(TAG, "privileged create success user=$pid transport=${shell.name}")
        return@withContext CreateSpaceResult.Success(pid)
    }

    private suspend fun awaitProfileConvergence(la: LauncherApps, profile: android.os.UserHandle): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PROFILE_CONVERGENCE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val converged = runCatching {
                la.getActivityList(Modules.MODULE_ENGINE, profile).orEmpty()
                    .any { it.componentName.className == PrismSettingsActivity::class.java.name }
            }.getOrDefault(false)
            if (converged) return true
            delay(PROFILE_CONVERGENCE_POLL_MS)
        }
        return false
    }

    suspend fun deleteSpace(context: Context, space: PrismSpace): DeleteSpaceResult = withContext(Dispatchers.IO) {
        val shell = resolveShell(context) ?: return@withContext DeleteSpaceResult.RootUnavailable
        val output = shell.run(buildVerifiedRootRemovalCommand(space.userId, Modules.MODULE_ENGINE))
        if (rootRemovalOwnerMismatch(output)) {
            DiagnosticLog.w(TAG, "privileged delete refused: owner mismatch user=${space.userId} transport=${shell.name}")
            return@withContext DeleteSpaceResult.ManualRemovalRequired("PrismSpace is not profile owner")
        }
        when (val r = parsePmRemoveOutput(output)) {
            PmRemoveOutcome.Removed -> {
                SpaceStateRepository(context).refresh("privileged_delete_success")
                DiagnosticLog.i(TAG, "privileged delete success user=${space.userId} transport=${shell.name}")
                DeleteSpaceResult.Success
            }
            is PmRemoveOutcome.Failed -> {
                DiagnosticLog.w(TAG, "privileged delete failed user=${space.userId} transport=${shell.name} reason=${r.reason}")
                DeleteSpaceResult.Failed(r.reason)
            }
        }
    }

    /**
     * Runs the detached transaction and polls its output file. The file lives in
     * /data/local/tmp because a shell-uid transaction cannot write the app-private
     * directory (0700), while every transport can write there and the app can read
     * it back; removal goes through the transport since the file is not ours.
     */
    private suspend fun runDetachedProvisioningTransaction(shell: PrivilegedShell, command: String): List<String>? {
        val outputFile = File(ROOT_TRANSACTION_OUTPUT_PATH)
        runCatching { outputFile.delete() }   // stale output from an older run must not fake completion
        val pid = shell.run(detachedRootProvisioningLauncher(command, outputFile.absolutePath))
            ?.asSequence()
            ?.map(String::trim)
            ?.mapNotNull(String::toLongOrNull)
            ?.lastOrNull()
            ?: return null
        val deadline = SystemClock.elapsedRealtime() + ROOT_TRANSACTION_TIMEOUT_MS
        var lines = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lines = runCatching { outputFile.readLines() }.getOrDefault(emptyList())
            if (provisioningTransactionFinished(lines)) {
                shell.run("rm -f ${shellQuote(outputFile.absolutePath)}")
                return lines
            }
            delay(ROOT_TRANSACTION_POLL_MS)
        }
        shell.run("kill -TERM $pid")
        delay(ROOT_TRANSACTION_POLL_MS)
        lines = runCatching { outputFile.readLines() }.getOrDefault(lines)
        shell.run("rm -f ${shellQuote(outputFile.absolutePath)}")
        return lines + "PRISM_PROVISION_FAILED stage=timeout"
    }

    private const val TAG = "Prism.SpaceProvision"
    private const val ROOT_TRANSACTION_OUTPUT_PATH = "/data/local/tmp/prism-provisioning-transaction.log"
    private const val ROOT_TRANSACTION_TIMEOUT_MS = 120_000L
    private const val ROOT_TRANSACTION_POLL_MS = 200L
    private const val PROFILE_CONVERGENCE_TIMEOUT_MS = 45_000L
    private const val PROFILE_CONVERGENCE_POLL_MS = 500L
}

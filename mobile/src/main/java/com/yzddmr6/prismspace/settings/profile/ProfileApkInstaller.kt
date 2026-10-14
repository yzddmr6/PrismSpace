package com.yzddmr6.prismspace.settings.profile

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import android.widget.Toast
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.service.FileTransferPolicy
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.bridge.Bridge
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.CompleteClonePreparation
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground PackageInstaller session for a cloned app's copied APK set (base + splits) that the
 * 普通模式 clone left in the dual space's Download/PrismSpace/.
 *
 * Runs inside the work profile FROM A FOREGROUND screen (the 棱镜-双开空间 entry), which is what makes
 * no-privilege install possible on Android 15+:
 *  - foreground → dodges the BAL block that kills launching the installer from the main space;
 *  - the system "confirm install" UI is routed by PackageInstaller to the real system installer, NOT
 *    via an ACTION_VIEW resolve — so a hijacked APK default handler (e.g. Termux) can't intercept it;
 *    a hook that re-addresses it to an installer this space lacks is answered by [startAddressed];
 *  - all splits go into one session → split apps (X/Twitter) actually install.
 */
object ProfileApkInstaller {

    private const val TAG = "Prism.PAI"
    private const val ACTION_RESULT = "com.yzddmr6.prismspace.action.PROFILE_INSTALL_RESULT"
    private const val EXTRA_PACKAGE = "package"

    /**
     * What we know about a session we started, kept until its result arrives. The timing is the only
     * way to tell a vendor installer's own refusal from a real user cancel: both report
     * `INSTALL_FAILED_ABORTED: User rejected permissions`.
     */
    private class PendingSession(val apks: List<Uri>) {
        @Volatile var sessionId: Int = -1
        /** elapsedRealtime when the confirm activity was launched; null until then. */
        @Volatile var pendingAt: Long? = null
        /** "<package>/<versionName>" of the confirmation activity, for diagnostics and the dialog. */
        @Volatile var installerLabel: String? = null
        /** The confirmation as it actually started; started again when Install is tapped while it waits. */
        @Volatile var confirm: Intent? = null
    }

    /** The one in-flight install per package, see [repeatInstallTap]. */
    private val sessions = ConcurrentHashMap<String, PendingSession>()

    private val outcomeState = MutableStateFlow<ProfileInstallOutcome?>(null)

    /**
     * Latest install outcome that still needs the user's attention (refusal or plain failure).
     * The profile entry screen renders it as a dialog; success and user cancel are toasts.
     */
    val lastOutcome: StateFlow<ProfileInstallOutcome?> = outcomeState.asStateFlow()

    /** Dismiss the dialog. */
    fun consumeOutcome() { outcomeState.value = null }

    /** The single-file APK set behind [lastOutcome], offered to the system installer by the dialog. */
    @Volatile private var refusedApk: Uri? = null
    @Volatile private var refusedPackage: String? = null

    /** True when the copied base/split APK files for this package still exist in this space. */
    fun hasCopiedApkSet(context: Context, pkg: String, label: String): Boolean =
        copiedApkSet(context.applicationContext, pkg, label).isNotEmpty()

    /**
     * Install the copied APK set for [pkg] cloned under [label]. The clone wrote files named
     * "<safeBase>.apk" + "<safeBase>.splitN.apk" where safeBase is the stable package name.
     * The former label-package namespace remains readable for already prepared transfers.
     * Must be called from a foreground context (the entry screen) so the confirm dialog can launch.
     */
    fun install(context: Context, pkg: String, label: String) {
        val appCtx = context.applicationContext
        val loc = PrismLocale.wrap(context)
        DiagnosticLog.i(TAG, "profile apk install requested pkg=$pkg label=$label")
        sessions[pkg]?.let { inFlight -> if (resumeInFlight(appCtx, loc, pkg, inFlight)) return }
        // A no-privilege session install needs PrismSpace's own "install unknown apps" op.
        // Some ROMs block immediately when it is missing, so guide the user to grant it first.
        // Cleared DISALLOW_INSTALL_UNKNOWN_SOURCES is necessary but not sufficient.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appCtx.packageManager.canRequestPackageInstalls()) {
            DiagnosticLog.i(TAG, "profile apk install needs unknown-source permission pkg=$pkg")
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appCtx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Toast.makeText(context, loc.getString(R.string.lz_pf_install_need_perm), Toast.LENGTH_LONG).show()
            return
        }
        val safeBase = safeBases(label, pkg).first()
        val uris = copiedApkSet(appCtx, pkg, label)
        if (uris.isEmpty()) {
            DiagnosticLog.w(TAG, "profile apk install has no copied apk set pkg=$pkg safeBase=$safeBase")
            Toast.makeText(context, loc.getString(R.string.lz_pf_install_no_apk), Toast.LENGTH_LONG).show(); return
        }
        registerResultReceiver(appCtx)
        val pending = PendingSession(uris)
        sessions[pkg] = pending
        DiagnosticLog.i(TAG, "profile apk install session start pkg=$pkg files=${uris.size}")
        Toast.makeText(context, loc.getString(R.string.lz_pf_install_started), Toast.LENGTH_SHORT).show()
        Thread {
            var committed = false
            try {
                val installer = appCtx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                runCatching { params.setAppPackageName(pkg) }
                val sessionId = installer.createSession(params)
                pending.sessionId = sessionId
                installer.openSession(sessionId).use { session ->
                    uris.forEachIndexed { i, uri ->
                        appCtx.contentResolver.openInputStream(uri)?.use { input ->
                            session.openWrite("split$i.apk", 0, -1).use { out ->
                                input.copyTo(out); session.fsync(out)
                            }
                        } ?: throw IllegalStateException("cannot read $uri")
                    }
                    val callback = Intent(ACTION_RESULT)
                        .setPackage(appCtx.packageName)
                        .putExtra(EXTRA_PACKAGE, pkg)
                    val pi = PendingIntent.getBroadcast(
                        appCtx, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                    committed = true
                    DiagnosticLog.i(TAG, "profile apk install session committed pkg=$pkg sessionId=$sessionId")
                }
            } catch (e: Exception) {
                DiagnosticLog.e(TAG, "session install failed for $pkg", e)
                if (committed) return@Thread    // The system owns it now and reports its outcome.
                sessions.remove(pkg, pending)
                abandon(appCtx, pending.sessionId)
                showToast(appCtx, loc.getString(R.string.lz_pf_install_failed, e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

    /**
     * Install tapped again while [inFlight] has not finished: never stage the suite a second time.
     * False only when the system no longer has its session, so a fresh one may start.
     */
    private fun resumeInFlight(appCtx: Context, loc: Context, pkg: String, inFlight: PendingSession): Boolean {
        val sessionId = inFlight.sessionId
        val alive = if (sessionId < 0) null else runCatching {
            appCtx.packageManager.packageInstaller.getSessionInfo(sessionId) != null
        }.getOrDefault(true)
        val confirm = inFlight.confirm
        val tap = repeatInstallTap(alive, confirmationShown = confirm != null)
        DiagnosticLog.i(TAG, "profile apk install already in flight pkg=$pkg sessionId=$sessionId tap=$tap")
        when (tap) {
            RepeatInstallTap.StartOver -> { sessions.remove(pkg, inFlight); return false }
            RepeatInstallTap.StillPreparing ->
                Toast.makeText(appCtx, loc.getString(R.string.lz_pf_install_started), Toast.LENGTH_SHORT).show()
            RepeatInstallTap.ShowConfirmation -> confirm?.let { showConfirmation(appCtx, loc, pkg, inFlight, sessionId, it) }
        }
        return true
    }

    /**
     * The suite to install: the URIs the ledger recorded when this suite was published (every one must
     * still exist — a partial split set cannot install). Only without a ledger suite (cloned by an
     * older version) does it fall back to the exact canonical file names, read-only.
     */
    private fun copiedApkSet(context: Context, pkg: String, label: String): List<Uri> {
        val recorded = runCatching { TransferLedger.apkSuite(context, pkg)?.apkUris.orEmpty() }.getOrDefault(emptyList())
        if (recorded.isNotEmpty()) {
            val uris = recorded.map(Uri::parse)
            val complete = uris.all { exists(context, it) }
            DiagnosticLog.i(TAG, "profile apk set from ledger pkg=$pkg files=${uris.size} complete=$complete")
            return if (complete) uris else emptyList()
        }
        return queryApkSet(context, safeBases(label, pkg))
    }

    private fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** base + splits in Download/PrismSpace named exactly "<base>.apk" / "<base>.splitN.apk" (no MediaStore "(1)" dupes). */
    private fun queryApkSet(context: Context, safeBases: List<String>): List<Uri> {
        safeBases.forEach { safeBase ->
            val found = queryExactApkSet(context, safeBase)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun queryExactApkSet(context: Context, safeBase: String): List<Uri> {
        val out = ArrayList<Uri>()
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%Download/PrismSpace/%", "$safeBase%.apk")
        val exact = Regex("^${Regex.escape(safeBase)}(\\.split\\d+)?\\.apk$")
        runCatching {
            context.contentResolver.query(coll, proj, sel, args, "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (exact.matches(c.getString(nameCol))) out.add(ContentUris.withAppendedId(coll, c.getLong(idCol)))
                }
            }
        }.onFailure { DiagnosticLog.e(TAG, "queryApkSet failed", it) }
        return out
    }

    private fun safeBases(label: String, pkg: String): List<String> = listOf(
        FileTransferPolicy.safeDisplayName(pkg),
        FileTransferPolicy.safeDisplayName("$label-$pkg"),
    ).distinct()

    @Volatile private var receiverRegistered = false
    private fun registerResultReceiver(appCtx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val loc = PrismLocale.wrap(c)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        DiagnosticLog.i(TAG, "profile apk install pending user confirm")
                        // Foreground confirm — the user just tapped 安装, so the app is foreground and BAL allows it.
                        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        val pending = packageName?.let { sessions[it] }?.takeIf { isStatusFor(it.sessionId, sessionId) }
                        showConfirmation(c, loc, packageName, pending, sessionId, confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        packageName?.let { release(it, sessionId) }
                        DiagnosticLog.i(TAG, "profile apk install success pkg=$packageName")
                        completeInstall(c, loc, packageName)
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status=$status"
                        val pending = packageName?.let { release(it, sessionId) }
                        if (packageName == null || pending == null) {
                            // A session already given up on, e.g. abandoned because nothing could confirm it.
                            DiagnosticLog.i(TAG, "profile apk install status for untracked session ignored pkg=$packageName sessionId=$sessionId status=$status message=$msg")
                            return
                        }
                        // A vendor installer may install the app itself and still abort the session
                        // (cf. F-Droid #2837), so believe the package state, not the status code.
                        val installedNow = isInstalledHere(c, packageName)
                        val elapsedMs = pending.pendingAt?.let { SystemClock.elapsedRealtime() - it }
                        // The APK set lives in MediaStore (content URIs); there is no file path to feed
                        // getPackageArchiveInfo cheaply, so the version comparison is skipped and the
                        // installed flag decides. Only packages absent from this profile are offered
                        // for install, so the flag flipping to true means this session landed.
                        val apks = pending.apks
                        val outcome = profileInstallOutcome(
                            status = status,
                            message = msg,
                            installedNow = installedNow,
                            elapsedMs = elapsedMs,
                            apkCount = apks.size,
                            installerLabel = pending.installerLabel,
                        )
                        when (outcome) {
                            is ProfileInstallOutcome.Succeeded -> {
                                DiagnosticLog.w(TAG, "profile apk install verdict=Succeeded despite status=$status pkg=$packageName message=$msg")
                                completeInstall(c, loc, packageName)
                            }
                            is ProfileInstallOutcome.Refused -> {
                                DiagnosticLog.w(TAG, "profile apk install refused by system installer status=$status message=$msg elapsedMs=$elapsedMs installer=${pending.installerLabel}")
                                refusedApk = apks.singleOrNull()
                                refusedPackage = packageName
                                outcomeState.value = outcome
                            }
                            is ProfileInstallOutcome.Cancelled -> {
                                DiagnosticLog.i(TAG, "profile apk install verdict=UserCancelled pkg=$packageName elapsedMs=$elapsedMs")
                                Toast.makeText(c, loc.getString(R.string.lz_pf_install_cancelled), Toast.LENGTH_LONG).show()
                            }
                            is ProfileInstallOutcome.Failed -> {
                                DiagnosticLog.w(TAG, "profile apk install failed status=$status message=$msg")
                                outcomeState.value = outcome
                            }
                            is ProfileInstallOutcome.ConfirmUnavailable -> Unit     // Only showConfirmation decides this.
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") appCtx.registerReceiver(receiver, filter)
    }

    /**
     * A verified install: tell the user and let the parent close the pending clone preparation.
     * Reached from STATUS_SUCCESS and from an aborted session whose package is installed anyway.
     */
    private fun BroadcastReceiver.completeInstall(c: Context, loc: Context, packageName: String?) {
        Toast.makeText(c, loc.getString(R.string.lz_pf_install_success), Toast.LENGTH_LONG).show()
        if (packageName.isNullOrBlank()) return
        val async = goAsync()
        Thread {
            try {
                val target = BridgeTargets.parentFresh(c.applicationContext)
                val outcome = target?.let {
                    Bridge.inParent(c.applicationContext, it)
                        .execute(CompleteClonePreparation(packageName))
                }
                DiagnosticLog.i(TAG, "parent pending clone completion pkg=$packageName outcome=${outcome?.javaClass?.simpleName}")
            } catch (error: Throwable) {
                DiagnosticLog.e(TAG, "parent pending clone completion failed pkg=$packageName", error)
            } finally {
                async.finish()
            }
        }.start()
    }

    /** Is [pkg] installed for this user right now? Package state, not the session's own claim. */
    private fun isInstalledHere(c: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        info.flags and ApplicationInfo.FLAG_INSTALLED != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** "<package>/<versionName>" of the activity the system routed the confirmation to. */
    private fun confirmInstallerLabel(c: Context, confirm: Intent): String? = runCatching {
        val pm = c.packageManager
        @Suppress("DEPRECATION") val resolved = pm.resolveActivity(confirm, 0)
        val installerPkg = resolved?.activityInfo?.packageName ?: return@runCatching null
        val version = runCatching { pm.getPackageInfo(installerPkg, 0).versionName }.getOrNull()
        if (version.isNullOrBlank()) installerPkg else "$installerPkg/$version"
    }.getOrNull()

    /**
     * Put a session's confirmation on screen, or end the session and say why: a session whose
     * confirmation no screen can show never finishes, it only keeps a staged copy of the APK set.
     */
    private fun showConfirmation(c: Context, loc: Context, pkg: String?, pending: PendingSession?, sessionId: Int, confirm: Intent?) {
        val shown = confirm?.let { startAddressed(c, it) }
        val installer = (shown ?: confirm)?.let { confirmInstallerLabel(c, it) } ?: confirm?.`package`
        if (shown == null) {
            DiagnosticLog.e(TAG, "profile apk install confirm unavailable installer=$installer sessionId=$sessionId hasIntent=${confirm != null}, abandoning")
            if (pkg != null && pending != null) sessions.remove(pkg, pending)
            abandon(c, sessionId)
            outcomeState.value = ProfileInstallOutcome.ConfirmUnavailable(installer)
            return
        }
        if (shown !== confirm) Toast.makeText(c, loc.getString(R.string.lz_pf_install_confirm_direct), Toast.LENGTH_LONG).show()
        if (pending != null) {
            // The clock starts only once the confirmation is really on screen:
            // an abort arriving before a human could answer is the installer's own.
            if (pending.pendingAt == null) pending.pendingAt = SystemClock.elapsedRealtime()
            pending.installerLabel = installer
            pending.confirm = shown
        }
        DiagnosticLog.i(TAG, "profile apk install confirm launched installer=$installer sessionId=$sessionId direct=${shown !== confirm}")
    }

    /**
     * Start [intent], addressed by package to one installer; returns the intent that actually started.
     *
     * First as addressed, so an installer the user routed it to that this space really has still gets
     * it. A hook that re-addresses installs to a preferred installer without checking the user leaves
     * it resolving to nothing here (InxLocker sending it to an InstallerX only the main space has,
     * #29); the addressee's own activity in this user is then started explicitly, which such hooks
     * leave alone.
     */
    private fun startAddressed(c: Context, intent: Intent): Intent? {
        val routed = runCatching { c.startActivity(intent) }
        if (routed.isSuccess) return intent
        val direct = if (intent.component != null) null else addresseeActivity(c, intent)?.let { Intent(intent).setComponent(it) }
        DiagnosticLog.w(TAG, "profile apk install start as addressed failed target=${intent.`package`} direct=${direct?.component?.flattenToShortString()}",
            routed.exceptionOrNull())
        if (direct == null) return null
        return runCatching { c.startActivity(direct) }.fold(
            onSuccess = { direct },
            onFailure = { DiagnosticLog.e(TAG, "profile apk install direct start failed", it); null },
        )
    }

    /** The matching activity of the package [intent] is addressed to, resolved in this user. */
    private fun addresseeActivity(c: Context, intent: Intent): ComponentName? {
        val pkg = intent.`package` ?: return null
        @Suppress("DEPRECATION")
        val matches = runCatching { c.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        val activity = matches?.firstOrNull { it.activityInfo?.packageName == pkg }?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }

    /** Stop tracking [pkg]'s install if the status is about its session; null for any other session. */
    private fun release(pkg: String, statusSessionId: Int): PendingSession? =
        sessions[pkg]?.takeIf { isStatusFor(it.sessionId, statusSessionId) && sessions.remove(pkg, it) }

    /** Drop a session nothing will finish, rather than leave its staged copy until the system reaps it. */
    private fun abandon(c: Context, sessionId: Int) {
        if (sessionId < 0) return
        runCatching { c.packageManager.packageInstaller.abandonSession(sessionId) }
            .onFailure { DiagnosticLog.w(TAG, "profile apk install abandon failed sessionId=$sessionId", it) }
    }

    /**
     * Hand the single copied APK to the system installer after the session was refused. Same probe
     * as SystemAppsManager uses for the critical installer activity, so a hijacked APK default
     * handler cannot take this over.
     */
    fun openRefusedWithSystemInstaller(context: Context) {
        val loc = PrismLocale.wrap(context)
        val uri = refusedApk
        val installerPkg = systemInstallerPackage(context)
        if (uri == null || installerPkg == null) {
            DiagnosticLog.w(TAG, "profile apk install open with system installer unavailable pkg=$refusedPackage installer=$installerPkg hasApk=${uri != null}")
            Toast.makeText(context, loc.getString(R.string.lz_pf_open_fail), Toast.LENGTH_LONG).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .setPackage(installerPkg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // This button promises the system installer: address its activity, not just its package (#29).
        addresseeActivity(context, view)?.let { view.component = it }
        runCatching { context.startActivity(view) }
            .onSuccess { DiagnosticLog.i(TAG, "profile apk install open with system installer pkg=$refusedPackage installer=$installerPkg") }
            .onFailure {
                DiagnosticLog.e(TAG, "profile apk install open with system installer failed pkg=$refusedPackage installer=$installerPkg", it)
                Toast.makeText(context, loc.getString(R.string.lz_pf_open_fail), Toast.LENGTH_LONG).show()
            }
    }

    private fun systemInstallerPackage(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).setData(Uri.fromParts("file", "dummy.apk", null)),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }.getOrNull()

    private fun showToast(appCtx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
        }
    }
}

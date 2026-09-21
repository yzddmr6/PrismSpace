package com.yzddmr6.prismspace.settings.profile

import android.content.BroadcastReceiver
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
 *  - all splits go into one session → split apps (X/Twitter) actually install.
 */
object ProfileApkInstaller {

    private const val TAG = "Prism.PAI"
    private const val ACTION_RESULT = "com.yzddmr6.prismspace.action.PROFILE_INSTALL_RESULT"
    private const val EXTRA_BASE = "base"
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
    }

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

    /** True when the copied base/split APK files for this transfer record still exist in Download/PrismSpace. */
    fun hasCopiedApkSet(context: Context, pkg: String, label: String): Boolean =
        queryApkSet(context.applicationContext, safeBases(label, pkg)).isNotEmpty()

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
        val safeBases = safeBases(label, pkg)
        val safeBase = safeBases.first()
        val uris = queryApkSet(appCtx, safeBases)
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
                        .putExtra(EXTRA_BASE, safeBase)
                        .putExtra(EXTRA_PACKAGE, pkg)
                    val pi = PendingIntent.getBroadcast(
                        appCtx, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                    DiagnosticLog.i(TAG, "profile apk install session committed pkg=$pkg sessionId=$sessionId")
                }
            } catch (e: Exception) {
                sessions.remove(pkg)
                DiagnosticLog.e(TAG, "session install failed for $pkg", e)
                showToast(appCtx, loc.getString(R.string.lz_pf_install_failed, e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

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
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        DiagnosticLog.i(TAG, "profile apk install pending user confirm")
                        // Foreground confirm — the user just tapped 安装, so the app is foreground and BAL allows it.
                        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { c.startActivity(confirm) }
                                .onSuccess {
                                    // The clock starts only once the confirmation is really on screen:
                                    // an abort arriving before a human could answer is the installer's own.
                                    val pending = intent.getStringExtra(EXTRA_PACKAGE)?.let { sessions[it] }
                                    val installer = confirmInstallerLabel(c, confirm)
                                    pending?.installerLabel = installer
                                    pending?.pendingAt = SystemClock.elapsedRealtime()
                                    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, pending?.sessionId ?: -1)
                                    DiagnosticLog.i(TAG, "profile apk install confirm launched installer=$installer sessionId=$sessionId")
                                }
                                .onFailure { DiagnosticLog.e(TAG, "cannot launch install confirm", it) }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                        packageName?.let { sessions.remove(it) }
                        DiagnosticLog.i(TAG, "profile apk install success pkg=$packageName")
                        completeInstall(c, loc, packageName)
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status=$status"
                        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                        val pending = packageName?.let { sessions.remove(it) }
                        // A vendor installer may install the app itself and still abort the session
                        // (cf. F-Droid #2837), so believe the package state, not the status code.
                        val installedNow = packageName != null && isInstalledHere(c, packageName)
                        val elapsedMs = pending?.pendingAt?.let { SystemClock.elapsedRealtime() - it }
                        // The APK set lives in MediaStore (content URIs); there is no file path to feed
                        // getPackageArchiveInfo cheaply, so the version comparison is skipped and the
                        // installed flag decides. Only packages absent from this profile are offered
                        // for install, so the flag flipping to true means this session landed.
                        val apks = pending?.apks
                            ?: intent.getStringExtra(EXTRA_BASE)?.let { queryExactApkSet(c.applicationContext, it) }
                            ?: emptyList()
                        val outcome = profileInstallOutcome(
                            status = status,
                            message = msg,
                            installedNow = installedNow,
                            elapsedMs = elapsedMs,
                            apkCount = apks.size,
                            installerLabel = pending?.installerLabel,
                        )
                        when (outcome) {
                            is ProfileInstallOutcome.Succeeded -> {
                                DiagnosticLog.w(TAG, "profile apk install verdict=Succeeded despite status=$status pkg=$packageName message=$msg")
                                completeInstall(c, loc, packageName)
                            }
                            is ProfileInstallOutcome.Refused -> {
                                DiagnosticLog.w(TAG, "profile apk install refused by system installer status=$status message=$msg elapsedMs=$elapsedMs installer=${pending?.installerLabel}")
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

package com.yzddmr6.prismspace.bridge

import android.content.Context
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.shared.BuildConfig
import com.yzddmr6.prismspace.shared.R
import java.util.concurrent.CopyOnWriteArrayList

interface AppControlPort {
    fun setAppFrozen(context: Context, packageName: String, frozen: Boolean): Boolean
    fun ensureAppHiddenState(context: Context, packageName: String, hidden: Boolean): Boolean
    fun setPackageSuspended(context: Context, packageName: String, suspended: Boolean): Boolean
    fun setPackagesSuspended(context: Context, packageNames: List<String>, suspended: Boolean): Array<String>
    fun setPackagesFrozen(context: Context, packageNames: List<String>, frozen: Boolean): Array<String>
    fun ensureAppFreeToLaunch(context: Context, packageName: String): String
}

interface FileBridgePort {
    fun openWriteSession(
        context: Context,
        store: BridgeFileStore,
        safeName: String,
        mimeType: String,
        relativePath: String,
    ): WriteSessionDto
    fun finishWriteSession(
        context: Context,
        store: BridgeFileStore,
        targetUri: String,
        record: TransferLedgerDto?,
    ): PublishedFileDto
    fun abortWriteSession(context: Context, store: BridgeFileStore, targetUri: String, transferId: String?)
    fun importApkSet(
        context: Context,
        paths: List<String>,
        label: String,
        packageName: String,
        cloneLocation: String,
    ): String?
    fun completeClonePreparation(context: Context, packageName: String): Boolean
    fun queryPendingClonePreparations(context: Context): List<String>
    fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto?
    fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind): Boolean
    fun recordTransfer(context: Context, record: TransferLedgerDto, contentUri: String): Boolean
    fun inspectTransferredFile(
        context: Context,
        contentUri: String,
        mime: String?,
        mode: BridgeOpenMode,
    ): BridgeInspectResult
}

interface AppListPort {
    fun queryProfileApps(context: Context, pageIndex: Int, pageSize: Int): ProfileAppPage
}

interface ShortcutPort {
    fun prepareProfileLaunch(
        context: Context,
        packageName: String,
        action: String?,
        dataUri: String?,
        categories: List<String>,
    ): Boolean
    fun cancelProfileLaunch(context: Context)
    fun updateAll(context: Context, dynamicLabel: Boolean): Boolean
    fun removeInParent(context: Context, packageName: String, profileUserId: Int): Boolean
    fun refreshInParent(context: Context, packageName: String, profileUserId: Int): Boolean
    fun queryDynamicLabelEnabled(context: Context): Boolean
}

interface InstallerPort {
    fun notifyPackageRestarted(context: Context, packageName: String, uid: Int, uptimeMillis: Long): Boolean
}

data class BridgeHandlers(
    val appControl: AppControlPort?,
    val fileBridge: FileBridgePort?,
    val appList: AppListPort?,
    val shortcut: ShortcutPort?,
    val installer: InstallerPort?,
) {
    fun missing(requireInstaller: Boolean = false): List<String> = buildList {
        if (appControl == null) add("app_control")
        if (fileBridge == null) add("file_bridge")
        if (appList == null) add("app_list")
        if (shortcut == null) add("shortcut")
        if (requireInstaller && installer == null) add("installer")
    }
}

class BridgeHandlersBuilder internal constructor() {
    private var appControl: AppControlPort? = null
    private var fileBridge: FileBridgePort? = null
    private var appList: AppListPort? = null
    private var shortcut: ShortcutPort? = null
    private var installer: InstallerPort? = null

    fun appControl(port: AppControlPort) {
        check(appControl == null) { "AppControlPort already contributed" }
        appControl = port
    }

    fun fileBridge(port: FileBridgePort) {
        check(fileBridge == null) { "FileBridgePort already contributed" }
        fileBridge = port
    }

    fun appList(port: AppListPort) {
        check(appList == null) { "AppListPort already contributed" }
        appList = port
    }

    fun shortcut(port: ShortcutPort) {
        check(shortcut == null) { "ShortcutPort already contributed" }
        shortcut = port
    }

    fun installer(port: InstallerPort) {
        check(installer == null) { "InstallerPort already contributed" }
        installer = port
    }

    internal fun build() = BridgeHandlers(appControl, fileBridge, appList, shortcut, installer)
}

fun interface BridgePortsContributor {
    fun contribute(builder: BridgeHandlersBuilder)
}

object BridgePortsContributors {
    private val contributors = CopyOnWriteArrayList<BridgePortsContributor>()

    fun register(contributor: BridgePortsContributor) {
        contributors += contributor
    }

    fun hasContributors(): Boolean = contributors.isNotEmpty()

    internal fun assemble(): BridgeHandlers = BridgeHandlersBuilder().also { builder ->
        contributors.forEach { it.contribute(builder) }
    }.build()
}

object BridgePorts {
    @Volatile private var installed: BridgeHandlers? = null

    @Synchronized
    fun install(handlers: BridgeHandlers) {
        if (installed != null) {
            val message = "bridge_ports_duplicate_install"
            if (BuildConfig.DEBUG) error(message)
            DiagnosticLog.w(TAG, message)
            return
        }
        installed = handlers
    }

    /** The command provider owns assembly, after higher-initOrder contributors have registered. */
    fun installRegistered(context: Context) {
        if (!BridgePortsContributors.hasContributors()) return
        install(BridgePortsContributors.assemble())
        verifyInstalled(context.resources.getBoolean(R.bool.bridge_require_installer_port))
    }

    fun verifyInstalled(requireInstaller: Boolean) {
        val missing = installed?.missing(requireInstaller).orEmpty().ifEmpty {
            if (installed == null) listOf("installation") else emptyList()
        }
        if (missing.isEmpty()) return
        val message = "bridge_ports_missing handlers=${missing.joinToString()}"
        if (BuildConfig.DEBUG) error(message)
        DiagnosticLog.e(TAG, message)
    }

    internal fun handlers(): BridgeHandlers? = installed
}

private const val TAG = "Prism.Bridge"

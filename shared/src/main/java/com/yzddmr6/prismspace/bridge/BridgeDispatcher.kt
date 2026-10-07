package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.os.Bundle
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.provisioning.SystemAppPolicyRuntime

enum class BridgeErrorCategory { HandlerUnavailable, InvalidRequest, ExecutionFailed }

data class BridgeError(val category: BridgeErrorCategory, val operation: String, val message: String)

class BridgeExecutionException(val bridgeError: BridgeError) : RuntimeException(
    "${bridgeError.category}: ${bridgeError.operation}: ${bridgeError.message}",
)

internal object BridgeDispatcher {
    fun dispatch(context: Context, command: BridgeCommand<*>): Bundle = try {
        when (command) {
            Ping -> success(Ping, true)
            is SetAppFrozen -> appControl(command) { setAppFrozen(context, command.packageName, command.frozen) }
            is EnsureAppHiddenState -> appControl(command) { ensureAppHiddenState(context, command.packageName, command.hidden) }
            is SetPackageSuspended -> appControl(command) { setPackageSuspended(context, command.packageName, command.suspended) }
            is SetPackagesSuspended -> appControl(command) {
                setPackagesSuspended(context, command.packageNames, command.suspended)
            }
            is SetPackagesFrozen -> appControl(command) {
                setPackagesFrozen(context, command.packageNames, command.frozen)
            }
            is EnsureAppFreeToLaunch -> appControl(command) { ensureAppFreeToLaunch(context, command.packageName) }
            is MarkClonedSystemApp -> appControl(command) { markClonedSystemApp(context, command.packageName) }
            is EnableSystemApp -> appControl(command) { enableSystemApp(context, command.packageName) }
            is OpenWriteSession -> fileBridge(command) {
                openWriteSession(context, command.store, command.safeName, command.mimeType, command.relativePath)
            }
            is FinishWriteSession -> fileBridge(command) {
                finishWriteSession(context, command.store, command.targetUri, command.history)
            }
            is AbortWriteSession -> fileBridge(command) {
                abortWriteSession(context, command.store, command.targetUri)
            }
            is ImportApkSet -> fileBridge(command) {
                importApkSet(context, command.paths, command.label, command.packageName, command.cloneLocation)
            }
            is CompleteClonePreparation -> fileBridge(command) {
                completeClonePreparation(context, command.packageName)
            }
            QueryPendingClonePreparations -> fileBridge(QueryPendingClonePreparations) {
                queryPendingClonePreparations(context)
            }
            QueryLatestVisibleImage -> fileBridge(QueryLatestVisibleImage) { queryLatestVisibleImage(context) }
            OpenImagePickerInProfile -> fileBridge(OpenImagePickerInProfile) { openImagePicker(context) }
            is OpenLatestForRead -> fileBridge(command) { openLatestForRead(context, command.store) }
            is WritePerAppShareMarker -> fileBridge(command) { writePerAppShareMarker(context, command.packageName) }
            is DeletePerAppShareMarker -> fileBridge(command) { deletePerAppShareMarker(context, command.packageName) }
            is RunBridgeSelfTest -> fileBridge(command) { runSelfTest(context, command.marker) }
            is InstallCrossProfileForwarding -> fileBridge(command) {
                installCrossProfileForwarding(context, command.kind)
            }
            is QueryProfileAppsPage -> appList(command) {
                queryProfileApps(context, command.pageIndex, command.pageSize)
            }
            is UpdateAllShortcutsInProfile -> shortcut(command) {
                updateAll(context, command.dynamicLabel)
            }
            is RemoveShortcutsInParent -> shortcut(command) {
                removeInParent(context, command.packageName, command.profileUserId)
            }
            is RefreshShortcutInParent -> shortcut(command) {
                refreshInParent(context, command.packageName, command.profileUserId)
            }
            QueryDynamicShortcutLabelEnabled -> shortcut(QueryDynamicShortcutLabelEnabled) {
                queryDynamicLabelEnabled(context)
            }
            is PrepareProfileShortcutLaunch -> shortcut(command) {
                prepareProfileLaunch(
                    context,
                    command.packageName,
                    command.action,
                    command.dataUri,
                    command.categories,
                )
            }
            CancelProfileShortcutLaunch -> shortcut(CancelProfileShortcutLaunch) {
                cancelProfileLaunch(context)
            }
            is QuerySystemAppSelectionPage -> success(
                command,
                SystemAppPolicyRuntime.querySelectionPage(context, command.pageIndex, command.pageSize),
            )
            is ApplySystemAppSelection -> success(
                command,
                SystemAppPolicyRuntime.applySelection(context, command.changes, command.finish),
            )
            QueryProfileProvisioningFacts -> success(
                QueryProfileProvisioningFacts,
                CoreBridgeOperations.queryProfileProvisioningFacts(context),
            )
            TriggerIncrementalProvisioning -> success(
                TriggerIncrementalProvisioning,
                CoreBridgeOperations.triggerIncrementalProvisioning(context),
            )
            WipeProfile -> success(WipeProfile, CoreBridgeOperations.wipeProfile(context))
            QueryParentIsProfileOwner -> success(
                QueryParentIsProfileOwner,
                CoreBridgeOperations.queryIsProfileOwner(context),
            )
            is SaveProfileName -> success(
                command,
                CoreBridgeOperations.saveProfileName(context, command.profileUserId, command.name),
            )
            EstablishBackwardGrant -> success(
                EstablishBackwardGrant,
                CoreBridgeOperations.establishBackwardGrant(context),
            )
            is SetAppOpMode -> success(command, CoreBridgeOperations.setAppOpMode(
                context,
                command.packageName,
                command.op,
                command.mode,
                command.uid,
            ))
            is NotifyPackageRestarted -> installer(command) {
                notifyPackageRestarted(context, command.packageName, command.uid, command.uptimeMillis)
            }
            is StartProfileDeactivation -> success(
                command,
                CoreBridgeOperations.startProfileDeactivation(context, command.profileUserId),
            )
            is UnfreezeAndLaunchApp -> success(
                command,
                CoreBridgeOperations.unfreezeAndLaunch(context, command.packageName),
            )
            is OpenAppDetailsInProfile -> success(
                command,
                CoreBridgeOperations.openAppDetails(context, command.packageName),
            )
            is RequestAppUninstall -> success(
                command,
                CoreBridgeOperations.requestAppUninstall(context, command.packageName),
            )
            OpenDiagnosticsSnapshot -> success(
                OpenDiagnosticsSnapshot,
                CoreBridgeOperations.openDiagnosticsSnapshot(context),
            )
            is ReadDiagnosticsChunk -> success(
                command,
                CoreBridgeOperations.readDiagnosticsChunk(context, command.token, command.offset),
            )
            is CloseDiagnosticsSnapshot -> success(
                command,
                CoreBridgeOperations.closeDiagnosticsSnapshot(context, command.token),
            )
        }
    } catch (error: Throwable) {
        failure(command.id, BridgeErrorCategory.ExecutionFailed, error.javaClass.name + ": " + error.message.orEmpty())
    }

    private fun <R> appControl(command: BridgeCommand<R>, block: AppControlPort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.appControl
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "AppControlPort")
        return success(command, port.block())
    }

    private fun <R> fileBridge(command: BridgeCommand<R>, block: FileBridgePort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.fileBridge
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "FileBridgePort")
        return success(command, port.block())
    }

    private fun <R> appList(command: BridgeCommand<R>, block: AppListPort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.appList
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "AppListPort")
        return success(command, port.block())
    }

    private fun <R> shortcut(command: BridgeCommand<R>, block: ShortcutPort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.shortcut
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "ShortcutPort")
        return success(command, port.block())
    }

    private fun <R> installer(command: BridgeCommand<R>, block: InstallerPort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.installer
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "InstallerPort")
        return success(command, port.block())
    }

    private fun <R> success(command: BridgeCommand<R>, result: R) = Bundle().apply {
        command.encodeResult(result, this)
    }

    private fun failure(operation: String, category: BridgeErrorCategory, message: String) = Bundle().apply {
        putString(ERROR_CATEGORY, category.name)
        putString(ERROR_OPERATION, operation)
        putString(ERROR_MESSAGE, message)
        DiagnosticLog.e(TAG, "bridge_dispatch_failed operation=$operation category=$category message=$message")
    }
}

internal object BridgeWire {
    const val KEY_COMMAND = "bridge.command"

    fun methodName(command: BridgeCommand<*>) = command.id

    fun readCommand(extras: Bundle): BridgeCommand<*>? {
        extras.classLoader = BridgeCommand::class.java.classLoader
        @Suppress("DEPRECATION")
        return extras.getParcelable(KEY_COMMAND)
    }

    fun request(command: BridgeCommand<*>) = Bundle(1).apply {
        classLoader = BridgeCommand::class.java.classLoader
        putParcelable(KEY_COMMAND, command)
    }

    fun <R> decode(command: BridgeCommand<R>, response: Bundle): R {
        response.classLoader = BridgeCommand::class.java.classLoader
        val category = response.getString(ERROR_CATEGORY)?.let(BridgeErrorCategory::valueOf)
        if (category != null) throw BridgeExecutionException(
            BridgeError(
                category,
                response.getString(ERROR_OPERATION).orEmpty(),
                response.getString(ERROR_MESSAGE).orEmpty(),
            ),
        )
        return command.decodeResult(response)
    }

    fun invalidRequest(operation: String, message: String): Bundle = Bundle().apply {
        putString(ERROR_CATEGORY, BridgeErrorCategory.InvalidRequest.name)
        putString(ERROR_OPERATION, operation)
        putString(ERROR_MESSAGE, message)
        DiagnosticLog.e(TAG, "bridge_invalid_request operation=$operation message=$message")
    }
}

private const val ERROR_CATEGORY = "bridge.error.category"
private const val ERROR_OPERATION = "bridge.error.operation"
private const val ERROR_MESSAGE = "bridge.error.message"
private const val TAG = "Prism.Bridge"

package com.yzddmr6.prismspace.settings.profile

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.ActionRow
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.PrismIcons
import com.yzddmr6.prismspace.prism.compose.component.PrismLevel
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.StatusHeroCard
import com.yzddmr6.prismspace.prism.compose.component.StatusRow
import com.yzddmr6.prismspace.prism.compose.theme.PrismTheme
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.prism.service.openSystemFileManager
import com.yzddmr6.prismspace.prism.service.prepareSystemFilePickerUsable
import com.yzddmr6.prismspace.prism.ui.CrossSpaceTransferEntry
import com.yzddmr6.prismspace.util.DevicePolicies
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile-side entry screen shown when user taps the "棱镜-双开空间" icon in work-profile launcher.
 *
 * Constraints:
 *  - This Activity runs in managed profile (user 12+), CANNOT access @OwnerUser APIs
 *    (Users.getProfilesManagedByPrism, SpaceRepository.dualSpaces, etc.)
 *  - PackageManager.getInstalledApplications only sees this profile's apps, not user 0
 *  - DevicePolicies(context).isProfileOwner is the profile-local DPM check (safe)
 *  - Versions/Build are device-level, identical across users
 *
 * Design: keep the profile-side entry focused on transfer history and export guidance. Secondary
 * details such as version, device and main-space management instructions stay in the footer.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismProfileEntryScreen() {
    val context = LocalContext.current
    val state = remember { loadProfileEntryState(context) }
    val sendFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        CrossSpaceTransferEntry.launch(context, uris)
    }

    // Transfer history for THIS space — files that landed in the dual space: shares imported here
    // (ImportToSpaceActivity) + clone APKs synced in (importApkToProfile). TransferHistoryStore is
    // per-user, so this reads only this profile's records. Reload on every ON_RESUME: a clone/
    // transfer may have written a new record while this screen was backgrounded — a one-shot
    // remember{load()} would show a stale snapshot.
    var transferState by remember { mutableStateOf<ProfileTransfers?>(null) }
    val transfers = transferState?.history.orEmpty()
    val pending = transferState?.pending.orEmpty()
    val transfersLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(transfersLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) transfersLifecycleOwner.lifecycleScope.launch {
                transferState = withContext(Dispatchers.IO) { loadProfileTransfers(context) }
            }
        }
        transfersLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { transfersLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PrismTheme {
        Scaffold(
            topBar = {
                // Single, orienting title ("双开空间"): the launcher icon already identifies the
                // profile-side entry, so repeating the full launcher label here would be noisy.
                TopAppBar(title = { Text(stringResource(R.string.lz_pf_entry_topbar)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    StatusRow(
                        title = stringResource(R.string.lz_shell_profile_identity),
                        summary = stringResource(R.string.lz_shell_profile_isolation),
                        leadingIcon = PrismIcons.Shield,
                    )
                }

                if (!state.isProfileOwner) {
                    // Genuine problem state — keep it prominent. Nothing here can fix provisioning
                    // (that lives in the main space), so no button: the footer says where to go.
                    StatusHeroCard(
                        level = PrismLevel.Warn,
                        title = stringResource(R.string.lz_pf_entry_hero_title_warn),
                        body = stringResource(R.string.lz_pf_entry_hero_body_warn),
                        tag = stringResource(R.string.lz_pf_entry_hero_tag_warn),
                        leadingIcon = Icons.Outlined.Shield,
                    )
                } else {
                    // 待安装 APK 区：安装动作只在这里（传输记录行不再内嵌安装按钮）。
                    if (transferState != null && transferState?.pending == null) {
                        Text(stringResource(R.string.lz_shell_pending_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (pending.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.lz_pf_entry_pending_installs),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            GroupCard {
                                pending.forEach { item ->
                                    val pkg = item.packageName!!
                                    ActionRow(
                                        title = item.name,
                                        summary = stringResource(R.string.lz_pf_entry_pending_ready),
                                        leadingIcon = PrismIcons.File,
                                        trailing = {
                                            Button(onClick = {
                                                ProfileApkInstaller.install(context, pkg, item.name)
                                            }) {
                                                Text(stringResource(R.string.lz_pf_install))
                                            }
                                        },
                                        onClick = { ProfileApkInstaller.install(context, pkg, item.name) },
                                    )
                                }
                            }
                        }
                    }

                    GroupCard {
                        ActionRow(
                            title = stringResource(R.string.lz_pf_entry_send_to_main),
                            summary = stringResource(R.string.lz_shell_profile_pick_files),
                            leadingIcon = PrismIcons.File,
                            onClick = {
                                if (prepareSystemFilePickerUsable(context)) {
                                    sendFiles.launch(arrayOf("*/*"))
                                } else {
                                    Toast.makeText(context, R.string.lz_pf_open_fail, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    // Files that have arrived in this space — tap a row to open the file manager.
                    // Same section wording as the main space's Files tab ("传输记录") for consistency.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.lz_pf_files_history_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (transfers.isNotEmpty()) {
                            GroupCard {
                                transfers.forEach { item ->
                                    // Plain history rows: tap opens the file manager; install actions
                                    // live in the 待安装 section above, not in record rows.
                                    ActionRow(
                                        title = item.name,
                                        summary = listOf(
                                            item.direction?.let { direction -> stringResource(
                                                if (direction == TransferDirection.ToMain) R.string.lz_pf_direction_to_main
                                                else R.string.lz_pf_direction_to_profile,
                                            ) },
                                            item.location.takeIf { it.isNotBlank() },
                                            formatTransferTime(item.timeMillis).takeIf { it.isNotBlank() },
                                        ).filterNotNull().joinToString(" · "),
                                        leadingIcon = if (item.isImage) PrismIcons.Img else PrismIcons.File,
                                        trailing = {
                                            Icon(
                                                imageVector = PrismIcons.FileOpen,
                                                contentDescription = stringResource(R.string.lz_pf_open_action),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        },
                                        onClick = { openSystemFileManager(context) },
                                    )
                                }
                            }
                        } else {
                            GroupCard {
                                Text(
                                    text = stringResource(R.string.lz_pf_files_history_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Secondary profile guidance and build/device context.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.isProfileOwner) {
                        Text(
                            text = stringResource(R.string.lz_pf_entry_back_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "PrismSpace v${state.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.lz_pf_entry_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // The session result is classified, not guessed: a refusal by the ROM's own installer and a
        // real user cancel arrive with the same code, so only a refusal gets a way out offered here.
        ProfileInstallOutcomeDialog()
    }
}

@Composable
private fun ProfileInstallOutcomeDialog() {
    val context = LocalContext.current
    val outcome by ProfileApkInstaller.lastOutcome.collectAsState()
    val reportHint = stringResource(R.string.lz_pf_install_report_hint)
    val howToReport: @Composable () -> Unit = {
        TextButton(onClick = { Toast.makeText(context, reportHint, Toast.LENGTH_LONG).show() }) {
            Text(stringResource(R.string.lz_pf_install_refused_how_to_report))
        }
    }
    when (val current = outcome) {
        is ProfileInstallOutcome.Refused -> AlertDialog(
            onDismissRequest = { ProfileApkInstaller.consumeOutcome() },
            title = { Text(stringResource(R.string.lz_pf_install_refused_title)) },
            text = {
                val body = stringResource(R.string.lz_pf_install_refused_body, current.installerLabel ?: "?")
                // A split suite cannot be installed by a file manager at all — say so instead of
                // offering a button that would fail.
                Text(if (current.singleApk) body else body + "\n\n" + stringResource(R.string.lz_pf_install_refused_split))
            },
            confirmButton = {
                if (current.singleApk) {
                    TextButton(onClick = {
                        ProfileApkInstaller.consumeOutcome()
                        ProfileApkInstaller.openRefusedWithSystemInstaller(context)
                    }) { Text(stringResource(R.string.lz_pf_install_open_with_system)) }
                } else {
                    TextButton(onClick = { ProfileApkInstaller.consumeOutcome() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            },
            dismissButton = howToReport,
        )
        is ProfileInstallOutcome.Failed -> AlertDialog(
            onDismissRequest = { ProfileApkInstaller.consumeOutcome() },
            title = { Text(stringResource(R.string.lz_pf_install_failed_title)) },
            // Full system message, untruncated: it is the only evidence the user can forward.
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { ProfileApkInstaller.consumeOutcome() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = howToReport,
        )
        else -> Unit
    }
}

/** Short "MM-dd HH:mm" stamp for a transfer record; blank when the time is unknown. */
private fun formatTransferTime(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** State container for PrismProfileEntryScreen. Resolved synchronously from profile-local APIs. */
internal data class ProfileEntryState(
    val isProfileOwner: Boolean,
    val versionName: String,
    val versionCode: Long,
)

internal fun loadProfileEntryState(context: Context): ProfileEntryState {
    val isProfileOwner = runCatching { DevicePolicies(context).isProfileOwner }.getOrDefault(false)
    val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    @Suppress("DEPRECATION")
    val versionCode = pkg?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
    } ?: 0L
    val versionName = pkg?.versionName ?: "?"
    return ProfileEntryState(
        isProfileOwner = isProfileOwner,
        versionName = versionName,
        versionCode = versionCode,
    )
}

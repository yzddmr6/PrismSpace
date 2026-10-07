package com.yzddmr6.prismspace.prism.compose.screen

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.SystemAppPickerPhase
import com.yzddmr6.prismspace.prism.compose.vm.SystemAppPickerViewModel
import com.yzddmr6.prismspace.prism.service.SpaceIconLoader
import com.yzddmr6.prismspace.util.Users

/**
 * 选择要带入的系统应用. Two groups: system apps (enabled in place through the policy) and
 * /data/app preinstalls (copied, then installed by the user through the system installer).
 * Critical packages are never listed: PrismSpace keeps them available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemAppPickerScreen(onFinished: () -> Unit) {
    val vm: SystemAppPickerViewModel = viewModel()
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as? FragmentActivity

    LaunchedEffect(state.phase) { if (state.phase == SystemAppPickerPhase.Done) onFinished() }
    BackHandler(enabled = state.phase != SystemAppPickerPhase.Submitting) { vm.later() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lz_sysapp_picker_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrismTextButton(
                    onClick = { vm.later() },
                    enabled = state.phase != SystemAppPickerPhase.Submitting,
                ) { Text(stringResource(R.string.lz_sysapp_picker_later)) }
                Button(
                    onClick = { activity?.let(vm::confirm) },
                    enabled = state.phase == SystemAppPickerPhase.Ready && activity != null,
                ) { Text(stringResource(R.string.lz_sysapp_picker_confirm)) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.Md),
        ) {
            Text(
                text = stringResource(R.string.lz_sysapp_picker_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when (val phase = state.phase) {
                SystemAppPickerPhase.Waiting -> StatusLine(stringResource(R.string.lz_sysapp_picker_waiting), progress = true)
                is SystemAppPickerPhase.NotReady -> StatusLine(stringResource(R.string.lz_sysapp_picker_unavailable), progress = false)
                SystemAppPickerPhase.Ready, SystemAppPickerPhase.Submitting, SystemAppPickerPhase.Done -> {
                    if (phase == SystemAppPickerPhase.Submitting) StatusLine(stringResource(R.string.lz_sysapp_picker_confirm), progress = true)
                    val enabled = phase == SystemAppPickerPhase.Ready
                    if (state.system.isNotEmpty()) GroupCard(title = stringResource(R.string.lz_sysapp_picker_group_system)) {
                        GroupHint(stringResource(R.string.lz_sysapp_picker_group_system_hint))
                        state.system.forEach { candidate ->
                            PickerRow(
                                label = candidate.label,
                                pkg = candidate.pkg,
                                needsInstall = false,
                                checked = candidate.pkg in state.checked,
                                enabled = enabled,
                                icons = vm.icons,
                                onToggle = { vm.toggle(candidate.pkg) },
                            )
                        }
                    }
                    if (state.preinstalled.isNotEmpty()) GroupCard(title = stringResource(R.string.lz_sysapp_picker_group_preinstalled)) {
                        GroupHint(stringResource(R.string.lz_sysapp_picker_group_preinstalled_hint))
                        state.preinstalled.forEach { candidate ->
                            PickerRow(
                                label = candidate.label,
                                pkg = candidate.pkg,
                                needsInstall = true,
                                checked = candidate.pkg in state.preinstalledChecked,
                                enabled = enabled,
                                icons = vm.icons,
                                onToggle = { vm.toggle(candidate.pkg) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = PrismSpacing.Lg))
        }
    }
}

@Composable
private fun StatusLine(text: String, progress: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm)) {
        if (progress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun GroupHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
    )
}

@Composable
private fun PickerRow(
    label: String,
    pkg: String,
    needsInstall: Boolean,
    checked: Boolean,
    enabled: Boolean,
    icons: SpaceIconLoader,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .heightIn(min = 56.dp)
            .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerIcon(pkg, label, icons)
        Spacer(Modifier.width(PrismSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (needsInstall) Surface(
            shape = RoundedCornerShape(7.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.lz_sysapp_picker_needs_install),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = PrismSpacing.Sm, vertical = 3.dp),
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PickerIcon(pkg: String, label: String, icons: SpaceIconLoader) {
    val pixels = with(LocalDensity.current) { 36.dp.roundToPx() }
    val dark = isSystemInDarkTheme()
    val bitmap by produceState<Bitmap?>(null, pkg, pixels, dark) {
        value = icons.load(Users.currentId(), pkg, "", pixels, dark)
    }
    val image = bitmap?.let { remember(it) { it.asImageBitmap() } }
    if (image != null) {
        Image(image, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Text(label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

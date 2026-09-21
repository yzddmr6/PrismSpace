package com.yzddmr6.prismspace.setup.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.component.PrismTextButton
import com.yzddmr6.prismspace.prism.compose.component.GroupCard
import com.yzddmr6.prismspace.prism.compose.theme.PrismTheme

/**
 * Single-page guided setup layout:
 *   ┌ Hero (gradient, brand + welcome)
 *   ├ "Main features" header
 *   ├ FeatureCard × 3 (clone / isolation / files)
 *   ├ "How it works · Privacy" section
 *   └ Primary CTA "Create clone space" + Secondary "Having trouble?"
 *
 * Error states (DPM prerequisite failures, provisioning cancelled) surface as
 * an AlertDialog overlay driven by [SetupController.uiState]. The setup model
 * owns the business logic; this file only renders and dispatches intents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismSetupScreen(controller: SetupController) {
    val state by controller.uiState.collectAsState()
    PrismTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            SetupContent(
                modifier = Modifier.padding(padding),
                checking = state is SetupUiState.Checking,
                onPrimaryCta = { controller.onPrimaryCta() },
                onShowHelp = { controller.onShowHelp() },
                onExportDiagnostics = { controller.onExportDiagnostics() },
            )
            if (state is SetupUiState.Error) {
                SetupErrorDialog(
                    state = state as SetupUiState.Error,
                    onDismiss = { controller.onDismissError() },
                    onExtraAction = { controller.onExtraAction(it) },
                )
            }
        }
    }
}

@Composable
private fun SetupContent(
    modifier: Modifier = Modifier,
    checking: Boolean,
    onPrimaryCta: () -> Unit,
    onShowHelp: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),   // align to the 4/8/16 rhythm (was 20)
    ) {
        Spacer(Modifier.height(8.dp))
        HeroSection()
        FeaturesSection()
        HowToSection()
        CtaSection(checking = checking, onPrimary = onPrimaryCta, onHelp = onShowHelp,
            onExportDiagnostics = onExportDiagnostics)
        PrivacySection()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeroSection() {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.prism_setup_hero_title), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.prism_setup_hero_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeaturesSection() {
    GroupCard {
        FeatureCard(
            icon = Icons.Outlined.ContentCopy,
            title = stringResource(R.string.prism_setup_feature_clone_title),
            body = stringResource(R.string.prism_setup_feature_clone_body),
        )
        FeatureCard(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.prism_setup_feature_isolation_title),
            body = stringResource(R.string.prism_setup_feature_isolation_body),
        )
        FeatureCard(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.prism_setup_feature_files_title),
            body = stringResource(R.string.prism_setup_feature_files_body),
        )
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


/** 创建方式说明（系统引导 + 随时可删除）—— 对齐原型：欢迎、特性、创建方式、CTA、帮助。 */
@Composable
private fun HowToSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.prism_setup_howto_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        GroupCard {
            FeatureCard(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.prism_setup_howto_system_title),
                body = stringResource(R.string.prism_setup_howto_system_body),
            )
            FeatureCard(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.prism_setup_howto_delete_title),
                body = stringResource(R.string.prism_setup_howto_delete_body),
            )
        }
    }
}

@Composable
private fun PrivacySection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.prism_setup_privacy_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.prism_setup_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CtaSection(
    checking: Boolean,
    onPrimary: () -> Unit,
    onHelp: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPrimary,
            enabled = !checking,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            if (checking) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else Text(
                    text = stringResource(R.string.prism_setup_cta_primary),
                    style = MaterialTheme.typography.titleMedium,
                )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            PrismTextButton(onClick = onHelp) {
                Text(text = stringResource(R.string.prism_setup_cta_help))
            }
            PrismTextButton(onClick = onExportDiagnostics) {
                Text(text = stringResource(R.string.lz_set_export_logs_title))
            }
        }
    }
}

@Composable
private fun SetupErrorDialog(
    state: SetupUiState.Error,
    onDismiss: () -> Unit,
    onExtraAction: (Int) -> Unit,
) {
    // messageParams is stored as a List<String>; spread via toTypedArray().
    val params = state.messageParams
    val message: String = if (params != null) {
        stringResource(state.messageRes, *params.toTypedArray())
    } else {
        stringResource(state.messageRes)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prism_setup_error_title)) },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (state.tryProvisionAnyway) {
                    // No onDismiss here: the launch either leaves the wizard (system
                    // provisioning UI) or its catch sets a fresh error state — dismissing
                    // first would clobber that honest fallback with the Welcome state.
                    PrismTextButton(onClick = {
                        onExtraAction(R.string.button_setup_try_provision_anyway)
                    }) {
                        Text(stringResource(R.string.button_setup_try_provision_anyway))
                    }
                }
                val extra = state.extraActionRes
                if (extra != null) {
                    PrismTextButton(onClick = {
                        onExtraAction(extra)
                        onDismiss()
                    }) {
                        Text(stringResource(extra))
                    }
                    // Kept separate from the primary extra action: a fallback button must not be
                    // the reason the setup help becomes unreachable on exactly the error states
                    // that need it most.
                    val secondary = state.secondaryActionRes
                    if (secondary != null) {
                        PrismTextButton(onClick = {
                            onExtraAction(secondary)
                            onDismiss()
                        }) {
                            Text(stringResource(secondary))
                        }
                    }
                } else {
                    PrismTextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        },
        dismissButton = if (state.extraActionRes != null) {
            { PrismTextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
        } else null,
    )
}

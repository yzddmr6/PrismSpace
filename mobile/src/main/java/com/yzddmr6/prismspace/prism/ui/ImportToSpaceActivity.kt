package com.yzddmr6.prismspace.prism.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yzddmr6.prismspace.prism.compose.component.TransferSheetHost
import com.yzddmr6.prismspace.prism.compose.theme.PrismTheme
import com.yzddmr6.prismspace.prism.compose.vm.TransferSheetViewModel
import com.yzddmr6.prismspace.util.PrismLocale

/**
 * The exported share target "Send to other space" (class name kept: the system share sheet ranks
 * and pins targets by component name).
 *
 * Every launch from outside PrismSpace — the system share sheet or any explicit SEND /
 * SEND_MULTIPLE intent — passes the one-time Confirm state before anything is written. No intent
 * extra is read, so no caller can skip that confirmation. The target space is derived from the
 * user that owns each source URI, never from the user this Activity happens to run in.
 *
 * Hosts the Compose [TransferSheetHost]; leaving the screen cancels a running batch and finishes.
 */
class ImportToSpaceActivity : ComponentActivity() {

    private val vm: TransferSheetViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.startExternal(receivedUris(intent), intent?.type)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            // The receiver is excludeFromRecents: once the user leaves, nothing could bring it back.
            if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations && !isFinishing) finish()
        })
        setContent {
            PrismTheme {
                TransferSheetHost(vm, onClosed = { finish() })
            }
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        fun receivedUris(intent: Intent?): List<Uri> {
            val result = mutableListOf<Uri>()
            if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
                runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let(result::addAll)
            } else {
                runCatching { intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let(result::add)
            }
            intent?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add)
            }
            return result.distinct()
        }
    }
}

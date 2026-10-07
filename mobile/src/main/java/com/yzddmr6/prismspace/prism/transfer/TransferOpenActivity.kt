package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible PrismSpace trampoline in the user that owns a transferred file. The other space starts
 * it through `CrossProfileApps.startActivity` (same package, not exported), so the viewer or file
 * manager is launched by a foreground activity of the owning user. Finishes right after.
 */
class TransferOpenActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = readRequest(intent)
        if (request == null) {
            DiagnosticLog.w(TAG, "open.trampoline missing request")
            finish()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TransferOpener.openLocal(this@TransferOpenActivity, request) }
            DiagnosticLog.i(TAG, "open.trampoline id=${request.recordId} result=$result")
            val message = when (result) {
                OpenSurfaceResult.Opened -> null
                OpenSurfaceResult.Missing -> getString(R.string.lz_xfer_open_missing)
                OpenSurfaceResult.NoViewer -> getString(R.string.lz_xfer_open_no_viewer)
                OpenSurfaceResult.Failed -> getString(R.string.lz_xfer_open_failed, getString(currentSpaceName()))
            }
            message?.let { Toast.makeText(this@TransferOpenActivity, it, Toast.LENGTH_LONG).show() }
            finish()
        }
    }

    private fun currentSpaceName(): Int =
        if (runCatching { Users.isParentProfile() }.getOrDefault(true)) R.string.lz_xfer_space_main
        else R.string.lz_xfer_space_dual

    internal companion object {
        private const val TAG = "Prism.TransferOpen"
        private const val EXTRA_RECORD_ID = "com.yzddmr6.prismspace.extra.TRANSFER_RECORD_ID"
        private const val EXTRA_MODE = "com.yzddmr6.prismspace.extra.TRANSFER_OPEN_MODE"
        private const val EXTRA_URI = "com.yzddmr6.prismspace.extra.TRANSFER_URI"
        private const val EXTRA_MIME = "com.yzddmr6.prismspace.extra.TRANSFER_MIME"
        private const val EXTRA_REL = "com.yzddmr6.prismspace.extra.TRANSFER_RELATIVE_PATH"

        /** Plain string extras only: the intent crosses users through the system. */
        fun intent(context: Context, request: TransferOpenRequest): Intent =
            Intent(context, TransferOpenActivity::class.java)
                .putExtra(EXTRA_RECORD_ID, request.recordId)
                .putExtra(EXTRA_MODE, request.mode.name)
                .putExtra(EXTRA_URI, request.contentUri)
                .putExtra(EXTRA_MIME, request.mime)
                .putExtra(EXTRA_REL, request.relativePath)

        fun readRequest(intent: Intent?): TransferOpenRequest? {
            val id = intent?.getStringExtra(EXTRA_RECORD_ID) ?: return null
            val mode = intent.getStringExtra(EXTRA_MODE)?.let { name -> OpenMode.entries.firstOrNull { it.name == name } }
                ?: return null
            return TransferOpenRequest(
                id,
                mode,
                intent.getStringExtra(EXTRA_URI),
                intent.getStringExtra(EXTRA_MIME),
                intent.getStringExtra(EXTRA_REL),
            )
        }
    }
}

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
            DiagnosticLog.i(TAG, "open.trampoline id=${request.recordId} mode=${request.mode} result=$result")
            surfaceMessage(this@TransferOpenActivity, request.mode, result)
                ?.let { Toast.makeText(this@TransferOpenActivity, it, Toast.LENGTH_LONG).show() }
            finish()
        }
    }

    internal companion object {
        private const val TAG = "Prism.TransferOpen"
        private const val EXTRA_RECORD_ID = "com.yzddmr6.prismspace.extra.TRANSFER_RECORD_ID"
        private const val EXTRA_MODE = "com.yzddmr6.prismspace.extra.TRANSFER_OPEN_MODE"
        private const val EXTRA_URI = "com.yzddmr6.prismspace.extra.TRANSFER_URI"
        private const val EXTRA_MIME = "com.yzddmr6.prismspace.extra.TRANSFER_MIME"
        private const val EXTRA_REL = "com.yzddmr6.prismspace.extra.TRANSFER_RELATIVE_PATH"
        private const val EXTRA_SHARE_URIS = "com.yzddmr6.prismspace.extra.TRANSFER_SHARE_URIS"
        private const val EXTRA_SHARE_MIMES = "com.yzddmr6.prismspace.extra.TRANSFER_SHARE_MIMES"

        /** Plain string extras only: the intent crosses users through the system. A null share mime is "". */
        fun intent(context: Context, request: TransferOpenRequest): Intent =
            Intent(context, TransferOpenActivity::class.java)
                .putExtra(EXTRA_RECORD_ID, request.recordId)
                .putExtra(EXTRA_MODE, request.mode.name)
                .putExtra(EXTRA_URI, request.contentUri)
                .putExtra(EXTRA_MIME, request.mime)
                .putExtra(EXTRA_REL, request.relativePath)
                .apply {
                    if (request.shareItems.isNotEmpty()) {
                        putStringArrayListExtra(EXTRA_SHARE_URIS, ArrayList(request.shareItems.map { it.contentUri }))
                        putStringArrayListExtra(EXTRA_SHARE_MIMES, ArrayList(request.shareItems.map { it.mime.orEmpty() }))
                    }
                }

        /** Null (treated as "missing request") when a Share request lacks items or its lists disagree. */
        fun readRequest(intent: Intent?): TransferOpenRequest? {
            val id = intent?.getStringExtra(EXTRA_RECORD_ID) ?: return null
            val mode = intent.getStringExtra(EXTRA_MODE)?.let { name -> OpenMode.entries.firstOrNull { it.name == name } }
                ?: return null
            val shareItems = if (mode == OpenMode.Share) {
                TransferOpenPlanner.shareItemsOf(intent.getStringArrayListExtra(EXTRA_SHARE_URIS), intent.getStringArrayListExtra(EXTRA_SHARE_MIMES))
                    ?: return null
            } else {
                emptyList()
            }
            return TransferOpenRequest(
                id,
                mode,
                intent.getStringExtra(EXTRA_URI),
                intent.getStringExtra(EXTRA_MIME),
                intent.getStringExtra(EXTRA_REL),
                shareItems,
            )
        }

        /** What the owning user tells the user when the surface did not open; null when it did. */
        internal fun surfaceMessage(context: Context, mode: OpenMode, result: OpenSurfaceResult): String? {
            val strings = PrismLocale.wrap(context)
            val space = strings.getString(currentSpaceName())
            return when (result) {
                OpenSurfaceResult.Opened -> null
                OpenSurfaceResult.Missing ->
                    strings.getString(if (mode == OpenMode.Share) R.string.lz_xfer_share_missing else R.string.lz_xfer_open_missing)
                OpenSurfaceResult.NoViewer ->
                    strings.getString(if (mode == OpenMode.Share) R.string.lz_xfer_share_no_target else R.string.lz_xfer_open_no_viewer)
                OpenSurfaceResult.Failed ->
                    strings.getString(if (mode == OpenMode.Share) R.string.lz_xfer_share_failed else R.string.lz_xfer_open_failed, space)
            }
        }

        private fun currentSpaceName(): Int =
            if (runCatching { Users.isParentProfile() }.getOrDefault(true)) R.string.lz_xfer_space_main
            else R.string.lz_xfer_space_dual
    }
}

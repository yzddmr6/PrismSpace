package com.yzddmr6.prismspace.prism.transfer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible PrismSpace receiver in the user that owns a transferred file. The other space reaches it
 * only through the system cross-profile intent forwarder, via the exported aliases
 * [CrossProfile.TRANSFER_OPEN_FROM_MAIN] / [CrossProfile.TRANSFER_OPEN_FROM_DUAL]; the viewer, file
 * manager or share sheet is then launched by a foreground activity of the owning user. Every request
 * is validated against this user's ledger first; unknown ones are ignored silently. Finishes right after.
 */
class TransferOpenActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = readRaw(intent)
        lifecycleScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                validateForwardedRequest(raw, TransferLedger.load(this@TransferOpenActivity))
            }
            when (verdict) {
                // No toast: an exported entry must not become a toast emitter for other apps. The raw
                // extras are not logged either (untrusted, possibly private).
                is ForwardVerdict.Ignore ->
                    DiagnosticLog.w(TAG, "open.ignored reason=${verdict.reason} mode=${raw.openMode() ?: "-"}")
                is ForwardVerdict.Accept -> {
                    val request = verdict.request
                    val result = withContext(Dispatchers.IO) { TransferOpener.openLocal(this@TransferOpenActivity, request) }
                    DiagnosticLog.i(TAG, "open.forwarded id=${request.recordId} mode=${request.mode} result=$result")
                    surfaceMessage(this@TransferOpenActivity, request.mode, result)
                        ?.let { Toast.makeText(this@TransferOpenActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
            finish()
        }
    }

    internal companion object {
        private const val TAG = "Prism.TransferOpen"
        internal const val EXTRA_RECORD_ID = "com.yzddmr6.prismspace.extra.TRANSFER_RECORD_ID"
        internal const val EXTRA_MODE = "com.yzddmr6.prismspace.extra.TRANSFER_OPEN_MODE"
        internal const val EXTRA_URI = "com.yzddmr6.prismspace.extra.TRANSFER_URI"
        internal const val EXTRA_SHARE_URIS = "com.yzddmr6.prismspace.extra.TRANSFER_SHARE_URIS"
        internal const val EXTRA_SHARE_MIMES = "com.yzddmr6.prismspace.extra.TRANSFER_SHARE_MIMES"

        /**
         * The forwarded request: [CrossProfile.ACTION_TRANSFER_OPEN] plus the direction's category, extras
         * only (no component, no data, no CATEGORY_DEFAULT). Main → dual carries MANAGED_PROFILE, dual →
         * main PARENT_PROFILE. Only pointers travel (id, mode, uri); the receiver reads the rest from its
         * own ledger. A null share mime is "".
         */
        fun forwardSpec(request: TransferOpenRequest, fromParent: Boolean): TransferForwardSpec {
            val strings = LinkedHashMap<String, String>()
            strings[EXTRA_RECORD_ID] = request.recordId
            strings[EXTRA_MODE] = request.mode.name
            request.contentUri?.let { strings[EXTRA_URI] = it }
            val lists = LinkedHashMap<String, List<String>>()
            if (request.shareItems.isNotEmpty()) {
                lists[EXTRA_SHARE_URIS] = request.shareItems.map { it.contentUri }
                lists[EXTRA_SHARE_MIMES] = request.shareItems.map { it.mime.orEmpty() }
            }
            val category = if (fromParent) CrossProfile.CATEGORY_MANAGED_PROFILE else CrossProfile.CATEGORY_PARENT_PROFILE
            return TransferForwardSpec(CrossProfile.ACTION_TRANSFER_OPEN, category, strings, lists)
        }

        fun forwardIntent(request: TransferOpenRequest, fromParent: Boolean): Intent {
            val spec = forwardSpec(request, fromParent)
            return Intent(spec.action).addCategory(spec.category).apply {
                spec.stringExtras.forEach { (key, value) -> putExtra(key, value) }
                spec.listExtras.forEach { (key, value) -> putStringArrayListExtra(key, ArrayList(value)) }
            }
        }

        /** Never throws: a hostile Bundle that fails to unparcel reads as an empty (malformed) request. */
        fun readRaw(intent: Intent?): RawForwardedRequest = runCatching {
            RawForwardedRequest(
                recordId = intent?.getStringExtra(EXTRA_RECORD_ID),
                mode = intent?.getStringExtra(EXTRA_MODE),
                contentUri = intent?.getStringExtra(EXTRA_URI),
                shareUris = intent?.getStringArrayListExtra(EXTRA_SHARE_URIS),
                shareMimes = intent?.getStringArrayListExtra(EXTRA_SHARE_MIMES),
            )
        }.getOrDefault(RawForwardedRequest(null, null, null, null, null))

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

/** Pure description of the forwarded intent (JVM-testable). */
internal data class TransferForwardSpec(
    val action: String,
    val category: String,
    val stringExtras: Map<String, String>,
    val listExtras: Map<String, List<String>>,
)

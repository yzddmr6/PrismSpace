package com.yzddmr6.prismspace.prism.compose.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability
import com.yzddmr6.prismspace.prism.transfer.TransferLedger
import com.yzddmr6.prismspace.prism.transfer.TransferLedgerRecord
import com.yzddmr6.prismspace.prism.transfer.currentDualUsability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Pure helpers

/** Returns true if the given MIME type is an image. */
internal fun isImageMime(mimeType: String?): Boolean =
    mimeType?.lowercase()?.startsWith("image/") == true

// ViewModel

/**
 * Main-space Files tab: sends picked files to the dual space (through the shared transfer sheet)
 * and lists this space's transfer ledger — files sent from here and files received here.
 * Ledger reads run on IO.
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val _history = MutableStateFlow<List<TransferLedgerRecord>>(emptyList())
    val history: StateFlow<List<TransferLedgerRecord>> = _history

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { TransferLedger.load(getApplication()) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TransferLedger.clear(getApplication()) }
            refresh()
        }
    }

    fun removeRecord(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TransferLedger.remove(getApplication(), id) }
            refresh()
        }
    }

    /** Fresh dual-space usability for the send-card gate — the same source as clone launch. */
    suspend fun dualUsability(): SpaceUsability = withContext(Dispatchers.IO) {
        currentDualUsability(getApplication())
    }
}

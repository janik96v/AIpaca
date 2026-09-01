package com.aipaca.app.ui.memory

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aipaca.app.EngineState
import com.aipaca.app.agent.memory.MemoryStore
import com.aipaca.app.data.AgentPrefs
import com.aipaca.app.work.MemoryMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MemoryViewModel"

/**
 * State for the Memory screen — the answer to "what does the agent believe about me,
 * and can I correct it".
 *
 * Everything the self-learning loops write is visible and editable here, and every
 * change they propose but do not apply on their own (soul edits, large deletions)
 * surfaces as a pending version the user approves or rejects. That review step is
 * the safety boundary that makes automatic memory writing acceptable at all.
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    /** The four editable memory surfaces, in prompt order. */
    enum class Tab(val label: String) {
        SOUL("Soul"),
        USER("User"),
        MEMORY("Facts"),
        SESSIONS("Sessions")
    }

    data class UiState(
        val tab: Tab = Tab.SOUL,
        val content: String = "",
        val pending: String? = null,
        val loopEnabled: Boolean = true,
        val lastConsolidationAt: Long = 0L,
        val entriesSinceConsolidation: Int = 0,
        val hasBackup: Boolean = false,
        val savedNotice: String? = null
    )

    private val prefs by lazy { AgentPrefs(getApplication()) }
    private val store get() = EngineState.memoryStore
    private val sessionIndex get() = EngineState.sessionIndexStore

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun selectTab(tab: Tab) {
        _state.value = _state.value.copy(tab = tab, savedNotice = null)
        reload()
    }

    fun reload() {
        val tab = _state.value.tab
        viewModelScope.launch(Dispatchers.IO) {
            val content = readContent(tab)
            val pending = fileFor(tab)?.let { store.readPending(it) }
            val hasBackup = fileFor(tab)?.let { store.backupsFor(it).isNotEmpty() } ?: false
            _state.value = _state.value.copy(
                content = content,
                pending = pending,
                hasBackup = hasBackup,
                loopEnabled = prefs.isMemoryLoopEnabled(),
                lastConsolidationAt = prefs.getLastConsolidationAt(),
                entriesSinceConsolidation = prefs.getEntriesSinceConsolidation()
            )
        }
    }

    /** Persists a hand-edited file. The user's edit always wins over the agent's. */
    fun save(content: String) {
        val tab = _state.value.tab
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.snapshot()
                if (tab == Tab.SESSIONS) sessionIndex.writeRaw(content)
                else fileFor(tab)?.let { store.writeRaw(it, content) }
                _state.value = _state.value.copy(savedNotice = "Saved")
                reload()
            } catch (e: Exception) {
                Log.e(TAG, "saving memory file failed", e)
                _state.value = _state.value.copy(savedNotice = "Save failed")
            }
        }
    }

    fun setLoopEnabled(enabled: Boolean) {
        prefs.setMemoryLoopEnabled(enabled)
        _state.value = _state.value.copy(loopEnabled = enabled)
    }

    /** Accepts a staged rewrite proposed by the consolidation pass. */
    fun approvePending() {
        val file = fileFor(_state.value.tab) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.applyPending(file)
            _state.value = _state.value.copy(savedNotice = "Applied")
            reload()
        }
    }

    fun rejectPending() {
        val file = fileFor(_state.value.tab) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.discardPending(file)
            _state.value = _state.value.copy(savedNotice = "Discarded")
            reload()
        }
    }

    /** Undo — rolls the current file back to the version before the last write. */
    fun restoreBackup() {
        val file = fileFor(_state.value.tab) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val restored = store.restoreLatestBackup(file)
            _state.value = _state.value.copy(savedNotice = if (restored) "Restored" else "No backup")
            reload()
        }
    }

    /**
     * Triggers the consolidation pass immediately, bypassing the interval and the
     * new-entry threshold. Also the only practical way to exercise the loop on demand.
     */
    fun runConsolidationNow() {
        MemoryMaintenance.runNow(getApplication())
        _state.value = _state.value.copy(savedNotice = "Memory update queued")
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(savedNotice = null)
    }

    private fun readContent(tab: Tab): String =
        if (tab == Tab.SESSIONS) sessionIndex.readRaw() else fileFor(tab)?.let { store.read(it) } ?: ""

    /** Null for the session index, which lives in its own store. */
    private fun fileFor(tab: Tab): String? = when (tab) {
        Tab.SOUL -> MemoryStore.SOUL_FILE
        Tab.USER -> MemoryStore.USER_FILE
        Tab.MEMORY -> MemoryStore.MEMORY_FILE
        Tab.SESSIONS -> null
    }
}

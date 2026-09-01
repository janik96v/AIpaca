package com.aipaca.app.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aipaca.app.EngineState
import com.aipaca.app.agent.AgentConfig
import com.aipaca.app.agent.AgentStep
import com.aipaca.app.agent.AgentTier
import com.aipaca.app.agent.TierInputs
import com.aipaca.app.agent.TierPolicy
import com.aipaca.app.agent.memory.LearnPass
import com.aipaca.app.agent.memory.MemoryFormat
import com.aipaca.app.agent.memory.MemoryTool
import com.aipaca.app.agent.memory.SessionIndexStore
import com.aipaca.app.agent.memory.SessionNote
import com.aipaca.app.agent.memory.SessionSearchTool
import com.aipaca.app.agent.memory.SessionSummarizer
import com.aipaca.app.agent.memory.SessionViewTool
import com.aipaca.app.agent.memory.SkillReviewPass
import com.aipaca.app.agent.memory.SkillTools
import com.aipaca.app.agent.newAgentOrchestrator
import com.aipaca.app.agent.tool.TavilyMcp
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.data.AgentPrefs
import com.aipaca.app.data.ChatConversationStore
import com.aipaca.app.data.MessageDatabase
import com.aipaca.app.data.MessageEntity
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.model.ChatMessage
import com.aipaca.app.model.Role
import com.aipaca.app.model.StoredConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val TAG = "ChatViewModel"

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val conversationStore = ChatConversationStore(application)
    private var activeConversationId: String? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<StoredConversation>>(emptyList())
    val conversations: StateFlow<List<StoredConversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val generationError: SharedFlow<String> = _generationError.asSharedFlow()

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private var generationJob: Job? = null

    // ---- Agent state -------------------------------------------------------

    val agentPrefs by lazy { AgentPrefs(getApplication()) }

    // Memory stores are process-wide so the chat, the Memory screen and the
    // background consolidation worker all see the same files.
    private val memoryStore get() = EngineState.memoryStore
    private val sessionIndexStore get() = EngineState.sessionIndexStore
    private val skillStore get() = EngineState.skillStore

    private val messageDb by lazy { MessageDatabase.getInstance(getApplication()) }
    private val messageDao by lazy { messageDb.messageDao() }

    /**
     * Execution tier of the most recent turn.
     *
     * There is no agent on/off switch any more — the tier is derived from what the
     * loaded model can actually do (see [TierPolicy]). Exposed for the Memory screen
     * so the behaviour stays inspectable rather than merely implicit.
     */
    private val _activeTier = MutableStateFlow(AgentTier.PLAIN)
    val activeTier: StateFlow<AgentTier> = _activeTier.asStateFlow()

    /** Whether the optional Tavily web-search tool has a key and consent. */
    private val _webSearchConfigured = MutableStateFlow(false)
    val webSearchConfigured: StateFlow<Boolean> = _webSearchConfigured.asStateFlow()

    fun refreshWebSearchConfigured() {
        _webSearchConfigured.value = agentPrefs.isWebSearchConfigured()
    }

    // ---- STT state ---------------------------------------------------------

    private val audioRecorder = com.aipaca.app.engine.AudioRecorder()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcriptionResult = MutableStateFlow<String?>(null)
    val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()

    private val _transcriptionError = MutableStateFlow<String?>(null)
    val transcriptionError: StateFlow<String?> = _transcriptionError.asStateFlow()

    private var recordingJob: Job? = null

    fun startRecording() {
        if (_isRecording.value || _isGenerating.value) return
        _isRecording.value = true
        _transcriptionError.value = null

        recordingJob = viewModelScope.launch {
            try {
                val samples = audioRecorder.record()  // suspends until stopRecording()
                _isRecording.value = false
                _isTranscribing.value = true
                val result = EngineState.whisperEngine.transcribe(samples)
                result.fold(
                    onSuccess  = { text -> _transcriptionResult.value = text },
                    onFailure  = { e   -> _transcriptionError.value = e.message ?: "Transcription failed" }
                )
            } catch (e: Exception) {
                _transcriptionError.value = e.message ?: "Recording failed"
            } finally {
                _isRecording.value = false
                _isTranscribing.value = false
            }
        }
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        // recordingJob continues — it transitions to transcription automatically
    }

    fun consumeTranscriptionResult() {
        _transcriptionResult.value = null
    }

    fun toggleThinking() {
        _thinkingEnabled.value = !_thinkingEnabled.value
    }

    init {
        val storedConversations = conversationStore.loadConversations()
        _conversations.value = storedConversations
        storedConversations.firstOrNull()?.let { conversation ->
            activeConversationId = conversation.id
            _activeConversationId.value = conversation.id
            _messages.value = conversation.messages
            _systemPrompt.value = conversation.systemPrompt
        }
        _webSearchConfigured.value = agentPrefs.isWebSearchConfigured()
        seedSoulIfNeeded()
        // One-time backfill of existing conversations into FTS5 index
        migrateExistingConversationsToFts()
    }

    /** Gives the agent an identity on first launch so soul.md is never empty. */
    private fun seedSoulIfNeeded() {
        if (agentPrefs.hasSeededSoul()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryStore.seedSoulIfEmpty()
                agentPrefs.setSeededSoul(true)
            } catch (e: Exception) {
                Log.e(TAG, "seeding soul.md failed", e)
            }
        }
    }

    // ---- Sending -----------------------------------------------------------

    fun sendMessage(
        userText: String,
        imageUri: Uri? = null,
        documentName: String? = null,
        documentText: String? = null
    ) {
        val content = buildString {
            if (!documentText.isNullOrBlank()) append("[Document: $documentName]\n$documentText\n\n")
            if (userText.isNotBlank()) append(userText.trim())
        }.trim()
        if (content.isBlank() && imageUri == null) return

        if (activeConversationId == null) {
            activeConversationId = UUID.randomUUID().toString()
            _activeConversationId.value = activeConversationId
        }

        val userMsg = ChatMessage(
            role = Role.USER,
            content = content,
            attachedImageUri = imageUri?.toString(),
            attachedDocumentName = documentName,
            displayText = if (documentName != null) userText.trim().ifBlank { null } else null
        )
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")

        _messages.value = _messages.value + userMsg + assistantMsg
        persistCurrentConversation()
        _isGenerating.value = true

        // The tier decides how much agency this turn gets — see AgentTier.kt.
        val tier = TierPolicy.select(
            TierInputs(
                toolCallingSupported = EngineState.toolCallingSupported.value,
                hasAttachedImage = imageUri != null,
                contextSize = EngineState.contextSize.value,
                isRemoteBackend = EngineState.useOllama.value
            )
        )
        _activeTier.value = tier
        Log.i(TAG, "turn tier=$tier (toolCalling=${EngineState.toolCallingSupported.value}, ctx=${EngineState.contextSize.value})")

        generationJob = viewModelScope.launch {
            var toolIterations = 0
            try {
                toolIterations = if (tier == AgentTier.PLAIN) {
                    runPlainTurn(imageUri)
                    0
                } else {
                    runAgentTurn(content, tier)
                }
            } finally {
                _isGenerating.value = false
                afterTurn(tier, toolIterations)
            }
        }
    }

    /** Plain chat: no tools, one generation. Also the path for vision turns. */
    private suspend fun runPlainTurn(imageUri: Uri?) {
        var tokenCount = 0
        try {
            val turns = buildTurns(_messages.value.dropLast(1))
            val thinkEnabled = _thinkingEnabled.value
            val params = GenerateParams(thinkingEnabled = thinkEnabled)

            val flow = if (EngineState.useOllama.value && imageUri == null) {
                EngineState.ollamaEngine.resetThinkingState()
                EngineState.ollamaEngine.generateChat(turns, params)
            } else if (imageUri != null) {
                if (!EngineState.engine.isMmprojLoaded()) {
                    _generationError.tryEmit("Load a vision projector first — go to Models tab")
                    return
                }
                val rawBytes = getApplication<Application>().contentResolver
                    .openInputStream(imageUri)?.use { it.readBytes() }
                if (rawBytes == null || rawBytes.isEmpty()) {
                    _generationError.tryEmit("Failed to read image")
                    return
                }
                // Downscale large images to reduce vision token count
                val imageBytes = downscaleImageIfNeeded(rawBytes, maxLongEdge = 768)
                EngineState.engine.generateChatWithImage(turns, imageBytes, params)
            } else {
                EngineState.engine.generateChat(turns, params)
            }

            flow.collect { chunk ->
                tokenCount++
                val current = _messages.value
                if (current.isNotEmpty()) {
                    val last = current.last()
                    val newContent = last.content + chunk.content
                    val newThinking = if (thinkEnabled) last.thinkingContent + chunk.thinking
                                      else last.thinkingContent
                    _messages.value = current.dropLast(1) +
                        last.copy(content = newContent, thinkingContent = newThinking)
                }
            }
            if (tokenCount == 0) {
                _generationError.tryEmit("Generation failed — prompt may exceed context window")
            }
        } catch (e: Exception) {
            _generationError.tryEmit("Generation error: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Tool-carrying turn. Returns the number of tool iterations, which feeds the
     * skill-review counter.
     */
    private suspend fun runAgentTurn(goal: String, tier: AgentTier): Int {
        var registry: ToolRegistry? = null
        var toolIterations = 0
        try {
            registry = buildRegistry(tier)

            // Frozen snapshots — loaded once, writes during this turn appear next session.
            val soulSnap = memoryStore.read(com.aipaca.app.agent.memory.MemoryStore.SOUL_FILE)
            val userSnap = memoryStore.read(com.aipaca.app.agent.memory.MemoryStore.USER_FILE)
            val memorySnap = memoryStore.read(com.aipaca.app.agent.memory.MemoryStore.MEMORY_FILE)
            val sessionIdx = if (tier == AgentTier.DEEP) sessionIndexStore.renderIndex() else ""
            val skillIdx = if (tier == AgentTier.DEEP) {
                skillStore.index().joinToString("\n") { pair -> "- ${pair.first}: ${pair.second}" }
            } else ""

            val agentConfig = AgentConfig(
                soulSnapshot = soulSnap,
                userSnapshot = userSnap,
                memorySnapshot = memorySnap,
                sessionIndex = sessionIdx,
                skillIndex = skillIdx,
                maxToolRounds = TierPolicy.maxRounds(tier),
                generateParams = GenerateParams(
                    maxTokens = 1536,
                    thinkingEnabled = _thinkingEnabled.value
                )
            )

            val orchestrator = EngineState.newAgentOrchestrator(registry, agentConfig)
            // Drop the empty assistant placeholder *and* the user message that is
            // already being passed as the goal — including it in the history too made
            // the model see the same question twice.
            val turns = buildTurns(_messages.value.dropLast(2))

            fun updateAssistant(text: String, thinking: String? = null) {
                val current = _messages.value
                if (current.isNotEmpty()) {
                    val last = current.last()
                    val updated = if (thinking != null) last.copy(content = text, thinkingContent = thinking)
                                  else last.copy(content = text)
                    _messages.value = current.dropLast(1) + updated
                }
            }

            val display = StringBuilder()
            val streamedContent = StringBuilder()
            val thinkingBuffer = StringBuilder()

            orchestrator.run(goal = goal, history = turns).collect { step ->
                when (step) {
                    is AgentStep.Thinking -> {
                        if (step.thinkingText.isNotEmpty()) thinkingBuffer.append(step.thinkingText)
                        if (step.partialText.isNotEmpty()) streamedContent.append(step.partialText)
                        updateAssistant(
                            text = display.toString() + streamedContent.toString(),
                            thinking = thinkingBuffer.toString()
                        )
                    }
                    is AgentStep.ToolCall -> {
                        toolIterations++
                        // Clear streamed content (may contain raw tool syntax)
                        streamedContent.clear()
                        display.appendLine("🔍 *${describeTool(step.name)}...*\n")
                        updateAssistant(display.toString(), thinkingBuffer.toString())
                    }
                    is AgentStep.ToolObservation -> {
                        display.appendLine("✅ *Done*\n")
                        streamedContent.clear()
                        thinkingBuffer.clear()
                        updateAssistant(display.toString())
                    }
                    is AgentStep.FinalAnswer -> {
                        val finalText = if (display.isNotEmpty()) display.toString() + step.text else step.text
                        updateAssistant(finalText, thinkingBuffer.toString())
                    }
                    is AgentStep.Error -> {
                        _generationError.tryEmit("Agent error: ${step.message}")
                        if (display.isEmpty() && streamedContent.isEmpty()) {
                            updateAssistant("Agent error: ${step.message}")
                        } else {
                            display.appendLine("\n\nAgent error: ${step.message}")
                            updateAssistant(display.toString())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "agent turn failed", e)
            _generationError.tryEmit("Agent error: ${e.message ?: "unknown error"}")
        } finally {
            registry?.closeAll()
        }
        return toolIterations
    }

    /**
     * Registers exactly the tools this tier's budget allows, in priority order.
     *
     * Web search is optional and additive: when it isn't configured, or the MCP
     * server can't be reached, the turn continues with the local tools instead of
     * failing. Tying the whole loop to a Tavily key is what previously made memory
     * unreachable for anyone without one.
     */
    private suspend fun buildRegistry(tier: AgentTier): ToolRegistry {
        val registry = ToolRegistry()
        val webConfigured = agentPrefs.isWebSearchConfigured()

        for (name in TierPolicy.toolNames(tier, webConfigured)) {
            when (name) {
                TierPolicy.TOOL_MEMORY ->
                    registry.registerLocal(MemoryTool.spec()) { args -> MemoryTool.run(args, memoryStore) }

                TierPolicy.TOOL_SESSION_SEARCH ->
                    registry.registerLocal(SessionSearchTool.spec()) { args -> SessionSearchTool.run(args, messageDao) }

                TierPolicy.TOOL_SESSION_VIEW ->
                    registry.registerLocal(SessionViewTool.spec()) { args ->
                        SessionViewTool.run(args, messageDao, sessionIndexStore)
                    }

                TierPolicy.TOOL_SKILL_VIEW ->
                    registry.registerLocal(SkillTools.viewSpec()) { args -> SkillTools.view(args, skillStore) }

                TierPolicy.TOOL_SKILL_MANAGE ->
                    registry.registerLocal(SkillTools.manageSpec()) { args -> SkillTools.manage(args, skillStore) }

                TierPolicy.TOOL_WEB_SEARCH -> {
                    val client = TavilyMcp.buildClient(agentPrefs)
                    if (client != null) {
                        try {
                            withTimeout(15_000) { registry.register(client) }
                        } catch (e: Exception) {
                            Log.w(TAG, "web search unavailable, continuing without it", e)
                            client.close()
                        }
                    }
                }
            }
        }
        Log.d(TAG, "tier=$tier tools=${registry.manifest().map { it.name }}")
        return registry
    }

    private fun describeTool(name: String): String = when (name) {
        TierPolicy.TOOL_SESSION_SEARCH, TierPolicy.TOOL_SESSION_VIEW -> "Looking through past conversations"
        TierPolicy.TOOL_MEMORY -> "Updating memory"
        TierPolicy.TOOL_SKILL_VIEW, TierPolicy.TOOL_SKILL_MANAGE -> "Consulting skills"
        else -> "Searching: $name"
    }

    // ---- Post-turn learning loops ------------------------------------------

    /**
     * Runs after every turn on every path — plain chat included.
     *
     * The previous version of this hung off the agent path, which bailed out early
     * whenever no Tavily key was configured, so in practice the agent never learned
     * anything and normal chats never reached the search index.
     */
    private fun afterTurn(tier: AgentTier, toolIterations: Int) {
        persistCurrentConversation()
        indexCurrentConversationToFts()

        if (!agentPrefs.isMemoryLoopEnabled()) return
        if (!EngineState.canRunMemoryPass()) return

        val digest = LearnPass.buildDigest(
            _messages.value.map { msg -> msg.role.name.lowercase() to msg.content }
        )

        // L1.5 — memory extraction. Counter-gated; each counter resets on its own,
        // so a memory pass no longer starves the skill review (and vice versa).
        val turnsSinceMemory = agentPrefs.getTurnsSinceMemory() + 1
        if (turnsSinceMemory >= LearnPass.MEMORY_REVIEW_THRESHOLD) {
            agentPrefs.setTurnsSinceMemory(0)
            runMemoryExtraction(digest)
        } else {
            agentPrefs.setTurnsSinceMemory(turnsSinceMemory)
        }

        // L1.5b — skill review. Tool-based, so only for models that can call tools.
        val itersSinceSkill = agentPrefs.getItersSinceSkill() + toolIterations
        if (tier == AgentTier.DEEP && itersSinceSkill >= SkillReviewPass.SKILL_REVIEW_THRESHOLD) {
            agentPrefs.setItersSinceSkill(0)
            runSkillReview(digest)
        } else {
            agentPrefs.setItersSinceSkill(itersSinceSkill)
        }
    }

    /**
     * Launched on the application scope, not [viewModelScope]: leaving the chat
     * screen used to cancel the pass halfway through, after its counters had
     * already been consumed.
     */
    private fun runMemoryExtraction(digest: String) {
        EngineState.scope.launch(Dispatchers.Default) {
            if (!EngineState.memoryPassMutex.tryLock()) {
                Log.d(TAG, "another memory pass is running — skipping extraction")
                return@launch
            }
            try {
                val result = LearnPass.run(digest, memoryStore, EngineState.memoryEngine())
                agentPrefs.addEntriesSinceConsolidation(result.total)
            } catch (e: Exception) {
                Log.e(TAG, "memory extraction failed", e)
            } finally {
                EngineState.memoryPassMutex.unlock()
            }
        }
    }

    private fun runSkillReview(digest: String) {
        EngineState.scope.launch(Dispatchers.Default) {
            if (!EngineState.memoryPassMutex.tryLock()) {
                Log.d(TAG, "another memory pass is running — skipping skill review")
                return@launch
            }
            try {
                SkillReviewPass.run(
                    digest = digest,
                    skillStore = skillStore,
                    engine = EngineState.engine,
                    generateMutex = EngineState.generateMutex,
                    isModelLoaded = { EngineState.isLoaded.value },
                    ollamaEngine = if (EngineState.useOllama.value) EngineState.ollamaEngine else null
                )
            } catch (e: Exception) {
                Log.e(TAG, "skill review failed", e)
            } finally {
                EngineState.memoryPassMutex.unlock()
            }
        }
    }

    /**
     * L2 — writes the one-line summary of a finished conversation into the session
     * index, so the agent can recall it later without reading the whole transcript.
     *
     * Called whenever a conversation is left. Re-summarizes at most once per day per
     * conversation, so a chat picked up again next week gets a current description
     * instead of keeping its first-day one.
     */
    private fun summarizeConversation(conversationId: String?, messages: List<ChatMessage>) {
        if (conversationId == null) return
        if (!agentPrefs.isMemoryLoopEnabled()) return
        if (messages.size < SessionSummarizer.MIN_MESSAGES) return
        if (!EngineState.canRunMemoryPass()) return

        val existing = sessionIndexStore.note(conversationId)
        if (existing != null && existing.date == MemoryFormat.today()) return

        val title = titleFor(messages)
        val digest = LearnPass.buildDigest(messages.map { it.role.name.lowercase() to it.content })

        EngineState.scope.launch(Dispatchers.Default) {
            if (!EngineState.memoryPassMutex.tryLock()) return@launch
            try {
                val summary = SessionSummarizer.summarize(digest, EngineState.memoryEngine()) ?: return@launch
                sessionIndexStore.upsert(
                    SessionNote(
                        sessionId = conversationId,
                        date = MemoryFormat.today(),
                        title = SessionIndexStore.sanitize(title, SessionIndexStore.MAX_TITLE_CHARS),
                        summary = summary
                    )
                )
                agentPrefs.addEntriesSinceConsolidation(1)
            } catch (e: Exception) {
                Log.e(TAG, "session summary failed", e)
            } finally {
                EngineState.memoryPassMutex.unlock()
            }
        }
    }

    // ---- Conversation management -------------------------------------------

    fun stopGeneration() {
        EngineState.engine.stopGeneration()
        EngineState.ollamaEngine.stopGeneration()
        generationJob?.cancel()
        _isGenerating.value = false
    }

    fun updateSystemPrompt(text: String) {
        _systemPrompt.value = text
        if (activeConversationId == null) {
            activeConversationId = UUID.randomUUID().toString()
            _activeConversationId.value = activeConversationId
        }
        persistCurrentConversation()
    }

    fun clearChat() {
        stopGeneration()
        summarizeConversation(activeConversationId, _messages.value)
        _messages.value = emptyList()
        _systemPrompt.value = ""
        activeConversationId = null
        _activeConversationId.value = null
    }

    fun selectConversation(conversationId: String) {
        stopGeneration()
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        if (conversation.id != activeConversationId) {
            summarizeConversation(activeConversationId, _messages.value)
        }
        activeConversationId = conversation.id
        _activeConversationId.value = conversation.id
        _messages.value = conversation.messages
        _systemPrompt.value = conversation.systemPrompt
    }

    fun deleteConversation(conversationId: String) {
        if (activeConversationId == conversationId) {
            stopGeneration()
        }
        _conversations.value = conversationStore.delete(conversationId)
        // Delete the agent's memory of it too, otherwise the session index keeps
        // pointing at a conversation the user removed.
        sessionIndexStore.remove(conversationId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageDao.deleteSession(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "removing conversation from the search index failed", e)
            }
        }
        if (activeConversationId == conversationId) {
            activeConversationId = null
            _activeConversationId.value = null
            _messages.value = emptyList()
        }
    }

    override fun onCleared() {
        summarizeConversation(activeConversationId, _messages.value)
        super.onCleared()
    }

    // ---- Helpers -----------------------------------------------------------

    private fun downscaleImageIfNeeded(rawBytes: ByteArray, maxLongEdge: Int): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return rawBytes // can't decode dimensions, pass through
        val longEdge = maxOf(w, h)
        if (longEdge <= maxLongEdge) return rawBytes // already small enough

        val scale = maxLongEdge.toFloat() / longEdge
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        val full = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scaled = Bitmap.createScaledBitmap(full, newW, newH, true)
        if (scaled !== full) full.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
    }

    private fun buildTurns(messages: List<ChatMessage>): List<ChatTurn> {
        val turns = messages.mapNotNull { msg ->
            if (msg.content.isBlank()) return@mapNotNull null
            val role = when (msg.role) {
                Role.USER      -> "user"
                Role.ASSISTANT -> "assistant"
                Role.SYSTEM    -> "system"
            }
            ChatTurn(role = role, content = msg.content)
        }
        val prompt = _systemPrompt.value
        return if (prompt.isNotBlank()) {
            listOf(ChatTurn(role = "system", content = prompt)) + turns
        } else {
            turns
        }
    }

    private fun titleFor(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == Role.USER }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.take(42)
            ?.ifBlank { null }
            ?: "Untitled chat"

    private fun persistCurrentConversation() {
        val conversationId = activeConversationId ?: return
        val currentMessages = _messages.value
        if (currentMessages.isEmpty() && _systemPrompt.value.isBlank()) return

        _conversations.value = conversationStore.upsert(
            StoredConversation(
                id           = conversationId,
                title        = titleFor(currentMessages),
                messages     = currentMessages,
                updatedAt    = System.currentTimeMillis(),
                systemPrompt = _systemPrompt.value
            )
        )
    }

    /** Index current conversation messages into Room/FTS5 for cross-session recall. */
    private fun indexCurrentConversationToFts() {
        val conversationId = activeConversationId ?: return
        val currentMessages = _messages.value
        if (currentMessages.isEmpty()) return
        val title = titleFor(currentMessages)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entities = currentMessages
                    .filter { it.content.isNotBlank() }
                    .map { msg ->
                        MessageEntity(
                            id = msg.id,
                            sessionId = conversationId,
                            role = msg.role.name.lowercase(),
                            content = msg.content,
                            timestamp = msg.timestamp,
                            sessionTitle = title
                        )
                    }
                messageDao.insertAll(entities)
            } catch (e: Exception) {
                Log.e(TAG, "FTS indexing failed", e)
            }
        }
    }

    /** One-time migration: backfill existing conversations into Room/FTS5. */
    fun migrateExistingConversationsToFts() {
        if (agentPrefs.hasMigratedToFts()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversations = conversationStore.loadConversations()
                for (conv in conversations) {
                    val entities = conv.messages
                        .filter { it.content.isNotBlank() }
                        .map { msg ->
                            MessageEntity(
                                id = msg.id,
                                sessionId = conv.id,
                                role = msg.role.name.lowercase(),
                                content = msg.content,
                                timestamp = msg.timestamp,
                                sessionTitle = conv.title
                            )
                        }
                    if (entities.isNotEmpty()) messageDao.insertAll(entities)
                }
                agentPrefs.setMigratedToFts(true)
                Log.i(TAG, "FTS migration complete: ${conversations.size} conversations indexed")
            } catch (e: Exception) {
                Log.e(TAG, "FTS migration failed", e)
            }
        }
    }
}

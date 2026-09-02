# AIpaca Architecture

## System Architecture

```
UI (Jetpack Compose + Material 3)
    |  StateFlow
EngineState (process-scoped singleton)
    |-- LlamaCppEngine --> llama_jni.cpp --> llama.cpp (GPU/CPU)
    |                                          |-- mtmd (vision)
    |                                          +-- Adreno OpenCL / CPU fallback
    |-- WhisperEngine ---> whisper_jni.cpp --> whisper.cpp (GPU/CPU)
    |-- OllamaEngine ----> Ktor HTTP Client --> Ollama server (local network)
    |                                          +-- OpenAI-compatible /v1/chat/completions
    +-- AgentOrchestrator
         |-- Native tool-calling (Jinja + PEG parser)  [LlamaCppEngine path]
         |-- OpenAI tool-calling (streamed deltas)      [OllamaEngine path]
         |-- ToolRegistry
         |   |-- MCP tools (HttpMcpClient, Streamable HTTP/SSE)
         |   +-- Local tools (memory, skill_view, skill_manage, session_search)
         |-- MemoryStore / SkillStore (filesystem, filesDir/agent_memory|skills)
         |-- MessageDatabase (Room + FTS5, session_search index)
         +-- LearnPass (counter-triggered post-turn review)

--- parallel ---

Ktor HTTPS Server (port 8443, TLS + Ed25519 auth)
    |  calls same EngineState (serialized via generateMutex)
OpenAI-compatible REST API (/v1/chat/completions)
    |  SSE streaming
Open WebUI / OpenClaw / LangChain / curl
```

**Key constraint**: One `llama_context` per process. Server and agent serialize all engine calls via `EngineState.generateMutex`. The `OllamaEngine` path is stateless HTTP and does not acquire the mutex.

---

## Project Structure

```
app/src/main/
|-- kotlin/com/aipaca/app/
|   |-- AIpacaApp.kt                    # Application entry point
|   |-- EngineState.kt                  # Process-scoped engine singleton
|   |-- agent/                          # Agent mode + MCP client
|   |   |-- AgentOrchestrator.kt        # Think->tool->observe loop
|   |   |-- AgentConfig.kt              # System prompt + persona
|   |   |-- AgentMessage.kt             # Tool-calling message roles
|   |   |-- AgentSession.kt             # Per-session agent state
|   |   |-- AgentTier.kt                # Capability-based execution tiers (Plain/Assisted/Deep)
|   |   |-- ToolManifestJson.kt         # Tool schema serialization
|   |   |-- mcp/                        # Model Context Protocol client
|   |   |   |-- HttpMcpClient.kt        # Streamable HTTP/SSE transport
|   |   |   |-- McpClient.kt            # MCP client interface
|   |   |   +-- McpModels.kt            # JSON-RPC 2.0 + MCP types
|   |   |-- tool/
|   |   |   |-- ToolRegistry.kt         # Aggregates MCP + local tools
|   |   |   +-- TavilyMcp.kt            # Tavily web search integration
|   |   +-- memory/                     # Agent memory + skills + learning
|   |       |-- MemoryStore.kt          # Filesystem-backed memory files (agent_soul.md, agent_user.md, agent_memory.md)
|   |       |-- MemoryTool.kt           # "memory" tool: add/replace/remove memory entries
|   |       |-- MemoryEngine.kt         # Generation wrapper for memory-specific LLM calls
|   |       |-- MemoryEntry.kt          # Memory entry data class
|   |       |-- MemoryExtraction.kt     # Per-turn memory extraction logic
|   |       |-- MemoryConsolidation.kt  # Merge/dedup/contradiction resolution
|   |       |-- ConsolidationPass.kt    # Orchestrates a full consolidation run
|   |       |-- LearnPass.kt            # Counter-triggered post-turn review
|   |       |-- SkillStore.kt           # Filesystem-backed skill files (agent_skills/<name>.md)
|   |       |-- SkillTools.kt           # "skill_view" + "skill_manage" tools
|   |       |-- SkillReviewPass.kt      # Counter-triggered skill extraction
|   |       |-- Skill.kt                # Skill data class
|   |       |-- SessionSearchTool.kt    # "session_search" tool: FTS5 cross-session recall
|   |       |-- SessionViewTool.kt      # "session_view" tool: load full past conversation
|   |       |-- SessionIndexStore.kt    # Session summary index (one-liners per conversation)
|   |       |-- SessionSummarizer.kt    # Post-conversation summary generation
|   |       |-- AntiPoisoning.kt        # Guards against persisting transient errors
|   |-- engine/                         # Inference engines
|   |   |-- InferenceEngine.kt          # Interface + data classes
|   |   |-- AgentModels.kt              # Agent-specific data models
|   |   |-- LlamaCppEngine.kt           # LLM JNI wrapper + vision
|   |   |-- WhisperEngine.kt            # STT engine
|   |   |-- OllamaEngine.kt             # Remote LLM via Ollama HTTP API
|   |   +-- AudioRecorder.kt            # Microphone input
|   |-- server/                         # OpenAI-compatible API server
|   |   |-- ApiServer.kt                # Ktor HTTPS server
|   |   |-- ApiService.kt               # Android foreground service
|   |   |-- ServerManager.kt            # Server state management
|   |   |-- models/OpenAIModels.kt      # OpenAI wire format
|   |   +-- security/                   # TLS, Ed25519, pairing
|   |       |-- TlsManager.kt           # Self-signed cert (PKCS12)
|   |       |-- AuthorizedKeysStore.kt   # Encrypted key store
|   |       |-- PairingManager.kt       # One-time PIN sessions
|   |       |-- Ed25519Verifier.kt       # Request signature verification
|   |       +-- AuthPlugin.kt           # Ktor auth plugin
|   |-- ui/                             # Jetpack Compose UI
|   |   |-- MainActivity.kt             # Navigation host + bottom nav
|   |   |-- chat/ChatScreen.kt          # Chat + streaming + agent steps
|   |   |-- chat/ChatViewModel.kt       # Chat state management
|   |   |-- chat/ThinkTagParser.kt      # Thinking token extraction
|   |   |-- memory/MemoryScreen.kt      # Memory viewer/editor (4 tabs: Soul, User, Memory, Sessions)
|   |   |-- memory/MemoryViewModel.kt   # Memory state management
|   |   |-- server/ServerScreen.kt      # Server dashboard + pairing
|   |   |-- models/ModelScreen.kt       # Model library + quant guide
|   |   |-- models/GgufFilePickerSheet.kt # Model file picker
|   |   |-- components/                 # Shared UI components
|   |   +-- theme/                      # Material 3 dark theme
|   |-- work/                           # Background workers
|   |   |-- MemoryMaintenance.kt        # WorkManager scheduler (24h periodic + on-demand)
|   |   +-- MemoryMaintenanceWorker.kt  # Idle-time consolidation (charging + idle)
|   +-- data/                           # Encrypted persistence
|       |-- ChatConversationStore.kt    # Conversation history (AES256-GCM)
|       |-- AgentPrefs.kt               # Agent config (API keys, MCP URL)
|       |-- MmprojModelPrefs.kt         # Multimodal projector path
|       |-- WhisperModelPrefs.kt        # Last used STT model path
|       |-- OllamaPrefs.kt              # Ollama server URL, model name, enabled state
|       |-- DownloadedModelStore.kt     # Downloaded model tracking
|       |-- HuggingFaceApi.kt           # HuggingFace API client
|       |-- ModelDownloadManager.kt     # Model download orchestration
|       +-- MessageDatabase.kt          # Room DB + FTS5 index for session_search
+-- cpp/
    |-- CMakeLists.txt                  # Native build config (OpenCL, mtmd)
    |-- llama_jni.cpp                   # LLM JNI bridge (~2K lines)
    |-- whisper_jni.cpp                 # STT JNI bridge
    |-- llama.cpp/                      # Git submodule (custom Adreno fork)
    |-- whisper.cpp/                    # Git submodule (v1.8.4)
    +-- opencl-stub/                    # ARM64 OpenCL link-time stub
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0, C++17 |
| UI | Jetpack Compose + Material 3 |
| LLM (on-device) | llama.cpp (custom Adreno fork: `janik96v/llama.cpp`) |
| LLM (remote) | Ollama via Ktor HTTP Client (OpenAI-compatible API) |
| STT | whisper.cpp 1.8.4 |
| Vision | llama.cpp mtmd library |
| GPU | Adreno OpenCL with optimized kernels |
| Server | Ktor 2.3 (Netty, HTTPS) |
| MCP Client | Ktor HTTP Client (Streamable HTTP/SSE) |
| Agent Memory | Filesystem plain-text files (app-internal storage) |
| Session Search | Room + SQLite FTS5 (BM25 ranking) |
| Background Work | AndroidX WorkManager (periodic + one-shot consolidation) |
| Security | Ed25519, TLS (PKCS12), AES256-GCM |
| Serialization | kotlinx.serialization, nlohmann/json (C++) |
| Build | Gradle (Kotlin DSL), CMake, NDK r27.2 |
| Target | Android API 28-35, arm64-v8a only |

---

## GPU Acceleration

### Adreno OpenCL Backend

AIpaca uses a custom fork of llama.cpp with Adreno OpenCL kernels. Both llama.cpp and whisper.cpp share the same ggml backend.

**GPU-compatible quantizations**: Q4_0, Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL. All others fall back to CPU silently.

**Build flags**:
```cmake
GGML_OPENCL=ON
GGML_OPENCL_USE_ADRENO_KERNELS=ON
```

The OpenCL library is linked via a stub (`opencl-stub/`) at compile time — the actual driver is provided by the device at runtime.

### GPU Probe Mechanism

Both LLM and whisper engines use the same GPU safety pattern:
1. Install SIGSEGV/SIGBUS signal handlers before GPU probe
2. Attempt a single 1-token decode on GPU
3. If signal fires: catch, revert to CPU, log warning
4. Cache result in `gpuLayers` StateFlow

This ensures the app never crashes from GPU driver issues — it silently falls back to CPU.

### Known GPU Limitations

| Issue | Workaround |
|---|---|
| Q8_0 KV cache on Adreno 750 | KV cache quantization disabled on GPU path |
| Flash attention with whisper | Standard attention (MUL_MAT+SOFT_MAX) used instead |
| Flash attention with mmproj | Standard attention used for vision encoding |

---

## Native Build

The native layer is built via CMake through the Android Gradle Plugin:

- **llama.cpp** is included as a git submodule (`app/src/main/cpp/llama.cpp/`) from a custom fork with Adreno kernel support
- **whisper.cpp** is included as a git submodule (`app/src/main/cpp/whisper.cpp/`), sharing the same ggml backend
- **mtmd** (multimodal) is built from `llama.cpp/tools/mtmd/` via `add_subdirectory(... EXCLUDE_FROM_ALL)`
- All three link into a single shared library (`libaipaca.so`) exposed via JNI
- Target ABI: arm64-v8a only, C++17, NDK r27.2

### JNI Functions (llama_jni.cpp)

| Function | Purpose |
|---|---|
| `nativeLoadModel` | Load GGUF, init context, GPU probe |
| `nativeProbeGpu` | Test GPU with signal handler |
| `nativeGetActiveGpuLayers` | Query actual layers in use |
| `nativeGenerate` | Simple prompt -> completion (streaming) |
| `nativeGenerateChat` | Multi-turn chat with roles |
| `nativeGenerateAgent` | Chat + tools, parses tool calls natively |
| `nativeGenerateChatWithImage` | Vision inference (text + image via mtmd) |
| `nativeBench` | Prefill/generation throughput benchmark |
| `nativeStopGeneration` | Request cancellation (atomic flag) |
| `nativeUnloadModel` | Free resources |
| `nativeGetSystemInfo` | GPU/backend diagnostics |
| `nativeGetModelInfo` | GGUF metadata (name, size, quant type) |
| `nativeGetChatTemplate` | Extract Jinja chat template from model |
| `nativeProbeToolSupport` | Check if chat template supports tool calling |
| `nativeLoadMmproj` | Load multimodal projector GGUF |
| `nativeUnloadMmproj` | Free mmproj resources |
| `nativeIsMmprojLoaded` | Query mmproj state |

# firn Architecture

## System Architecture

```
UI (Jetpack Compose, "Instrument" design system over a themed Material 3 base)
    |  StateFlow
EngineState (process-scoped singleton)
    |-- LlamaCppEngine --> llama_jni.cpp --> llama.cpp (GPU/CPU)
    |                                          |-- mtmd (vision)
    |                                          +-- Adreno OpenCL / CPU fallback
    |-- WhisperEngine ---> whisper_jni.cpp --> whisper.cpp (GPU/CPU)
    |-- OllamaEngine ----> Ktor HTTP Client --> Ollama server (local network)
    |                                          +-- OpenAI-compatible /v1/chat/completions
    |-- AgentOrchestrator (tier Plain / Assisted / Deep, chosen by TierPolicy)
    |    |-- Native tool-calling (Jinja + PEG parser)  [LlamaCppEngine path]
    |    |-- OpenAI tool-calling (streamed deltas)      [OllamaEngine path]
    |    +-- ToolRegistry
    |        |-- MCP tools (HttpMcpClient, Streamable HTTP/SSE; Tavily web search)
    |        +-- Local tools (memory, files, session_search, session_view, skill_view, skill_manage)
    +-- Memory (MemoryEngine: local engine under generateMutex, or Ollama)
         |-- MemoryStore / SkillStore / AgentWorkspace (filesDir/agent_memory|agent_skills|agent_workspace)
         |-- MessageDatabase (Room + FTS5, session_search index)
         |-- LearnPass / SkillReviewPass / SessionSummarizer (counter-triggered after a turn)
         +-- ConsolidationPass (MemoryMaintenanceWorker, idle + charging)

--- parallel ---

Ktor HTTPS Server (port 8443, TLS + Ed25519 auth)
    |  calls same EngineState (serialized via generateMutex)
OpenAI-compatible REST API (/v1/chat/completions)
    |  SSE streaming
Open WebUI / OpenClaw / LangChain / curl
```

**Key constraint**: One `llama_context` per process. Server, agent and memory passes serialize all local engine calls via `EngineState.generateMutex`. The `OllamaEngine` path is stateless HTTP and does not acquire the mutex.

---

## Project Structure

```
app/src/main/
|-- kotlin/ch/firn/app/
|   |-- FirnApplication.kt              # Application entry point (OpenCL env, EngineState init, prefs restore)
|   |-- EngineState.kt                  # Process-scoped engine singleton (state flows, generateMutex, computeContextConfig)
|   |-- agent/                          # Agent mode + MCP client
|   |   |-- AgentOrchestrator.kt        # Think->tool->observe loop
|   |   |-- AgentConfig.kt              # System prompt + persona
|   |   |-- AgentMessage.kt             # Tool-calling message roles
|   |   |-- AgentSession.kt             # Per-session agent state
|   |   |-- AgentTier.kt                # Plain/Assisted/Deep tiers + TierPolicy (tool budget per tier)
|   |   |-- ToolManifestJson.kt         # Tool schema serialization
|   |   |-- mcp/                        # Model Context Protocol client
|   |   |   |-- HttpMcpClient.kt        # Streamable HTTP/SSE transport
|   |   |   |-- McpClient.kt            # MCP client interface
|   |   |   +-- McpModels.kt            # JSON-RPC 2.0 + MCP types
|   |   |-- tool/
|   |   |   |-- ToolRegistry.kt         # Aggregates MCP + local tools
|   |   |   |-- TavilyMcp.kt            # Tavily web search (remote MCP server)
|   |   |   |-- FileTool.kt             # "files" tool: sandboxed list/read/write/append/edit/delete
|   |   |   +-- AgentWorkspace.kt       # Sandbox roots for "files" (memory/skills/workspace)
|   |   +-- memory/                     # Agent memory + skills + learning
|   |       |-- MemoryStore.kt          # Filesystem-backed memory files (agent_soul.md, agent_user.md, agent_memory.md)
|   |       |-- MemoryTool.kt           # "memory" tool: add/replace/remove memory entries
|   |       |-- MemoryEngine.kt         # Backend-agnostic generation surface for the memory passes
|   |       |-- MemoryEntry.kt          # Memory entry data class
|   |       |-- MemoryExtraction.kt     # Line-format parsing for the extraction pass
|   |       |-- MemoryConsolidation.kt  # Merge/dedup/contradiction resolution
|   |       |-- ConsolidationPass.kt    # Orchestrates a full consolidation run
|   |       |-- LearnPass.kt            # Counter-triggered post-turn fact extraction
|   |       |-- SkillStore.kt           # Filesystem-backed skill files (agent_skills/<name>.md)
|   |       |-- SkillTools.kt           # "skill_view" + "skill_manage" tools
|   |       |-- SkillReviewPass.kt      # Counter-triggered skill extraction (Deep tier only)
|   |       |-- Skill.kt                # Skill data class
|   |       |-- SessionSearchTool.kt    # "session_search" tool: FTS5 cross-session recall
|   |       |-- SessionViewTool.kt      # "session_view" tool: load full past conversation
|   |       |-- SessionIndexStore.kt    # Session summary index (agent_sessions.md)
|   |       |-- SessionSummarizer.kt    # Post-conversation summary generation
|   |       +-- AntiPoisoning.kt        # Guards against persisting transient errors
|   |-- engine/                         # Inference engines
|   |   |-- InferenceEngine.kt          # Interface + data classes
|   |   |-- AgentModels.kt              # Agent-specific data models
|   |   |-- LlamaCppEngine.kt           # LLM JNI wrapper + vision
|   |   |-- WhisperEngine.kt            # STT engine
|   |   |-- OllamaEngine.kt             # Remote LLM via Ollama HTTP API
|   |   |-- ModelHeader.kt              # Header-only model description (GGUF probe / Whisper ggml header)
|   |   +-- AudioRecorder.kt            # Microphone input
|   |-- model/                          # ChatMessage, StoredConversation
|   |-- server/                         # OpenAI-compatible API server
|   |   |-- ApiServer.kt                # Ktor HTTPS server
|   |   |-- ApiService.kt               # Foreground service (dataSync; stops at Android's 6 h limit via onTimeout)
|   |   |-- ServerManager.kt            # Server state management
|   |   |-- models/OpenAIModels.kt      # OpenAI wire format
|   |   +-- security/                   # TLS, Ed25519, pairing
|   |       |-- TlsManager.kt           # Self-signed cert (PKCS12)
|   |       |-- AuthorizedKeysStore.kt  # Encrypted key store
|   |       |-- PairingManager.kt       # One-time PIN sessions
|   |       |-- Ed25519Verifier.kt      # Request signature verification
|   |       +-- AuthPlugin.kt           # Ktor auth plugin (Firn-Ed25519 scheme)
|   |-- ui/                             # Jetpack Compose UI ("Instrument" design)
|   |   |-- MainActivity.kt             # App shell: rail, status line, nav host, position ladder, history sheet
|   |   |-- shell/                      # AppShell.kt (Rail, StatusLine, PositionLadder, haze), HistorySheet.kt, ModelStatus.kt
|   |   |-- chat/ChatScreen.kt          # Empty state or conversation, composer below
|   |   |-- chat/ChatEmptyState.kt      # Model World + header readout + next step
|   |   |-- chat/Messages.kt            # Message list: user/agent messages, thinking block
|   |   |-- chat/Composer.kt            # Sine line, modes, attach, dictation, send/stop
|   |   |-- chat/SessionModes.kt        # Session switches (system prompt, web search, Ollama)
|   |   |-- chat/ChatDialogs.kt         # System prompt / web search key / Ollama dialogs
|   |   |-- chat/ChatViewModel.kt       # Chat state, tool registration, post-turn learning loops
|   |   |-- chat/ThinkTagParser.kt      # Thinking token extraction
|   |   |-- memory/                     # Memory screen (Soul, You, Facts, Sessions) + ViewModel
|   |   |-- server/ServerScreen.kt      # Server dashboard + pairing
|   |   |-- models/ModelScreen.kt       # Installed models: load, unload, delete, open a file
|   |   |-- models/BrowseModels.kt      # Curated catalog + Hugging Face search
|   |   |-- models/ModelCatalog.kt      # Curated model entries and browse filters
|   |   |-- models/GgufFilePickerSheet.kt # Quant picker for a repo
|   |   |-- models/ModelFileImport.kt   # System file picker -> copy into app storage
|   |   |-- models/ContextWindowDialog.kt # Context size picker before a chat model loads
|   |   |-- components/                 # Instrument kit: Instrument.kt, SoftSquare.kt, InkOverlays.kt, StatusChip.kt, FirnMark.kt
|   |   |-- components/world/           # Model World: geometry, motion, renderer (ModelWorldGeometry.kt), Canvas (ModelWorld.kt)
|   |   +-- theme/                      # Ink colours, Inter type scale, Phosphor Thin icons, Brand naming, FirnTheme
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
|-- res/font/                           # Inter (Light, Regular, Medium)
+-- cpp/
    |-- CMakeLists.txt                  # Native build config (OpenCL, whisper, mtmd)
    |-- llama_jni.cpp                   # LLM JNI bridge (~2.2K lines)
    |-- whisper_jni.cpp                 # STT JNI bridge
    |-- llama.cpp/                      # Git submodule (custom Adreno fork)
    |-- whisper.cpp/                    # Git submodule (upstream)
    |-- stable-diffusion.cpp/           # Git submodule, not part of the build yet
    +-- opencl-stub/                    # ARM64 OpenCL link-time stub
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0.21, C++17 |
| UI | Jetpack Compose; Instrument component kit on a Material 3 theme mapped to the Ink tokens |
| Type / icons | Inter (bundled, SIL OFL 1.1), Phosphor Icons Thin (generated `ImageVector`s, MIT) |
| LLM (on-device) | llama.cpp (custom Adreno fork: `janik96v/llama.cpp`) |
| LLM (remote) | Ollama via Ktor HTTP Client (OpenAI-compatible API) |
| STT | whisper.cpp (upstream, 1.9.x) |
| Vision | llama.cpp mtmd library |
| GPU | Adreno OpenCL with optimized kernels |
| Server | Ktor 2.3.12 (Netty, HTTPS) |
| MCP Client | Ktor HTTP Client (Streamable HTTP/SSE) |
| Agent Memory | Filesystem plain-text files (app-internal storage) |
| Session Search | Room + SQLite FTS5 (BM25 ranking) |
| Background Work | AndroidX WorkManager (periodic + one-shot consolidation) |
| Security | Ed25519, TLS (PKCS12), AES256-GCM |
| Serialization | kotlinx.serialization, nlohmann/json (C++) |
| Build | Gradle (Kotlin DSL), CMake, NDK r27 (27.2) |
| Target | Android API 28–36 (compile/target SDK 36), arm64-v8a only |

---

## UI Design System

The "Instrument" design lives in `ui/theme/` (tokens) and `ui/components/` (controls). Reuse them; do not add ad-hoc colours, fonts, shapes or icon sets.

- **Colour** (`theme/InstrumentColors.kt`, `Ink`): only white on true black; hierarchy is white at an alpha. Errors carry no hue — severity is in the copy. The faint top haze (`shell/AppShell.kt`) is the only tint.
- **Controls** (`components/SoftSquare.kt`): one primary (white) action per screen; everything else tappable sits on a dark surface; plain text on black is never tappable; selected = filled `#FAFAFA` with black text.
- **Shape**: zero radius for layout, hairlines and sheets (`theme/Theme.kt`, `components/Instrument.kt`); soft-square radii only on controls — 12dp buttons, 14dp cards.
- **Type** (`theme/Typography.kt`, `InkType`): Inter only. Labels and anything the user reads or taps are sentence case; tracked uppercase is for chrome (status, labels) only.
- **Icons** (`theme/PhosphorThin.kt`, `Ph`): Phosphor Thin only; add a glyph by copying its SVG path verbatim.
- **Brand**: "firn" lowercase in running text, `FIRN` in chrome (`theme/Brand.kt`). The mark is never redrawn: `components/FirnMark.kt` transcribes the source SVGs in `docs/brand/`.
- **Model World** (`components/world/`): the chat empty state's point cloud. With no model it is a sphere; once a header is read it folds into a shape derived from the GGUF/ggml header — one ring per block, one point per KV head, radius from embedding length, twist from context length, dot weight from quantization. The geometry is plain Kotlin and unit-tested.

---

## GPU Acceleration

### Adreno OpenCL Backend

firn uses a custom fork of llama.cpp with Adreno OpenCL kernels. Both llama.cpp and whisper.cpp share the same ggml backend.

**GPU-compatible quantizations**: Q4_0 (pure), Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL. Other quantizations lack Adreno kernels and silently fall back to CPU; the quant picker (`GgufFilePickerSheet.kt`) marks GPU compatibility per file.

**Build flags**:
```cmake
GGML_OPENCL=ON
GGML_OPENCL_USE_ADRENO_KERNELS=ON
```

The OpenCL library is linked via a stub (`opencl-stub/`) at compile time and excluded from the APK — the device's `libOpenCL.so` (declared as an optional `uses-native-library`) is used at runtime. `FirnApplication` sets `LM_GGML_OPENCL_ADRENO_USE_LARGE_BUFFER=1` before the native library loads so weights > 1 GB can stay in GPU memory.

### GPU Probe Mechanism

Both LLM and whisper engines use the same GPU safety pattern:
1. Load with GPU offload requested.
2. Install temporary SIGSEGV/SIGBUS handlers (`SA_RESETHAND`) and run a probe: a single 1-token decode (LLM) or `whisper_full` on 1 s of silence (whisper).
3. If a signal fires or the call returns an error: free the context and reload the model CPU-only.
4. The result is exposed as `EngineState.gpuLayers` (0 = CPU-only) for the LLM and `WhisperEngine.isGpuActive` for whisper.

This ensures the app never crashes from GPU driver issues — it falls back to CPU.

### Known GPU Limitations

| Issue | Workaround |
|---|---|
| Quantized KV cache on Adreno OpenCL | GPU path keeps f16 KV, flash attention off; CPU path uses q8_0 K+V with flash attention |
| Flash attention with whisper | Standard attention (MUL_MAT+SOFT_MAX) used instead |
| Flash attention with mmproj | Standard attention used for vision encoding |

---

## Native Build

The native layer is built via CMake through the Android Gradle Plugin:

- **llama.cpp** is included as a git submodule (`app/src/main/cpp/llama.cpp/`) from a custom fork with Adreno kernel support
- **whisper.cpp** is included as a git submodule (`app/src/main/cpp/whisper.cpp/`), sharing the same ggml backend
- **mtmd** (multimodal) is built from `llama.cpp/tools/mtmd/` via `add_subdirectory(... EXCLUDE_FROM_ALL)`
- All three link into a single shared library (`libfirn.so`) exposed via JNI
- Target ABI: arm64-v8a only, C++17, NDK r27 (27.2)
- `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` aligns every native library to 16 KB pages, as Google Play requires for Android 15+ targets (NDK r28 does this by default; r27 needs the opt-in)

### JNI Functions (llama_jni.cpp)

| Function | Purpose |
|---|---|
| `nativeLoadModel` | Load GGUF, init context (KV type and flash attention depend on GPU/CPU path) |
| `nativeProbeGpu` | Test GPU with a 1-token decode under signal handlers |
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
| `nativeProbeGgufMeta` | Header-only GGUF read (`no_alloc=true`) — architecture, name, `n_ctx_train`, param count, embedding/layer/head shape, quant; feeds context-window sizing and `ModelHeader` |
| `nativeLoadMmproj` | Load multimodal projector GGUF |
| `nativeUnloadMmproj` | Free mmproj resources |
| `nativeIsMmprojLoaded` | Query mmproj state |

### JNI Functions (whisper_jni.cpp)

| Function | Purpose |
|---|---|
| `nativeLoadWhisperModel` | Load whisper model from path, `flash_attn` forced off |
| `nativeProbeWhisperGpu` | Test GPU with signal handler on a 1s silent buffer |
| `nativeReloadWhisperCpuOnly` | Free GPU context, reload last model path CPU-only |
| `nativeTranscribe` | Transcribe float PCM (16kHz mono) to text |
| `nativeGetLanguage` | Query detected language code |
| `nativeFreeWhisperModel` | Free whisper context |

---

## Context Window Sizing

`EngineState.computeContextConfig()` derives selectable context sizes from the GGUF header and device RAM, instead of a fixed default.

**1. Header probe** — `nativeProbeGgufMeta` reads the GGUF header only (`gguf_init_from_file` with `no_alloc=true`, no weights loaded) and returns architecture, `n_ctx_train`, param count (summed from tensor shapes), layer count, KV head count, head dimension and quant. Without a path, the loaded model's `ModelInfo` is used.

**2. Recurrent/SSM branch** — if the architecture is a recurrent/SSM family (mamba, rwkv, jamba, falcon-h1, ssm, recurrent), KV cost does not scale with context length the way it does for transformers, so the options are `[16384, 32768, 65536, 131072, 262144]` filtered to `<= n_ctx_train * 4` (train context floored at 8192). Recommended: the first option `>= 65536`, else the largest.

**3. Transformer branch** — computes a RAM budget for the KV cache:
```
availableForKvMb = totalDeviceRamMb - modelWeightMb - 3072 (reserve)   // floored at 512 MB
kvBytesPerToken  = 2 * n_layer * n_head_kv * d_head * kvTypeBytes      // kvTypeBytes: 2 (f16, GPU) or 1 (q8_0, CPU)
maxCtxFromRam    = (availableForKvMb * 1024 * 1024) / kvBytesPerToken
maxSafe          = min(maxCtxFromRam, n_ctx_train) , floored at 2048
```
`modelWeightMb` is estimated from param count and quantization (bits-per-param table: Q2≈2.5, Q3≈3.5, Q4/IQ4≈4.5, Q5≈5.5, Q6≈6.5, Q8≈8.0, F16≈16.0, F32≈32.0, otherwise 5.0). If the header lacks layer/head shape, `kvBytesPerToken` falls back to a size-class estimate from the param count. The offered options are `[4096, 8192, 16384, 32768, 65536, 131072]` filtered to `<= maxSafe`; the recommended option is the first one `>= 10240`, else the largest that fits. Because `maxSafe` is capped at `n_ctx_train`, the transformer branch never offers a size beyond the trained context.

The picker is `ui/models/ContextWindowDialog.kt`, opened from `ModelScreen.kt` before a chat model loads; it shows the computed options with the recommended one marked.

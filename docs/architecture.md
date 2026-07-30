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
    +-- AgentOrchestrator
         |-- Native tool-calling (Jinja + PEG parser)
         |-- ToolRegistry (aggregated MCP tools)
         +-- HttpMcpClient (Streamable HTTP/SSE)

--- parallel ---

Ktor HTTPS Server (port 8443, TLS + Ed25519 auth)
    |  calls same EngineState (serialized via generateMutex)
OpenAI-compatible REST API (/v1/chat/completions)
    |  SSE streaming
Open WebUI / OpenClaw / LangChain / curl
```

**Key constraint**: One `llama_context` per process. Server and agent serialize all engine calls via `EngineState.generateMutex`.

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
|   |   |-- ToolManifestJson.kt         # Tool schema serialization
|   |   |-- mcp/                        # Model Context Protocol client
|   |   |   |-- HttpMcpClient.kt        # Streamable HTTP/SSE transport
|   |   |   +-- McpModels.kt            # JSON-RPC 2.0 + MCP types
|   |   +-- tool/
|   |       |-- ToolRegistry.kt         # Aggregates tools from MCP servers
|   |       +-- TavilyMcp.kt            # Tavily web search integration
|   |-- engine/                         # Inference engines
|   |   |-- InferenceEngine.kt          # Interface + data classes
|   |   |-- LlamaCppEngine.kt           # LLM JNI wrapper + vision
|   |   |-- WhisperEngine.kt            # STT engine
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
|   |   |-- chat/ChatScreen.kt          # Chat + streaming + agent steps
|   |   |-- server/ServerScreen.kt      # Server dashboard + pairing
|   |   |-- models/ModelScreen.kt       # Model library + quant guide
|   |   +-- theme/                      # Material 3 dark theme
|   +-- data/                           # Encrypted persistence
|       |-- ChatConversationStore.kt    # Conversation history (AES256-GCM)
|       |-- AgentPrefs.kt               # Agent config (API keys, MCP URL)
|       +-- MmprojModelPrefs.kt         # Multimodal projector path
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
| LLM | llama.cpp (custom Adreno fork: `janik96v/llama.cpp`) |
| STT | whisper.cpp 1.8.4 |
| Vision | llama.cpp mtmd library |
| GPU | Adreno OpenCL with optimized kernels |
| Server | Ktor 2.3 (Netty, HTTPS) |
| MCP Client | Ktor HTTP Client (Streamable HTTP/SSE) |
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
| `nativeBench` | Prefill/generation throughput benchmark |
| `nativeStopGeneration` | Request cancellation (atomic flag) |
| `nativeUnloadModel` | Free resources |
| `nativeGetSystemInfo` | GPU/backend diagnostics |

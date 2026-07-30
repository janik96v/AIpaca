# AIpaca — Capabilities

A detailed breakdown of everything the app can do, organized by feature area.

---

## 1. On-Device LLM Inference

AIpaca runs GGUF language models entirely on-device via llama.cpp, compiled as a native ARM64 library through JNI.

**Core capabilities:**
- Load any GGUF model from device storage
- GPU-accelerated inference via Adreno OpenCL with optimized kernels
- Automatic GPU probing with CPU fallback (signal handler detects driver crashes)
- Configurable context size (default 10240 tokens)
- Configurable thread count (default 6)
- Full or partial GPU layer offload (`nGpuLayers = -1` for full offload)
- Real-time tokens-per-second measurement
- Stop generation on demand (atomic flag)

**GPU-compatible quantizations:**
Q4_0, Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL

All other quantizations fall back to CPU silently. The app auto-detects GPU compatibility from the model's GGUF metadata (`general.file_type`).

**Reasoning/thinking support:**
Models with DeepSeek-style thinking tokens (e.g., `<think>...</think>`) are supported. Thinking content is streamed separately from visible content via the `TokenCallback.onToken(content, thinking)` interface, displayed in collapsible UI blocks.

---

## 2. Vision / Multimodal

AIpaca supports image+text inference using llama.cpp's `mtmd` (multimodal) library.

**How it works:**
- A separate **mmproj** (multimodal projector) GGUF file is loaded via `mtmd_init_from_file()`
- Images are encoded through the vision pipeline and combined with text tokens
- `mtmd_helper_eval_chunks()` handles the full prefill (text + image encoding)
- The image marker `<__media__>` is inserted before the last user message

**Capabilities:**
- Attach images to chat messages (camera or gallery picker)
- Attach PDFs (text extracted via Android PDFBox)
- Auto-detection of multimodal capability by scanning GGUF metadata keys (`vision.*`, `clip.*`, `siglip.*`, etc.)
- mmproj path persisted via `MmprojModelPrefs` — auto-restored on app startup
- Flash attention disabled for mmproj on Adreno GPU (standard attention works correctly)

**Tested vision models:**
- Gemma 4 E2B Instruct (unsloth) — Q4_0
- Qwen3 4B — Q4_0

---

## 3. Speech-to-Text (STT)

On-device transcription via whisper.cpp (v1.8.4), sharing the same GGML backend as llama.cpp.

**Capabilities:**
- Real-time microphone recording via `AudioRecorder`
- GPU-accelerated transcription (OpenCL, with flash attention disabled for Adreno compatibility)
- Automatic GPU probe with CPU fallback (same signal handler pattern as LLM)
- Persistent whisper model path via `WhisperModelPrefs`
- Transcribed text inserted directly into chat input


---

## 4. Agent Mode

AIpaca includes a native on-device agent that can call external tools via the Model Context Protocol (MCP).

### Agent Loop

The `AgentOrchestrator` implements a think → tool → observe → repeat loop:

```
User Goal
  ↓
[Round 1] LLM generates response (streaming)
  ↓
  Tool call detected? → Yes
    ↓
    Execute tool via MCP client
    ↓
    Append observation to conversation
    ↓
[Round 2] LLM generates response using tool result
  ↓
  Tool call detected? → No
    ↓
  Final answer (grounded in tool results)
```

**Key design decisions:**
- **Native tool calling** — uses llama.cpp's Jinja template system + PEG parser (`common_chat_parse`) for structured tool call extraction, not regex
- **Proper message roles** — Assistant messages carry `tool_calls`, Tool messages carry results with `tool_call_id` — matching the OpenAI tool-calling protocol
- **Max 4 tool rounds** per query (configurable via `AgentConfig.maxToolRounds`)
- **Thread safety** — all engine calls serialized via `EngineState.generateMutex` (server and agent share one engine)

### Tool Calling

Tools are defined using JSON Schema and serialized into the model's prompt via the Jinja template system:

```kotlin
data class AgentToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)
```

The tool manifest is kept intentionally compact to fit within small on-device context windows.

### MCP Client

AIpaca includes a hand-rolled MCP client (`HttpMcpClient`) supporting:
- **Streamable HTTP** transport (not stdio — subprocess spawning is forbidden on Android)
- **SSE** (Server-Sent Events) for streaming responses
- **JSON-RPC 2.0** protocol
- Session management with `Mcp-Session-Id` headers
- Tool discovery via `tools/list`
- Tool execution via `tools/call`

### Current Tools

| Tool | Source | Description |
|---|---|---|
| Tavily Web Search | MCP server | Search the web for current information |

The `ToolRegistry` aggregates tools from multiple MCP server connections into a flat manifest.

### Streaming UI

Agent steps are streamed to the UI in real-time:
- `AgentStep.Thinking` — model reasoning (collapsible)
- `AgentStep.ToolCall` — tool name + arguments
- `AgentStep.ToolObservation` — tool result
- `AgentStep.FinalAnswer` — grounded final response


---

## 5. OpenAI-Compatible REST API Server

AIpaca exposes an HTTPS server on port 8443 that implements the OpenAI chat completions API.

### Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | None | Health check + model status |
| `POST` | `/v1/pair` | PIN | Register client Ed25519 public key |
| `GET` | `/v1/models` | Ed25519 | List loaded models |
| `POST` | `/v1/chat/completions` | Ed25519 | Chat inference (streaming/non-streaming) |

### Compatibility

Tested with:
- OpenAI Python SDK (`openai.OpenAI(base_url=...)`)
- Open WebUI
- OpenClaw
- `curl`
- Any HTTP client that speaks the OpenAI wire format

### Request Format

Standard OpenAI `/v1/chat/completions` format with support for:
- `messages` (system, user, assistant roles)
- `stream` (true/false, SSE when streaming)
- `temperature`, `top_p`, `max_tokens`
- `frequency_penalty`, `presence_penalty`
- `stop` sequences
- `include_thinking` (AIpaca extension for reasoning models)

### Foreground Service

The server runs as an Android foreground service (`ApiService`) with a persistent notification showing the server URL. This keeps the server alive when the screen is off or the app is in the background.


---

## 6. Security

### Authentication — Ed25519 Asymmetric Keys

AIpaca uses SSH-style public key authentication:
1. Client generates an Ed25519 keypair locally
2. Client sends public key + 6-digit PIN to AIpaca via `/v1/pair`
3. AIpaca stores the public key in an encrypted key store
4. All subsequent API requests must include a signed `Authorization` header
5. Signature format: `AIpaca-Ed25519:<base64-pubkey> <base64-sig> <unix-timestamp>`
6. Timestamp window: +/-30 seconds (replay protection)

### Transport — TLS

- Self-signed PKCS12 certificate generated on first server start
- HTTPS-only (port 8443)
- Certificate bound to device IP

### Encryption at Rest

| Data | Method |
|---|---|
| Conversation history | EncryptedSharedPreferences (AES256-GCM) |
| Agent config (API keys, MCP URL) | EncryptedSharedPreferences (AES256-GCM) |
| Authorized client keys | EncryptedSharedPreferences (AES256-GCM) |
| TLS certificate | Android Keystore |

---

## 7. User Interface

Built with Jetpack Compose and Material 3 (dark theme).

### Chat Tab
- Message list with streaming token display
- Input field with send button
- Microphone button for speech-to-text
- Image/PDF attachment picker
- Agent mode toggle (when enabled in settings)
- Collapsible thinking blocks for reasoning models
- Drawer menu with conversation history and settings

### Models Tab
- Curated list of tested models with links to Hugging Face
- Quantization compatibility guide (GPU vs CPU)
- Load model from device storage
- GPU layer visualization

### Server Tab
- Server status indicator (running/stopped)
- Local network address display
- Start/stop server button
- Device pairing interface (QR code + PIN)
- List of paired devices

### UI Features
- Edge-to-edge layout (Android 15 support)
- Keyboard IME handling with auto-scroll
- Real-time state updates via StateFlow
- Animated streaming indicators (spinner, progress bars)

---

## 8. Data Persistence

| Component | Storage | Purpose |
|---|---|---|
| `ChatConversationStore` | EncryptedSharedPreferences | Conversation history (multiple saved chats) |
| `AgentPrefs` | EncryptedSharedPreferences | Tavily API key, MCP server URL, agent toggle |
| `WhisperModelPrefs` | SharedPreferences | Last used STT model path |
| `MmprojModelPrefs` | SharedPreferences | Last used multimodal projector path |
| `AuthorizedKeysStore` | EncryptedSharedPreferences | Paired client public keys |

Conversations are stored as serialized `StoredConversation` objects with ID, title, messages, and timestamps.



For GPU acceleration details, build configuration, and project structure, see [architecture.md](architecture.md).

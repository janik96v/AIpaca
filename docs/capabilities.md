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

## 2. Remote LLM (Ollama)

AIpaca can route generation through a remote Ollama server running on the local network instead of the on-device model. This is designed to test the agentic pipeline with larger models (e.g. Qwen3 30B) running on a desktop machine while the app acts purely as an orchestration front-end.

**How it works:**
- `OllamaEngine` connects to Ollama's OpenAI-compatible API (`/v1/chat/completions`) via Ktor HTTP client with SSE streaming
- Server URL and model name are configured through a connection dialog in the Modes menu and persisted via `OllamaPrefs` (plain SharedPreferences — not sensitive)
- `EngineState.useOllama` StateFlow controls which engine is active; toggled via `enableOllama()` / `disableOllama()`
- `ChatViewModel.sendMessage()` routes to `OllamaEngine.generateChat()` when Ollama is active
- `AgentOrchestrator` accepts an optional `OllamaEngine?` and routes agent generation through it when present, using Ollama's native OpenAI tool-calling format instead of the Jinja/PEG parser path
- Connectivity can be tested via `OllamaEngine.listModels()`, which probes Ollama's `/api/tags` endpoint

**Capabilities:**
- Chat mode: streaming token generation with `<think>...</think>` tag parsing for thinking models (e.g. Qwen3)
- Agent mode: full tool-calling pipeline, with streamed tool call deltas accumulated and parsed from SSE chunks
- Stop generation on demand (atomic flag, compatible with ongoing SSE reads)
- 5-minute request timeout for large slow models

**Network access:**
- `network_security_config.xml` allows cleartext HTTP for local network access (Ollama typically runs on plain HTTP port 11434)
- Default server: `http://192.168.1.100:11434`, default model: `qwen3:30b`

**Key differences from on-device inference:**
- No `generateMutex` — HTTP calls are stateless and do not block the on-device engine
- No GPU probing or quantization concerns — the remote server handles all of that
- Tool calls use Ollama's native OpenAI format rather than llama.cpp's Jinja template + PEG parser

---

## 3. Vision / Multimodal

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

## 4. Speech-to-Text (STT)

On-device transcription via whisper.cpp (v1.8.4), sharing the same GGML backend as llama.cpp.

**Capabilities:**
- Real-time microphone recording via `AudioRecorder`
- GPU-accelerated transcription (OpenCL, with flash attention disabled for Adreno compatibility)
- Automatic GPU probe with CPU fallback (same signal handler pattern as LLM)
- Persistent whisper model path via `WhisperModelPrefs`
- Transcribed text inserted directly into chat input


---

## 5. Agent Mode

AIpaca includes a native on-device agent that can call external tools via the Model Context Protocol (MCP) and built-in local tools. It also supports routing through a remote Ollama engine for testing with larger models.

### Agent Loop

The `AgentOrchestrator` implements a think → tool → observe → repeat loop:

```
User Goal
  ↓
[Round 1] LLM generates response (streaming)
  ↓
  Tool call detected? → Yes
    ↓
    Execute tool (MCP or local)
    ↓
    Append observation to conversation
    ↓
[Round 2] LLM generates response using tool result
  ↓
  Tool call detected? → No
    ↓
  Final answer (grounded in tool results)
  ↓
[LearnPass] Counter-triggered post-turn review (background)
```

**Key design decisions:**
- **Native tool calling** — uses llama.cpp's Jinja template system + PEG parser (`common_chat_parse`) for structured tool call extraction, not regex (on-device path)
- **OpenAI tool calling** — uses Ollama's native streamed tool call delta format when `OllamaEngine` is active
- **Proper message roles** — Assistant messages carry `tool_calls`, Tool messages carry results with `tool_call_id` — matching the OpenAI tool-calling protocol
- **Capability-based tiers** — tool budget and round limit are derived from model capabilities, not user toggles (see Tiered Execution below)
- **Thread safety** — on-device engine calls serialized via `EngineState.generateMutex`; Ollama calls are stateless HTTP and do not acquire the mutex

### Tiered Execution

There is no agent mode toggle. What a turn is allowed to do follows from what the loaded model can actually do, probed at load time. The `AgentTier` policy selects one of three tiers:

| Tier | Max Tools | Max Rounds | When |
|---|---|---|---|
| `PLAIN` | 0 | 1 | No tool-calling support, image attached, or context < 4096 |
| `ASSISTED` | 3 | 2 | Context size 4096–8191 |
| `DEEP` | 6 | 6 | Context >= 8192, or remote backend (Ollama) |

Tools are selected in priority order: `memory`, `session_search`, `web_search` (if configured), `session_view`, `skill_view`, `skill_manage`. Lower tiers get only the highest-priority tools that fit their budget.

The only user-facing choice is whether web search may send queries off-device (requires a Tavily API key).

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

### Agent Memory

The agent has persistent memory across sessions, implemented as a set of plain-text files in app-internal storage (`filesDir/agent_memory/`).

**Memory files:**
- `agent_soul.md` — persona, core identity, guiding principles (max ~1375 chars / ~500 tokens)
- `agent_user.md` — user preferences, communication style, name (max ~1375 chars / ~500 tokens)
- `agent_memory.md` — environment facts, project conventions, corrections, session index (max ~2200 chars / ~800 tokens)

**How it works:**
- Entries are separated by the `§` character (Hermes Agent convention)
- When a file exceeds its character limit after an addition, oldest entries are trimmed (FIFO)
- All three files are injected into the agent's system prompt at the start of each turn so the model has cross-session context
- An `AntiPoisoning` guard rejects writes that contain transient errors or negative claims, preventing the model from permanently recording dead-end states

### Skills

The agent can store and retrieve reusable procedures as named skill files (`filesDir/agent_skills/<name>.md`).

**Skill file format:** YAML frontmatter (`name`, `description`, `category`) followed by a markdown procedure body.

**How it works:**
- The agent receives a compact skill index (name + one-line description for each skill) injected into its system prompt
- Skills are loaded on demand via `skill_view` — this progressive disclosure keeps context size bounded
- New skills or updates are written via `skill_manage`
- Limits: max 50 skills, max 4000 chars per skill body

### Session Search

The `session_search` tool lets the agent search past conversations using SQLite FTS5 full-text search with BM25 ranking. No LLM calls are made — it is pure SQL.

**`MessageDatabase`** (Room + FTS5) indexes all conversation messages. For each matching session, the tool returns a "bookend" snippet:
- First 5 messages (goal context)
- Match window (1 message before + matching message + 1 message after)
- Last 5 messages (resolution)

Up to 5 sessions are returned per query, capped at 6000 characters total.

### Session View

The `session_view` tool lets the agent load a full past conversation by session ID (obtained from `session_search` results or the session index in `agent_memory.md`). It renders the conversation as head/tail messages: up to 8 messages from the start and 8 from the end, each capped at 400 characters, with a total output limit of 6000 characters. Each view bumps the session's access counter.

### Session Index

The `SessionIndexStore` maintains a one-line summary per finished conversation in `agent_memory.md`. After each conversation ends, `SessionSummarizer` generates a short summary and appends it to the index. The index is pruned against live session IDs in the Room database during consolidation.

### LearnPass

After each agent turn (once the user has their answer), a counter-triggered background pass examines a digest of the recent conversation and decides whether to extract skills or memory entries.

**Trigger thresholds** (both default to 10):
- Every 10 user turns → memory review
- Every 10 tool iterations → skill review
- When both thresholds are hit simultaneously → combined review

**How it works:**
- Uses a restricted `ToolRegistry` with only `memory` and `skill_manage` (never calls external MCP tools)
- Receives only the last 6-8 messages as a digest, not full history
- Capped at 4 tool rounds and 512 output tokens — review is deliberately cheap
- The current memory snapshot is injected into the review prompt so the model avoids writing duplicates

### Memory Maintenance (Consolidation)

An idle-time background worker (`MemoryMaintenanceWorker`) runs periodically to consolidate memory:

**Scheduling:** 24-hour periodic via AndroidX WorkManager, with constraints: device must be charging, idle, and battery not low. Can also be triggered on demand from the Memory screen.

**What it does:**
- Merges duplicate memory entries
- Resolves contradictions between entries
- Prunes the session index against live session IDs in the Room database
- Requires at least 5 new entries since the last run (unless triggered manually)
- Gates on: memory loop enabled, backend ready, engine not busy, device not thermally throttled

### Memory Screen

A dedicated bottom-navigation tab (`Memory`) provides full visibility and control over the memory system:

- **Four sub-tabs:** Soul, User, Memory, Sessions — one per memory file plus the session index
- **Editor:** view and edit memory file contents with save/revert
- **Approval flow:** review and approve or reject pending proposals from the learn pass
- **Undo:** restore the previous version of any memory file (one-level backup)
- **Manual triggers:** "Update memory now" button to run extraction on demand, consolidation trigger
- **Status display:** learning on/off toggle, current execution tier chip, consolidation status

### Current Tools

| Tool | Source | Description |
|---|---|---|
| `tavily_search` | MCP server | Search the web for current information (via Tavily) |
| `memory` | Local | Add, replace, or remove entries in persistent memory files |
| `skill_view` | Local | Load the full procedure of a named skill |
| `skill_manage` | Local | Create, patch, or delete a skill |
| `session_search` | Local | FTS5 full-text search over past conversation sessions |
| `session_view` | Local | Load a full past conversation by session ID |

The `ToolRegistry` aggregates tools from all MCP server connections and registered local tools into a single flat manifest.

### Streaming UI

Agent steps are streamed to the UI in real-time:
- `AgentStep.Thinking` — model reasoning (collapsible)
- `AgentStep.ToolCall` — tool name + arguments
- `AgentStep.ToolObservation` — tool result
- `AgentStep.FinalAnswer` — grounded final response


---

## 6. OpenAI-Compatible REST API Server

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

## 7. Security

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

## 8. User Interface

Built with Jetpack Compose and Material 3 (dark theme).

### Chat Tab
- Message list with streaming token display
- Input field with send button
- Microphone button for speech-to-text
- Image/PDF attachment picker
- Modes overflow menu: System prompt, Thinking, and Ollama toggles
- Ollama connection dialog (server URL + model name, test connectivity)
- Collapsible thinking blocks for reasoning models
- Drawer menu with conversation history and settings
- Execution tier chip showing current capability level

### Memory Tab
- Four sub-tabs: Soul, User, Memory, Sessions
- Inline editor for each memory file with save/revert
- Pending proposal review (approve/reject learn pass output)
- Undo last change (one-level backup restore)
- Manual extraction and consolidation triggers
- Learning on/off toggle
- Current tier and consolidation status display

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

## 9. Data Persistence

| Component | Storage | Purpose |
|---|---|---|
| `ChatConversationStore` | EncryptedSharedPreferences | Conversation history (multiple saved chats) |
| `AgentPrefs` | EncryptedSharedPreferences | Tavily API key, MCP server URL |
| `WhisperModelPrefs` | SharedPreferences | Last used STT model path |
| `MmprojModelPrefs` | SharedPreferences | Last used multimodal projector path |
| `OllamaPrefs` | SharedPreferences | Ollama server URL, model name, enabled state |
| `AuthorizedKeysStore` | EncryptedSharedPreferences | Paired client public keys |
| `MemoryStore` | Plain files (filesDir/agent_memory/) | Agent cross-session memory (soul, user, memory) |
| `SkillStore` | Plain files (filesDir/agent_skills/) | Agent learned skill procedures |
| `SessionIndexStore` | Plain files (filesDir/agent_memory/) | One-line session summaries |
| `MessageDatabase` | Room SQLite + FTS5 | Indexed conversation messages for session_search |
| `DownloadedModelStore` | SharedPreferences | Downloaded model tracking |

Conversations are stored as serialized `StoredConversation` objects with ID, title, messages, and timestamps. Agent memory files use plain text (not encrypted) because they contain only model-extracted summaries, not raw user messages.



For GPU acceleration details, build configuration, and project structure, see [architecture.md](architecture.md).

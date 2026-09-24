# AIpaca — Capabilities

A detailed breakdown of everything the app can do, organized by feature area.

---

## 1. On-Device LLM Inference

AIpaca runs GGUF language models entirely on-device via llama.cpp, compiled as a native ARM64 library through JNI.

**Core capabilities:**
- Load any GGUF model from device storage
- GPU-accelerated inference via Adreno OpenCL with optimized kernels
- Automatic GPU probing with CPU fallback (signal handler detects driver crashes)
- Architecture-aware, RAM-aware context window sizing — options and a recommendation are computed from the GGUF header and device memory, not a fixed default (see [Context Window Sizing](#context-window-sizing) below and [architecture.md](architecture.md#context-window-sizing))
- Configurable thread count (default 6)
- Full or partial GPU layer offload (`nGpuLayers = -1` for full offload)
- Real-time tokens-per-second measurement
- Stop generation on demand (atomic flag)

**GPU-compatible quantizations:**
Q4_0, Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL

All other quantizations fall back to CPU silently. The app auto-detects GPU compatibility from the model's GGUF metadata (`general.file_type`).

**Reasoning/thinking support:**
Models with DeepSeek-style thinking tokens (e.g., `<think>...</think>`) are supported. Thinking content is streamed separately from visible content via the `TokenCallback.onToken(content, thinking)` interface, displayed in collapsible UI blocks.

### Context Window Sizing

`EngineState.computeContextConfig()` replaces a fixed default with options derived from the loaded GGUF's header (`nativeProbeGgufMeta` — a header-only read, no tensors loaded) and the device's total RAM:

- **Recurrent/SSM architectures** (mamba, rwkv, jamba, falcon-h1, and similar): KV cost does not scale with context the way it does for transformers, so options go up to 262144 tokens, bounded by the model's trained context.
- **Transformer architectures**: a RAM budget is computed as `total device RAM − estimated model weight − 3 GB reserve`, then converted to a max context length via the KV cache cost per token (`2 × n_layer × n_head_kv × d_head × bytes-per-element`). The result is clamped to the model's trained context length (`n_ctx_train`).

The computed options and a recommended value are shown in a "Context Window" dialog on the Chat and Models tabs. Full formulas: [architecture.md § Context Window Sizing](architecture.md#context-window-sizing).

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
| `DEEP` | 8 (12 on a remote backend) | 6 | Context >= 8192, or remote backend (Ollama) |

The DEEP tool budget was raised from 6 to 8 for the `files` tool (issue #54) — the five local tools plus `files` plus web search no longer fit in six slots. Remote backends (Ollama) are not bound by the on-device context budget, so they get a wider 12-tool ceiling instead.

Tools are selected in priority order: `memory`, `session_search`, `web_search` (if configured), `files`, `session_view`, `skill_view`, `skill_manage`. `files` is DEEP-only — its schema is too large to spend one of ASSISTED's three slots on. Lower tiers get only the highest-priority tools that fit their budget.

The only user-facing choice is whether web search may send queries off-device (requires a Tavily API key).

Malformed or hallucinated tool calls are tolerated up to a point: `TierPolicy.MALFORMED_CALLS_BEFORE_DEGRADE = 2` consecutive malformed calls in a turn triggers a graceful degrade rather than a crash or an infinite retry loop.

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
- `agent_memory.md` — environment facts, project conventions, corrections (max ~2200 chars / ~800 tokens)
- `agent_sessions.md` — the session index (see [Session Index](#session-index) below); a separate file from `agent_memory.md`, with its own caps

**How it works:**
- Entries are separated by the `§` character (Hermes Agent convention)
- When a file exceeds its character limit after an addition, oldest entries are trimmed (FIFO)
- All memory files are injected into the agent's system prompt at the start of each turn so the model has cross-session context; each heading now names its backing file (e.g. `## Who You Are (memory/agent_soul.md)`) — see [The `files` Tool](#the-files-tool) below for why
- An `AntiPoisoning` guard rejects writes to `agent_user.md` and `agent_memory.md` that contain transient errors or negative claims, preventing the model from permanently recording dead-end states. `agent_soul.md` is read-only for direct writes — see **Pending Proposals** below
- **Undo:** `MemoryStore` keeps the last `MAX_BACKUPS = 5` versions of every file in `agent_memory/backup/`, not just one — the Memory screen's undo restores the most recent backup, but up to five generations are retained on disk
- **Pending proposals:** a write to `agent_soul.md` is never applied directly. It is staged via `MemoryStore.writePending()` to `<file>.pending` (e.g. `agent_soul.md.pending`) and surfaces on the Memory screen for the user to approve (`applyPending()` — snapshots the current file, then applies) or reject (`discardPending()` — deletes the pending file). This is how both the learn pass and the `files` tool route soul changes through user review

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

The `session_view` tool lets the agent load a full past conversation by session ID (obtained from `session_search` results or the session index in `agent_sessions.md`). It renders the conversation as head/tail messages: up to 8 messages from the start and 8 from the end, each capped at 400 characters, with a total output limit of 6000 characters. Each view bumps the session's access counter.

### Session Index

The `SessionIndexStore` maintains a one-line summary per finished conversation in its own file, `agent_sessions.md` (`filesDir/agent_memory/agent_sessions.md`) — a separate file from `agent_memory.md`, with its own caps: `MAX_NOTES = 30` (indexed sessions) and `MAX_INDEX_CHARS = 1500`. After each conversation ends, `SessionSummarizer` generates a short summary and appends it to the index. The index is pruned against live session IDs in the Room database during consolidation, and eviction keeps the 30 highest-scoring notes (recency + access count).

### LearnPass

After each agent turn (once the user has their answer), counter-triggered background passes examine a digest of the recent conversation and decide whether to extract memory facts or a skill. There are two separate passes with different cost profiles:

**Trigger thresholds** (both default to 10):
- Every 10 user turns → `LearnPass` memory review
- Every 10 tool iterations → `SkillReviewPass` skill review
- When both thresholds are hit simultaneously → combined review

**`LearnPass` (memory review):**
- Plain completion, no tool calls — `engine.complete()` with `maxTokens = 256`, asking for line-structured text that is parsed deterministically (`MemoryExtraction`), not a tool-calling round
- Works even on models with no tool-calling template at all, since it never drives the orchestrator
- Receives the last `DIGEST_MESSAGES = 8` messages as a digest, not full history
- The current memory snapshot is injected into the prompt so the model avoids writing duplicates

**`SkillReviewPass` (skill review):**
- Stays on the tool-calling path (`skill_manage` calls), because a skill body is multi-paragraph markdown — the shape line-structured parsing handles badly
- Capped at `MAX_TOOL_ROUNDS = 4` tool rounds — a review is a lookup-and-write, not an investigation
- Gated on `AgentTier.DEEP` — only runs for models that can actually call tools
- Uses a restricted `ToolRegistry` with only `skill_manage` (never calls external MCP tools)

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

A dedicated screen (`Mem` in the navigation rail) provides full visibility and control over the memory system:

- **Four sub-tabs:** Soul, You, Facts, Sessions — one per memory file plus the session index
- **Editor:** view and edit memory file contents with save/revert
- **Approval flow:** review and approve or reject pending proposals from the learn pass, or from a `files` write to `agent_soul.md`
- **Undo:** restore a previous version of any memory file — up to `MAX_BACKUPS = 5` generations are kept per file
- **Manual triggers:** "Update memory now" button to run extraction on demand, consolidation trigger
- **Status display:** learning on/off toggle, current execution tier chip (`tierLabel()`: "Plain chat" / "Assisted" / "Deep"), consolidation status. This tier chip lives only on the Memory tab — the Chat tab has no execution-tier indicator.

### Current Tools

| Tool | Source | Description |
|---|---|---|
| `web_search` | MCP server (Tavily) | Search the web for current information. The tier-policy slot name is `web_search`; the actual MCP tool name the model calls is `tavily_search` — the two names differ |
| `memory` | Local | Add, replace, or remove entries in persistent memory files |
| `files` | Local | Sandboxed list/read/write/append/edit/delete over memory/skills/workspace files (DEEP tier only) — see [The `files` Tool](#the-files-tool) below |
| `skill_view` | Local | Load the full procedure of a named skill |
| `skill_manage` | Local | Create, patch, or delete a skill |
| `session_search` | Local | FTS5 full-text search over past conversation sessions |
| `session_view` | Local | Load a full past conversation by session ID |

The `ToolRegistry` aggregates tools from all MCP server connections and registered local tools into a single flat manifest.

### The `files` Tool

Before this tool existed, the agent's memory reached the model only as frozen prompt text, and the `memory` tool was write-only — asked directly about its own `agent_soul.md`, the agent would truthfully answer it had none. `files` gives the agent live read access to what it already writes.

It is a single tool with an `action` discriminator rather than six separate tools, because tool-schema size is itself a binary enablement factor at small context budgets (`research/20_kurzbericht_edge_kontext.md`, Hebel C). It is DEEP-tier only — its schema is too large to spend one of ASSISTED's three tool slots on.

**Actions and parameters:** `action` (required: `list`/`read`/`write`/`append`/`edit`/`delete`), `path` (required), `content` (write/append), `old_text` + `new_text` (edit).

- **list** — directory listing renders as sorted `- name (N bytes)` for files and `- name/` for subdirectories; file target renders as `path (N bytes)`. Backup and pending entries are hidden from listings.
- **read** — truncates at `MAX_READ_CHARS = 8000` with a `[...truncated]` marker; errors on directories.
- **write / append** — append reads the existing content first, then concatenates.
- **edit** — `replaceFirst(old_text, new_text)`; errors if `old_text` is not found.
- **delete** — workspace root only; refuses to delete the workspace root itself; deletes recursively.

**Three sandbox roots** (`AgentWorkspace`):

| Root | Backing directory | Write behavior |
|---|---|---|
| `memory` | `filesDir/agent_memory` | `agent_user.md` and `agent_memory.md` are writable, routed through the `AntiPoisoning` filter; `agent_soul.md` is read-only for direct writes — a write stages a pending proposal instead |
| `skills` | `filesDir/agent_skills` | Writable, unfiltered |
| `workspace` | `filesDir/agent_workspace` | Writable, unfiltered scratch space |

**Path resolution rejects, in order:** (1) empty path; (2) absolute paths (leading `/` or `\`, or containing `:`); (3) unknown root; (4) approval-gated artifacts — any path segment equal to `backup` or ending in `.pending`, because reading or writing these would route around user approval; (5) canonical-path escape — the target and the root are both canonicalized and the target must resolve under the root, which is what actually catches `..` traversal and symlinks (the raw string is never trusted, only the canonicalized comparison).

**Caps:** `MAX_READ_CHARS = 8000`; `MAX_WRITE_CHARS = 32000` per file, checked against the merged (post-edit) content; `MAX_WORKSPACE_BYTES = 2 MiB` total under `workspace/`, projected before every write.

**Write pipeline, in order:** (1) size cap against the merged content; (2) a write to `agent_soul.md` becomes `memoryStore.writePending(...)` and returns a non-error message explaining it is a proposal awaiting approval, never an overwrite; (3) writes to `agent_user.md`/`agent_memory.md` run `AntiPoisoning.isPoisoned()` against the caller-supplied fragment, not the merged file — so an `append` cannot smuggle disallowed content past a filter that only ever sees already-trusted text; (4) workspace quota projection; (5) `mkdirs()` + `writeText()`.

The chat UI shows "Reading its own files" as the tool-call status string while `files` is active.

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
| `filesDir/agent_memory` (soul, user, memory, sessions) | Plain, unencrypted |
| `filesDir/agent_skills` | Plain, unencrypted |
| `filesDir/agent_workspace` | Plain, unencrypted, and agent-writable — the sandbox scratch space the `files` tool writes to |

The three `filesDir/agent_*` directories are not encrypted. This is a deliberate tradeoff (the content is model-extracted summaries, not raw user messages) but is security-relevant now that `agent_workspace` exists specifically for the agent to write to at will.

---

## 8. User Interface

Built with Jetpack Compose and Material 3 (dark theme).

### Chat Tab
- Message list with streaming token display
- Input field with send button
- Microphone button for speech-to-text
- Image/PDF attachment picker
- Modes overflow menu: System Prompt, Thinking (shown only when the model supports it), Web search, and Ollama
- Ollama connection dialog (server URL + model name, test connectivity)
- Collapsible thinking blocks for reasoning models
- History sheet with conversation history (from the rail's clock icon)
- Model World on the empty chat: the loaded model drawn from its GGUF header — one ring per
  block, one point per KV head, radius from the embedding width, twist from the trained
  context, dot weight from the quantisation — with a six-cell header readout

There is no execution-tier chip on the Chat tab. The tier label is shown only on the Memory tab (see below).

### Memory Tab
- Four sub-tabs: Soul, User, Facts, Sessions
- Inline editor for each memory file with save/revert
- Pending proposal review (approve/reject learn pass output, or a `files` write to `agent_soul.md`)
- Undo last change (restores the most recent of up to 5 retained backups per file)
- Manual extraction and consolidation triggers
- Learning on/off toggle
- Current tier chip ("Plain chat" / "Assisted" / "Deep") and consolidation status display

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
| `SessionIndexStore` | Plain file (filesDir/agent_memory/agent_sessions.md) | One-line session summaries |
| `AgentWorkspace` | Plain files (filesDir/agent_workspace/) | Agent scratch space, backing the `files` tool's `workspace` root |
| `MessageDatabase` | Room SQLite + FTS5 | Indexed conversation messages for session_search |
| `DownloadedModelStore` | SharedPreferences | Downloaded model tracking |

Conversations are stored as serialized `StoredConversation` objects with ID, title, messages, and timestamps. Agent memory files use plain text (not encrypted) because they contain only model-extracted summaries, not raw user messages.



For GPU acceleration details, build configuration, and project structure, see [architecture.md](architecture.md).

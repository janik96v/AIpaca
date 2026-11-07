# firn — Capabilities

A detailed breakdown of everything the app can do, organized by feature area.

---

## 1. On-Device LLM Inference

firn runs GGUF language models entirely on-device via llama.cpp, compiled as a native ARM64 library through JNI.

**Core capabilities:**
- Models are downloaded in-app from Hugging Face (curated catalog, Hub search, quant picker) or imported with "Open a .gguf file" — see [Models](#models)
- GPU-accelerated inference via Adreno OpenCL with optimized kernels
- Automatic GPU probing with CPU fallback (signal handler detects driver crashes)
- Architecture-aware, RAM-aware context window sizing — options and a recommendation are computed from the GGUF header and device memory, not a fixed default (see [Context Window Sizing](#context-window-sizing) below)
- Thread count: available CPU cores, capped at 6
- Full GPU layer offload by default (`nGpuLayers = -1`)
- Tokens per second measured per generation (logged); a native pp/tg benchmark runs from the Server screen
- Stop generation on demand (atomic flag)

**GPU-compatible quantizations:**
Q4_0 (pure Q4_0 only, checked via the tensor histogram), Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL

Ops without an Adreno kernel fall back to CPU silently. The app detects GPU compatibility at load from the model's GGUF metadata (`general.file_type`).

**Reasoning/thinking support:**
Models with DeepSeek-style thinking tokens (e.g., `<think>...</think>`) are supported. Thinking content is streamed separately from visible content via the `TokenCallback.onToken(content, thinking)` interface and shown in a collapsible block above the answer.

### Context Window Sizing

`EngineState.computeContextConfig()` replaces a fixed default with options derived from the GGUF's header (`nativeProbeGgufMeta` — a header-only read, no tensors loaded) and the device's total RAM:

- **Recurrent/SSM architectures** (mamba, rwkv, jamba, falcon-h1, and similar): KV cost does not scale with context the way it does for transformers, so options go up to 262144 tokens, bounded by the model's trained context.
- **Transformer architectures**: a RAM budget is computed as `total device RAM − estimated model weight − 3 GB reserve`, then converted to a max context length via the KV cache cost per token (`2 × n_layer × n_head_kv × d_head × bytes-per-element`). The result is clamped to the model's trained context length (`n_ctx_train`).

The options and the recommended value are offered in the Context window dialog, which opens when a chat model is loaded from the Models screen. Full formulas: [architecture.md § Context Window Sizing](architecture.md#context-window-sizing).

---

## 2. Remote LLM (Ollama)

firn can route generation through a remote Ollama server on the local network instead of the on-device model. This is designed to test the agentic pipeline with larger models (e.g. Qwen3 30B) running on a desktop machine while the app acts purely as an orchestration front-end.

**How it works:**
- `OllamaEngine` connects to Ollama's OpenAI-compatible API (`/v1/chat/completions`) via Ktor HTTP client with SSE streaming
- Server URL and model name are set in the Ollama dialog of the Modes menu and persisted via `OllamaPrefs` (plain SharedPreferences — not sensitive); the remote mode is restored on app start
- `EngineState.useOllama` StateFlow controls which engine is active; toggled via `enableOllama()` / `disableOllama()`. While active, `isLoaded` is reported true so chat, agent and server paths proceed
- `ChatViewModel` routes plain turns to `OllamaEngine.generateChat()` when Ollama is active (image turns stay on-device)
- `AgentOrchestrator` accepts an optional `OllamaEngine?` and routes agent generation through it when present, using Ollama's native OpenAI tool-calling format instead of the Jinja/PEG parser path

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

## 3. Vision / Multimodal and Documents

firn supports image+text inference using llama.cpp's `mtmd` (multimodal) library.

**How it works:**
- A separate **mmproj** (multimodal projector) GGUF file is loaded via `mtmd_init_from_file()`
- Images are downscaled to a 768 px long edge, encoded through the vision pipeline and combined with text tokens
- `mtmd_helper_eval_chunks()` handles the full prefill (text + image encoding)
- The image marker `<__media__>` is inserted before the last user message

**Capabilities:**
- Attach an image from the system photo picker (only while a projector is loaded on top of a local chat model); image turns always run as plain chat, without tools
- Attach a document (`.txt`, `.md`, `.csv`, `.json`, `.pdf`); its text is inlined into the message, capped at ~75% of the context window. PDF text is extracted with Android's `PdfRenderer` on API 35+ and PDFBox-Android (`com.tom-roush:pdfbox-android`) below
- Auto-detection of multimodal capability by scanning GGUF metadata keys (`vision.*`, `clip.*`, `siglip.*`, etc.)
- mmproj path persisted via `MmprojModelPrefs` — auto-restored on app startup once a chat model has loaded
- Flash attention disabled for mmproj on Adreno GPU (standard attention works correctly)

**Tested vision models:**
- Gemma 4 E2B Instruct (unsloth) — Q4_0

---

## 4. Speech-to-Text (STT)

On-device transcription via whisper.cpp (v1.9.1), sharing the same GGML backend as llama.cpp.

**Capabilities:**
- Microphone recording via `AudioRecorder`
- GPU-accelerated transcription (OpenCL, with flash attention disabled for Adreno compatibility)
- Automatic GPU probe with CPU fallback (same signal handler pattern as LLM)
- Whisper models (tiny, base, small) available in the catalog; the model path is persisted via `WhisperModelPrefs` and auto-restored on startup
- Transcribed text is inserted into the composer

---

## 5. Agent Mode

firn includes a native on-device agent that can call external tools via the Model Context Protocol (MCP) and built-in local tools. It also supports routing through a remote Ollama engine for testing with larger models.

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
[Post-turn] Counter-triggered learning passes (background)
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

Tools are selected in priority order: `memory`, `session_search`, `web_search` (if configured), `files`, `session_view`, `skill_view`, `skill_manage`. `files`, `session_view` and the two skill tools are DEEP-only. Lower tiers get only the highest-priority tools that fit their budget.

The only user-facing choice is whether web search may send queries off-device (requires a Tavily API key).

Malformed or hallucinated tool calls are tolerated up to a point: `TierPolicy.MALFORMED_CALLS_BEFORE_DEGRADE = 2` malformed calls in a turn make the orchestrator drop the tools and answer plainly rather than crash or retry forever.

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

firn includes a hand-rolled MCP client (`HttpMcpClient`) supporting:
- **Streamable HTTP** transport (not stdio — subprocess spawning is forbidden on Android)
- **SSE** (Server-Sent Events) for streaming responses
- **JSON-RPC 2.0** protocol
- Session management with `Mcp-Session-Id` headers
- Tool discovery via `tools/list`
- Tool execution via `tools/call`

### Agent Memory

The agent has persistent memory across sessions, implemented as a set of plain-text files in app-internal storage (`filesDir/agent_memory/`).

**Memory files:**
- `agent_soul.md` — persona, core identity, guiding principles (max 1375 chars / ~500 tokens); seeded with a default persona on first launch
- `agent_user.md` — user preferences, communication style, name (max 1375 chars / ~500 tokens)
- `agent_memory.md` — environment facts, project conventions, corrections (max 2200 chars / ~800 tokens)
- `agent_sessions.md` — the session index (see [Session Index](#session-index) below); a separate file from `agent_memory.md`, with its own caps

**How it works:**
- Entries are date-stamped and separated by the `§` character (Hermes Agent convention)
- When a file exceeds its character limit after an addition, oldest entries are trimmed (FIFO)
- On tool-carrying turns (ASSISTED and DEEP), the soul, user and memory files are injected into the system prompt as frozen snapshots; DEEP turns also get the session index and the skill index. Each heading names its backing file (e.g. `## Who You Are (memory/agent_soul.md)`) — see [The `files` Tool](#the-files-tool) below for why. Plain turns carry only the user's system prompt
- An `AntiPoisoning` guard rejects writes that contain transient errors or negative claims, preventing the model from permanently recording dead-end states. It filters the `memory` tool, the `files` tool, extraction, consolidation and skill writes
- `agent_soul.md` is not writable from a turn: the `memory` tool only offers `user` and `memory`, and a `files` write to the soul is staged as a pending proposal
- **Backups:** `MemoryStore` keeps the last `MAX_BACKUPS = 5` versions of every file in `agent_memory/backup/`, snapshotted before each consolidation, approval or manual save. Undo on the Memory screen restores the most recent one
- **Pending proposals:** changes the user must see first are staged via `MemoryStore.writePending()` to `<file>.pending` (e.g. `agent_soul.md.pending`) instead of applied: every consolidation change to `agent_soul.md`, any consolidation that would delete more than 30% of a file (`PENDING_DELETION_RATIO`), and any `files` write to the soul. The user approves (`applyPending()` — snapshots, then applies) or rejects (`discardPending()`) on the Memory screen

### Skills

The agent can store and retrieve reusable procedures as named skill files (`filesDir/agent_skills/<name>.md`).

**Skill file format:** YAML frontmatter (`name`, `description`, `category`) followed by a markdown procedure body.

**How it works:**
- The agent receives a compact skill index (name + one-line description for each skill) injected into its system prompt on DEEP turns
- Skills are loaded on demand via `skill_view` — this progressive disclosure keeps context size bounded
- New skills or updates are written via `skill_manage`
- Limits: max 50 skills, max 60-char description, max 4000 chars per skill body

### Session Search

The `session_search` tool lets the agent search past conversations using SQLite FTS5 full-text search with BM25 ranking. No LLM calls are made — it is pure SQL.

**`MessageDatabase`** (Room + FTS5) indexes all conversation messages. For each matching session, the tool returns a "bookend" snippet:
- First 5 messages (goal context)
- Match window (1 message before + matching message + 1 message after)
- Last 5 messages (resolution)

The top 10 FTS matches are grouped into at most 5 sessions, capped at 6000 characters total.

### Session View

The `session_view` tool lets the agent load a full past conversation by session ID (obtained from `session_search` results or the session index in `agent_sessions.md`). It renders the conversation as head/tail messages: up to 8 messages from the start and 8 from the end, each capped at 400 characters, with a total output limit of 6000 characters. Each view bumps the session's access counter.

### Session Index

The `SessionIndexStore` maintains a one-line summary per conversation in its own file, `agent_sessions.md` (`filesDir/agent_memory/agent_sessions.md`), with its own caps: `MAX_NOTES = 30` (indexed sessions) and `MAX_INDEX_CHARS = 1500`. When the user leaves a conversation (new chat or switching to another), `SessionSummarizer` writes a short summary — only for conversations with at least 4 messages, and at most once per day per conversation. Deleting a conversation removes its note. The index is pruned against live session IDs in the Room database during consolidation, and eviction keeps the 30 highest-scoring notes (recency rank + 2 × access count).

### Learning Passes

After every turn (plain chat included, once the user has their answer), counter-triggered background passes examine a digest of the recent conversation. There are two passes with different cost profiles; each counter resets on its own, and all memory passes share a mutex, so a pass that finds another one running is skipped.

**Trigger thresholds** (both 10):
- Every 10 user turns → `LearnPass` memory extraction
- Every 10 tool iterations, DEEP tier only → `SkillReviewPass` skill review

**`LearnPass` (memory extraction):**
- Plain completion, no tool calls — `engine.complete()` with `maxTokens = 256`, asking for line-structured text that is parsed deterministically (`MemoryExtraction`), not a tool-calling round
- Works on models with no tool-calling template and on the Ollama backend
- Receives the last `DIGEST_MESSAGES = 8` messages as a digest (max 4000 chars), not full history
- Writes only to `agent_user.md` and `agent_memory.md`; at most 4 facts per pass, near-duplicates of existing entries are dropped
- The current memory snapshot is injected into the prompt so the model avoids writing duplicates

**`SkillReviewPass` (skill review):**
- Stays on the tool-calling path (`skill_manage` calls), because a skill body is multi-paragraph markdown — the shape line-structured parsing handles badly
- Capped at `MAX_TOOL_ROUNDS = 4` tool rounds — a review is a lookup-and-write, not an investigation
- Uses a restricted `ToolRegistry` with only `skill_manage` (never calls external MCP tools)

### Memory Maintenance (Consolidation)

An idle-time background worker (`MemoryMaintenanceWorker`) runs `ConsolidationPass` periodically:

**Scheduling:** 24-hour periodic via AndroidX WorkManager, with constraints: device must be charging, idle, and battery not low. "Update now" on the Memory screen queues a one-shot run that drops these constraints and the thresholds below.

**What it does:**
- Snapshots all memory files, then asks the model for line-structured `DROP` / `EDIT` / `MERGE` / `ADD` commands per file (files with at least 4 entries)
- Merges duplicates and resolves contradictions in favour of the newer entry
- Stages soul changes and large deletions as pending proposals (see Agent Memory)
- Prunes the session index against live session IDs in the Room database
- Scheduled runs require at least 5 new entries since the last run and the configured interval to have elapsed
- Gates on: learning enabled, backend ready, engine not busy, device not thermally throttled

### Current Tools

| Tool | Source | Description |
|---|---|---|
| `web_search` | MCP server (Tavily) | Search the web for current information. The tier-policy slot name is `web_search`; the actual MCP tool name the model calls is `tavily_search` — the two names differ |
| `memory` | Local | Add, replace, or remove entries in `user` and `memory` |
| `files` | Local | Sandboxed list/read/write/append/edit/delete over memory/skills/workspace files (DEEP tier only) — see [The `files` Tool](#the-files-tool) below |
| `skill_view` | Local | Load the full procedure of a named skill |
| `skill_manage` | Local | Create, patch, or delete a skill |
| `session_search` | Local | FTS5 full-text search over past conversation sessions |
| `session_view` | Local | Load a full past conversation by session ID |

The `ToolRegistry` aggregates tools from all MCP server connections and registered local tools into a single flat manifest. If web search is configured but the MCP server cannot be reached within 15 s, the turn continues with the local tools.

### The `files` Tool

Before this tool existed, the agent's memory reached the model only as frozen prompt text, and the `memory` tool was write-only — asked directly about its own `agent_soul.md`, the agent would truthfully answer it had none. `files` gives the agent live read access to what it already writes.

It is a single tool with an `action` discriminator rather than six separate tools, because tool-schema size is itself a binary enablement factor at small context budgets. It is DEEP-tier only — its schema is too large to spend one of ASSISTED's three tool slots on.

**Actions and parameters:** `action` (required: `list`/`read`/`write`/`append`/`edit`/`delete`), `path` (required), `content` (write/append), `old_text` + `new_text` (edit).

- **list** — directory listing renders as sorted `- name (N bytes)` for files and `- name/` for subdirectories; file target renders as `path (N bytes)`. Backup and pending entries are hidden from listings.
- **read** — truncates at `MAX_READ_CHARS = 8000` with a `[...truncated]` marker; errors on directories.
- **write / append** — append reads the existing content first, then concatenates.
- **edit** — `replaceFirst(old_text, new_text)`; errors if `old_text` is not found.
- **delete** — only under `workspace/`; refuses to delete the workspace root itself; deletes recursively.

**Three sandbox roots** (`AgentWorkspace`):

| Root | Backing directory | Write behavior |
|---|---|---|
| `memory` | `filesDir/agent_memory` | `agent_user.md` and `agent_memory.md` are writable, routed through the `AntiPoisoning` filter; `agent_soul.md` is read-only for direct writes — a write stages a pending proposal instead |
| `skills` | `filesDir/agent_skills` | Writable, unfiltered |
| `workspace` | `filesDir/agent_workspace` | Writable, unfiltered scratch space |

**Path resolution rejects, in order:** (1) empty path; (2) absolute paths (leading `/` or `\`, or containing `:`); (3) unknown root; (4) approval-gated artifacts — any path segment equal to `backup` or ending in `.pending`, because reading or writing these would route around user approval; (5) canonical-path escape — the target and the root are both canonicalized and the target must resolve under the root, which is what actually catches `..` traversal and symlinks (the raw string is never trusted, only the canonicalized comparison).

**Caps:** `MAX_READ_CHARS = 8000`; `MAX_WRITE_CHARS = 32000` per file, checked against the merged (post-edit) content; `MAX_WORKSPACE_BYTES = 2 MiB` total under `workspace/`, projected before every write.

**Write pipeline, in order:** (1) size cap against the merged content; (2) a write to `agent_soul.md` becomes `memoryStore.writePending(...)` and returns a non-error message explaining it is a proposal awaiting approval, never an overwrite; (3) writes to `agent_user.md`/`agent_memory.md` run `AntiPoisoning.isPoisoned()` against the caller-supplied fragment, not the merged file — so an `append` cannot smuggle disallowed content past a filter that only ever sees already-trusted text; (4) workspace quota projection; (5) `mkdirs()` + `writeText()`.

### Streaming UI

Agent steps (`AgentStep`) are streamed to the chat as they happen:
- `Thinking` — model reasoning, shown in the collapsible thinking block; partial answer text streams live
- `ToolCall` — an inline status line per call: "Looking through past conversations", "Updating memory", "Reading its own files", "Consulting skills", or "Searching: <tool>"
- `ToolObservation` — the status line is marked done; raw tool output is not shown
- `FinalAnswer` — appended below the status lines

---

## 6. OpenAI-Compatible REST API Server

firn exposes an HTTPS server on port 8443 that implements the OpenAI chat completions API. It serves the loaded local model, or the Ollama model while remote mode is active.

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

Pairing and chat example scripts live in `examples/python/` (`pair.py`, `chat.py`).

### Request Format

Standard OpenAI `/v1/chat/completions` format with support for:
- `messages` (system, user, assistant roles)
- `stream` (true/false, SSE when streaming)
- `temperature`, `top_p`, `max_tokens`
- `frequency_penalty`, `presence_penalty`
- `stop` sequences
- `include_thinking` (firn extension for reasoning models)

### Foreground Service

The server runs as an Android `dataSync` foreground service (`ApiService`) with a persistent notification showing the server URL and a "Stop server" action. This keeps the server alive when the screen is off or the app is in the background. The server binds only to the Wi-Fi/Ethernet IPv4 address. Android 15+ caps `dataSync` foreground services at 6 hours per 24; when the cap is hit, `ApiService.onTimeout()` stops the service and the server shuts down cleanly.

---

## 7. Security

### Authentication — Ed25519 Asymmetric Keys

firn uses SSH-style public key authentication:
1. Client generates an Ed25519 keypair locally
2. Client sends public key + 6-digit PIN to firn via `/v1/pair` (PIN valid for 5 minutes, at most 10 failed attempts, constant-time comparison)
3. firn stores the public key in an encrypted key store
4. All subsequent API requests must include a signed `Authorization` header
5. Header format: `Authorization: Firn-Ed25519 <base64-pubkey> <base64-sig> <unix-timestamp>`; the signed message is `Firn-Ed25519:<unix-timestamp>`
6. Timestamp window: +/-30 seconds (replay protection)

### Transport — TLS

- Self-signed certificate generated on first server start, stored as PKCS12
- HTTPS-only (port 8443)
- Certificate carries the device IP as SAN; it is regenerated when the IP changes
- The pairing QR code carries the certificate fingerprint for pinning

### Encryption at Rest

| Data | Method |
|---|---|
| Conversation history (`ChatConversationStore`) | EncryptedSharedPreferences (AES256-GCM) |
| Agent prefs (Tavily API key, learning state) | EncryptedSharedPreferences (AES256-GCM) |
| Authorized client keys | EncryptedSharedPreferences (AES256-GCM) |
| TLS keystore (`filesDir/tls/server.p12`) | PKCS12 with a random password held in EncryptedSharedPreferences (Android Keystore-backed master key) |
| Message search index (`firn_messages.db`) | Plain Room/SQLite, unencrypted — holds message text for `session_search` |
| `filesDir/agent_memory` (soul, user, memory, sessions) | Plain, unencrypted |
| `filesDir/agent_skills` | Plain, unencrypted |
| `filesDir/agent_workspace` | Plain, unencrypted, and agent-writable — the sandbox scratch space the `files` tool writes to |

The `filesDir/agent_*` directories and the FTS database are not encrypted. For the memory files this is a deliberate tradeoff (the content is model-extracted summaries, not raw user messages); the FTS database does contain raw message text. Both are security-relevant, as is `agent_workspace`, which exists specifically for the agent to write to at will.

---

## 8. User Interface

Jetpack Compose, dark only, in the "Instrument" design: true black, white hairlines, tracked uppercase chrome, Inter (three weights) and Phosphor Thin icons. Material 3 remains only as the base theme for the few Material components still in use (menus, snackbars, text fields). Edge-to-edge; the screen stays on while the app is in front.

### Shell

- **Rail** on the left: the firn mark (tap to collapse the rail), then Chat, Mem, Mdl, Srv. At the bottom, the Modes glyph (sliders) and the History glyph (clock), reachable from every screen. With the rail collapsed, the status line carries the rail handle plus Modes and History.
- **Status line** at the top of every screen, e.g. `NO MODEL LOADED`, `READING QWEN3.GGUF HEADER`, `THINKING`, `LISTENING`, `QWEN3.5 4B · GPU · READY`, `QWEN3:30B · REMOTE · READY`, `WHISPER BASE · SPEECH · READY`.
- **Position ladder**: four small squares on the right edge, the current screen lit.
- **Modes menu**: System prompt (dialog with Clear/Done), Thinking on/off (only when the model supports it), Web search (Tavily API key dialog — save, keep or delete the key), Ollama (dialog with server URL and model name — Connect, Update, Disconnect). The glyph turns white while any mode is on.
- **History sheet**: bottom sheet with "New chat" and one row per saved conversation (title, message count, last update, `open` marker for the current one, delete).

### Chat

- **Empty state**: the Model World — a 420-point cloud drawn from the loaded model's GGUF header: one ring per block, one point per KV head, radius from the embedding width, twist from the trained context, dot weight from the quantisation. A Fibonacci sphere when idle or remote; with only a Whisper model loaded it shows that model. Below it, a six-cell header readout (`BLOCKS`, `HEADS`, `EMBD`, `CTX`, `QUANT`, `PARAMS`) and a copy block: "Load a model" with an "Open models" button; "Loading" while weights load; "Ask me anything" with two suggestion chips ("Summarise a PDF" opens the document picker and pre-fills the prompt; "What do you recall" pre-fills a memory question).
- **Messages**: user messages right-aligned in a hairline frame (with attached image or document name); firn's messages left-aligned under the firn mark, which animates while streaming. Text is selectable. Reasoning sits in a collapsible "Thinking" block, open while it streams. Tool calls appear as inline status lines (see [Streaming UI](#streaming-ui)).
- **Composer**: a sine line above the input (near-flat when idle, full amplitude while generating or listening); paperclip, input, and one square action button — send, stop while generating, microphone when a Whisper model is loaded and the input is empty, stop while recording. The paperclip opens the document picker, or a Document/Image menu when a vision projector is loaded. Sending and attaching need a ready model.

There is no execution-tier indicator on the chat screen; the tier label is shown only on the Memory screen.

### Memory

- **Learning** toggle card: "Consolidates while charging · N new notes", or "Paused — nothing is written".
- **Segmented control**: Soul, You, Facts, Sessions — one per memory file plus the session index, each with a one-line hint.
- **Proposed version** card when a pending proposal exists, with Approve and Reject.
- **Entries**, newest date first with date stamps; session notes read as `title — summary`.
- **Edit this file** opens an inline editor with Save, Revert, Undo last change (when a backup exists) and Close.
- **Update now** queues a consolidation run.
- **Footer**: last consolidation time and the current tier (`AGENT PLAIN CHAT` / `ASSISTED` / `DEEP`), computed from the loaded model and context size.

### Models

- **On this phone**: count and storage used/total, then one row per chat, speech and vision file with Load/Unload and delete (with confirmation). Row meta shows quant · size · trained context, or blocks and heads once loaded. A vision projector can only be loaded on top of a local chat model. Active downloads show progress and Cancel; load errors are listed.
- **Context window dialog**: opens when a chat model is loaded; lists the computed options with one marked Recommended, and loads with the tapped size.
- **Browse models**: search field (Hugging Face, debounced), filter chips (All, Chat, Code, Vision, Speech, Embedding), the curated catalog, then Hub results. Rows offer Download, Cancel, Installed, or "Too large" when the build needs more RAM than the device has (weights + 2.5 GB). A collapsible GPU compatibility guide lists which quants run on the Adreno GPU.
- **Quant picker**: Download opens a sheet listing the repo's GGUF/BIN files with size and a GPU/CPU chip. After a chat model, a second sheet offers the repo's mmproj files (or Skip). Downloads keep running across navigation; each repo's files live in their own folder.
- **Open a .gguf file**: system file picker; the file is copied into app storage (skipped if an identical copy exists), its kind inferred from the name (`.bin` → Whisper, `*mmproj*` → projector), and loaded right away.

### Server

- One line of explanation, then the server URL (tap to copy) or `OFFLINE`, and Start/Stop server (Start needs a loaded model).
- **Paired devices**: name and short certificate-style fingerprint per client, with remove. "Pair a new device" (while running) opens a dialog with a QR code (endpoint, PIN, TLS fingerprint), the 6-digit PIN, an expiry countdown and "New PIN" once it expires.
- **Active model** (local only): name, GPU layers or CPU, quant; the last benchmark (pp/tg t/s) and "Run native bench" (pp 128, tg 128, 3 runs).
- **Python quickstart** (while running): `python3 pair.py` and `python3 chat.py "Hello" --stream`.

---

## 9. Data Persistence

| Component | Storage | Purpose |
|---|---|---|
| `ChatConversationStore` | EncryptedSharedPreferences | Conversation history (multiple saved chats) |
| `AgentPrefs` | EncryptedSharedPreferences | Tavily API key and web-search switch, MCP server URL (defaults to Tavily), learning toggle, pass counters, last consolidation time |
| `AuthorizedKeysStore` | EncryptedSharedPreferences | Paired client public keys |
| `TlsManager` | `filesDir/tls/server.p12` + EncryptedSharedPreferences | TLS keystore and its password |
| `WhisperModelPrefs` | SharedPreferences | Last used STT model path |
| `MmprojModelPrefs` | SharedPreferences | Last used multimodal projector path |
| `OllamaPrefs` | SharedPreferences | Ollama server URL, model name, enabled state |
| `DownloadedModelStore` | SharedPreferences (JSON list) | Model files on the phone — downloaded and imported — with repo, path, size and type |
| `ModelDownloadManager` | Files under `filesDir/models/` | Model files: downloads in `models/<owner>_<repo>/`, imports in `models/`. In-flight progress is kept in memory only |
| `MemoryStore` | Plain files (`filesDir/agent_memory/`) | Agent cross-session memory (soul, user, memory), `backup/` and `*.pending` |
| `SessionIndexStore` | Plain file (`filesDir/agent_memory/agent_sessions.md`) | One-line session summaries |
| `SkillStore` | Plain files (`filesDir/agent_skills/`) | Agent learned skill procedures |
| `AgentWorkspace` | Plain files (`filesDir/agent_workspace/`) | Agent scratch space, backing the `files` tool's `workspace` root |
| `MessageDatabase` | Room SQLite + FTS5 (`firn_messages.db`) | Indexed conversation messages for `session_search` |

Conversations are stored as serialized `StoredConversation` objects with ID, title, messages, system prompt and last-update time.

For GPU acceleration details, build configuration, and project structure, see [architecture.md](architecture.md).

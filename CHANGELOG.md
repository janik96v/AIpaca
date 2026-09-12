# Changelog

All notable changes to AIpaca are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

---

## [0.5.0] – 2026-09-12

### Added
- Self-learning memory loop (#52): `agent_soul.md`, `agent_user.md` and an `agent_memory.md`
  index of past conversations, kept up to date by three loops — per-turn extraction,
  a one-line summary per finished conversation, and an idle-time consolidation pass
  that merges duplicates and resolves contradictions while the device is charging
- Memory screen: dedicated bottom-nav tab to view, edit, approve or reject anything the
  loops write, undo the last change, and trigger a consolidation run on demand
- `session_view` tool: loads one past conversation in full, the on-demand half of the
  session index
- Tool-calling capability probe: the model's Jinja chat template is checked at load time;
  tiered execution budgets (Plain / Assisted / Deep) derived from context size and
  backend type
- Ollama remote backend: route generation through a local-network Ollama server
  (`OllamaEngine`) with OpenAI-compatible streaming, agent tool-calling via streamed
  deltas, and a connection dialog in the Modes menu
- Agent memory and skills system: persistent `MemoryStore`, `SkillStore`, `SessionSearchTool`,
  `SessionViewTool`, and counter-triggered `LearnPass` for background extraction
- Architecture-aware, RAM-aware context window sizing (#49): options and a recommended value
  are now computed from a header-only GGUF probe (architecture, trained context, layer/head
  shape) and the device's available RAM, instead of a fixed default. Recurrent/SSM
  architectures get a separate branch since their KV cost does not scale with context the
  same way. Surfaced as a "Context Window" dialog on the Chat and Models tabs
- Sandboxed `files` tool (#54): the agent can list/read/write/append/edit/delete over its own
  memory, skills, and a scratch workspace, gated to the DEEP execution tier. Writes to
  `agent_soul.md` are staged as a pending proposal for the user to approve rather than applied
  directly; `agent_user.md`/`agent_memory.md` writes still go through the anti-poisoning filter

### Changed
- Removed the agent mode toggle. How many tools a turn carries and how many rounds it may
  take now follow from the model's probed capabilities; the only remaining choice is
  whether web search may send queries off-device
- The learn pass runs on every conversation and every backend. It previously sat behind
  the agent path, which required a Tavily API key, so in practice it never ran
- Conversations are indexed for search on every path, not only in agent mode
- Anti-poisoning filter now covers German and no longer rejects ordinary facts that merely
  contain words like "not found"
- Raised `DEEP_MAX_TOOLS` from 6 to 8 (#54) — the local tool set plus `files` plus web search
  no longer fit in six slots. Remote backends (Ollama) are not bound by the on-device context
  budget and get a new `REMOTE_MAX_TOOLS = 12` ceiling instead

### Fixed
- Ollama backend now honours the thinking toggle and streams reasoning tokens as they arrive
  (#55): `reasoning_effort` is actually sent in the request, `delta.reasoning` is parsed out
  of streamed chunks, and the SSE read no longer buffers the entire response before emitting
  anything — tokens now arrive incrementally instead of bursting all at once at the end

---

## [0.4.0] – 2026-08-07

### Added
- Hugging Face model downloads (#48): browse and download GGUF models from HF repos in the
  app, with streaming progress and cancellation, a quant picker showing available variants
  with file sizes and GPU-chip info, and download entries persisted across restarts
- Vision adapter downloads: after selecting an LLM the app offers a matching mmproj GGUF
  when one is available; mmproj files are separated from main model files in HF listings
- Context-window size picker when loading an LLM (512–8192 tokens) with a recommended default
- Load state indicators — downloaded models show a spinner and a "Loaded" status
- Qwen3.5 4B (Q4_K_M) added to the recommended models list

## [0.3.0] – 2026-07-08

### Added
- On-device vision inference via llama.cpp's `mtmd` library with OpenCL GPU acceleration —
  attach images in chat and have vision-capable models analyse them entirely on-device
- Automatic image downscaling for large images to prevent context overflow
- mmproj persistence — the multimodal projector path is restored across app restarts

### Fixed
- Flash attention disabled for mmproj on Adreno GPUs, matching the existing whisper workaround

## [0.2.0] – 2026-06-05

### Added
- On-device speech-to-text via whisper.cpp — mic button in the chat input bar, transcription
  runs fully on-device with no cloud and no Google Play Services (#36)
- OpenCL GPU acceleration for whisper, with a SIGSEGV/SIGBUS probe validating the GPU before
  committing to GPU inference and automatic CPU fallback when the probe fails
- Load/unload whisper models from the Models tab; the model path persists across restarts

### Fixed
- `flash_attn` disabled for whisper — FLASH_ATTN_EXT produces incorrect output for whisper
  tensor shapes on Adreno; standard MUL_MAT+SOFT_MAX attention is used instead

## [0.1.0] – 2025-05-12

### Added
- On-device LLM inference via llama.cpp (GGUF models, ARM64)
- OpenAI-compatible HTTPS server on port 8443 (`POST /v1/chat/completions`, `GET /v1/models`)
- Server-Sent Events streaming for token output
- Ed25519 asymmetric key authentication (SSH-style pairing)
- Self-signed TLS certificate generated on first launch
- QR-code and 6-digit PIN pairing flow
- Built-in streaming chat UI (Jetpack Compose + Material 3)
- Android foreground service — server stays alive with screen off
- Python example scripts (`chat.py`, `pair.py`)
- Full API client guide (`docs/api-client-guide.md`)

[Unreleased]: https://github.com/janik96v/AIpaca/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/janik96v/AIpaca/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/janik96v/AIpaca/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/janik96v/AIpaca/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/janik96v/AIpaca/compare/v0.1.1...v0.2.0
[0.1.0]: https://github.com/janik96v/AIpaca/releases/tag/v0.1.0

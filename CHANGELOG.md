# Changelog

All notable changes to AIpaca are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added
- Self-learning memory loop (#52): `soul.md`, `user.md` and a `memory.md` index of past
  conversations, kept up to date by three loops — per-turn extraction, a one-line summary
  per finished conversation, and an idle-time consolidation pass that merges duplicates
  and resolves contradictions while the device is charging
- Memory screen: view, edit, approve or reject anything the loops write, undo the last
  change, and trigger a consolidation run on demand
- `session_view` tool: loads one past conversation in full, the on-demand half of the
  session index
- Tool-calling capability probe: the model's chat template is checked at load time

### Changed
- Removed the agent mode toggle. How many tools a turn carries and how many rounds it may
  take now follow from the model's probed capabilities; the only remaining choice is
  whether web search may send queries off-device
- The learn pass runs on every conversation and every backend. It previously sat behind
  the agent path, which required a Tavily API key, so in practice it never ran
- Conversations are indexed for search on every path, not only in agent mode
- Anti-poisoning filter now covers German and no longer rejects ordinary facts that merely
  contain words like "not found"

### Planned
- In-app HuggingFace model browser
- Chat history persistence (Room DB)
- GPU acceleration (Vulkan / OpenCL)
- Multi-request queuing
- Multimodal / vision support

---

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

[Unreleased]: https://github.com/janikvollenweider/AIpaca/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/janikvollenweider/AIpaca/releases/tag/v0.1.0

# firn Roadmap

---

## Implemented

| Feature | Details |
|---|---|
| On-device LLM chat | llama.cpp via JNI, streaming tokens, GGUF models |
| GPU acceleration | Adreno OpenCL with optimized kernels (Q4_0, Q6_K, etc.) |
| Speech-to-text | whisper.cpp, GPU-accelerated (standard attention) |
| Vision / multimodal | llama.cpp mtmd, auto-detect from GGUF metadata, PDF extraction |
| Instrument UI | Jetpack Compose redesign: true black, Inter + Phosphor Thin icons, collapsible left rail (CHAT / MEM / MDL / SRV) with status line, soft-square controls |
| Model World | Chat empty state draws the loaded model as a live point cloud built from its GGUF header, with a header readout |
| Conversation history | Encrypted storage (AES256-GCM), multiple saved chats |
| Reasoning tokens | DeepSeek-style thinking tokens, collapsible UI blocks |
| OpenAI-compatible API | Ktor HTTPS server, `/v1/chat/completions`, SSE streaming |
| TLS + Ed25519 auth | Self-signed PKCS12 certs, SSH-style asymmetric keys |
| QR code + PIN pairing | One-tap device registration |
| Foreground service | Server stays alive in background; stops cleanly at Android's 6-hour `dataSync` limit |
| Agent mode | Think->tool->observe loop (AgentOrchestrator) |
| Native tool-calling | llama.cpp Jinja templates + PEG parser |
| MCP client | Streamable HTTP/SSE, JSON-RPC 2.0 |
| Tavily web search | First MCP tool integration |
| Ollama remote backend | Route generation through a local-network Ollama server (OllamaEngine) |
| Tiered execution | Tool count and round budget derived from the model's probed capabilities — no agent toggle |
| Agent memory | agent_soul.md / agent_user.md / agent_memory.md / agent_sessions.md, injected into the prompt each turn as file-path-labeled sections, now complemented by live reads via the `files` tool (issue #54) |
| Self-learning loop | Post-turn extraction, per-session summaries, idle-time consolidation (issue #52) |
| Memory screen | Inspect, edit, approve and undo everything the loops write |
| Sandboxed `files` tool | Agent can list/read/write/edit its own memory, skills, and a scratch workspace; DEEP tier only (issue #54) |
| Context window sizing | Architecture-aware, RAM-aware context options derived from the GGUF header and device RAM |
| In-app model downloads | Catalog, Hugging Face search, quant picker and inline progress; each repo's files in their own folder; local `.gguf` files can be opened too |
| firn rebrand | Renamed from AIpaca: app ID `ch.firn.app`, `libfirn.so`, auth scheme `Firn-Ed25519` |
| Google Play readiness | Targets API 36; all native libraries aligned for 16 KB pages |

---

## Next — KV prefix cache

**Goal**: Reuse KV cache across turns instead of discarding it on every generation. Only new tokens get prefilled. Orthogonal to KV quantization (quant saves RAM; reuse saves prefill time).

**Prerequisite** (done): native tool-calling agent loop with proper tool roles and a stable system + manifest prefix — the multi-turn loop is where the gain is biggest.

| Phase | Description | Status |
|---|---|---|
| 1 | In-session incremental prefill (LCP-trim, decode only new tokens) | Planned |
| 2 | Persistent prefix cache (save/restore stable system prompt cache to disk) | Planned |
| 3 | Block-hash radix tree (multiple prefixes, block-granular, LRU) | Future |

**Acceptance criteria**:
- Agent loop 4 rounds: total prefill time >= 50% lower than baseline
- Persistence roundtrip: save -> unload -> load -> generate produces identical output
- No RAM regression; server/chat still working

---

## Future Roadmap

### Near-Term

| Feature | Description |
|---|---|
| Multi-request queuing | Queue API requests when engine is busy |

### Medium-Term

| Feature | Description |
|---|---|
| Programmatic tool calling | In-process JavaScript sandbox (QuickJS) for batch tool operations |

### Long-Term

| Feature | Description |
|---|---|
| iOS port | SwiftUI frontend sharing the same llama.cpp/whisper.cpp core |
| Encrypted memory files | Move `agent_memory/`, `agent_skills/`, and `agent_workspace/` behind `EncryptedFile`, matching conversation storage. Scope grew with issue #54 — `agent_workspace/` is agent-writable scratch space and is plaintext today, same as the other two |
| Local embedding provider | On-device embedding model for semantic memory search |
| Additional MCP tools | Calculator, calendar, and other local tool integrations. (File system access shipped as the local `files` tool, issue #54 — no longer on this list.) |

---

## Architecture Constraints

These constraints guide all roadmap decisions:

1. **Single engine instance** — One `llama_context` per process. Server + agent serialize via `generateMutex`.
2. **No cloud dependency** — All core features work 100% offline. MCP tools are optional network features.
3. **Native tool calling** — llama.cpp Jinja templates + PEG parser, not prompt-based regex parsing.
4. **Server API unchanged** — The public `/v1/chat/completions` endpoint has no `tools` field. Tool calling is agent-internal only.
5. **Additive changes** — New features are validated before landing. Existing paths (chat, server) are not modified without reason.
6. **Capability over configuration** — What a turn is allowed to do follows from what the loaded model can actually do, probed at load time (`AgentTier`: Plain/Assisted/Deep). Users choose privacy boundaries (may queries leave the device), not execution modes.

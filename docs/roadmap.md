# AIpaca Roadmap

---

## Implemented

| Feature | Details |
|---|---|
| On-device LLM chat | llama.cpp via JNI, streaming tokens, GGUF models |
| GPU acceleration | Adreno OpenCL with optimized kernels (Q4_0, Q6_K, etc.) |
| Speech-to-text | whisper.cpp 1.8.4, GPU-accelerated (standard attention) |
| Vision / multimodal | llama.cpp mtmd, auto-detect from GGUF metadata, PDF extraction |
| Chat UI | Jetpack Compose, Material 3 dark theme, streaming display |
| Conversation history | Encrypted storage (AES256-GCM), multiple saved chats |
| Reasoning tokens | DeepSeek-style thinking tokens, collapsible UI blocks |
| OpenAI-compatible API | Ktor HTTPS server, `/v1/chat/completions`, SSE streaming |
| TLS + Ed25519 auth | Self-signed PKCS12 certs, SSH-style asymmetric keys |
| QR code + PIN pairing | One-tap device registration |
| Foreground service | Server stays alive in background |
| Agent mode | Think->tool->observe loop (AgentOrchestrator) |
| Native tool-calling | llama.cpp Jinja templates + PEG parser |
| MCP client | Streamable HTTP/SSE, JSON-RPC 2.0 |
| Tavily web search | First MCP tool integration |
| Tiered execution | Tool count and round budget derived from the model's probed capabilities — no agent toggle |
| Agent memory | soul.md / user.md / memory.md with a frozen-snapshot prompt layer |
| Self-learning loop | Post-turn extraction, per-session summaries, idle-time consolidation (issue #52) |
| Memory screen | Inspect, edit, approve and undo everything the loops write |

---

## PR1 — Agent Foundation (Native Tool-Calling)

**Goal**: Clean agent loop with native tool-calling via llama.cpp's Jinja template + PEG parser, proper tool message roles, and streaming. Existing chat/server paths stay untouched.

| Increment | Description | Status |
|---|---|---|
| 0 | Test harness (JUnit + MockEngine MCP tests) | Done |
| 1 | Kotlin foundation (AgentModels, AgentMessage, ToolManifestJson) | Done |
| 2 | JNI native tool-calling + engine wiring (`nativeGenerateAgent`) | Done |
| 3 | AgentOrchestrator (structured tool calls, proper tool roles, streaming) | In Progress |
| 4 | Context & manifest tuning (context size >= 4096, compact tool schemas) | Planned |
| 5 | Cleanup (remove old AgentLoop, update UI to use orchestrator) | Planned |

**Acceptance criteria**:
- Real `tavily_search` round with structured tool_calls (native, no regex), grounded answer — fully on-device
- Existing OpenAI server + chat UI unchanged and working
- MCP client unit tests passing
- No crash on malformed/hallucinated tool calls

**Risks**: Tool-capable Jinja template must exist in the GGUF (Qwen2.5: yes). 3B model tool-call robustness is limited — needs defensive parsing + retry.

> Full spec (German): [docs/internal/research/spec_agent_and_kv_consolidated.md](internal/research/spec_agent_and_kv_consolidated.md)

---

## PR2 — KV-Prefix-Cache

**Goal**: Reuse KV cache across turns instead of discarding it on every generation. Only new tokens get prefilled. Orthogonal to KV quantization (quant saves RAM; reuse saves prefill time).

**Prerequisite**: PR1 completed. The biggest gain is in the multi-turn agent loop, which needs proper tool roles and a stable system+manifest prefix first.

| Phase | Description | Status |
|---|---|---|
| 1 | In-session incremental prefill (LCP-trim, decode only new tokens) | Planned |
| 2 | Persistent prefix cache (save/restore stable system prompt cache to disk) | Planned |
| 3 | Block-hash radix tree (multiple prefixes, block-granular, LRU) | Future |

**Acceptance criteria**:
- Agent loop 4 rounds: total prefill time >= 50% lower than baseline
- Persistence roundtrip: save -> unload -> load -> generate produces identical output
- No RAM regression; server/chat still working

> Full spec (German): [docs/internal/research/spec_kv_prefix_cache.md](internal/research/spec_kv_prefix_cache.md)

---

## Future Roadmap

### Near-Term

| Feature | Description |
|---|---|
| In-app model browser | Browse and download GGUF models from HuggingFace directly |
| Multi-request queuing | Queue API requests when engine is busy |
| Agent UI improvements | Dedicated agent screens, tool result display, session management |

### Medium-Term

| Feature | Description |
|---|---|
| Programmatic tool calling | In-process JavaScript sandbox (QuickJS) for batch tool operations. **[Implementation plan](internal/implementation_plans/ptc_programmatic_tool_calling.md)** · [lab research](internal/lab/scripting_engines.md) |

### Long-Term

| Feature | Description |
|---|---|
| iOS port | SwiftUI frontend sharing the same llama.cpp/whisper.cpp core |
| Encrypted memory files | Move `agent_memory/` behind `EncryptedFile`, matching conversation storage |
| Local embedding provider | On-device embedding model for semantic memory search |
| Additional MCP tools | File system, calculator, calendar, and other local tool integrations |

---

## Architecture Constraints

These constraints guide all roadmap decisions:

1. **Single engine instance** — One `llama_context` per process. Server + agent serialize via `generateMutex`.
2. **No cloud dependency** — All core features work 100% offline. MCP tools are optional network features.
3. **Native tool calling** — llama.cpp Jinja templates + PEG parser, not prompt-based regex parsing.
4. **Server API unchanged** — The public `/v1/chat/completions` endpoint has no `tools` field. Tool calling is agent-internal only.
5. **Additive changes** — New features are gated behind toggles until verified. Existing paths (chat, server) are not modified.
6. **Capability over configuration** — What a turn is allowed to do follows from what the loaded model can actually do, probed at load time. Users choose privacy boundaries (may queries leave the device), not execution modes.

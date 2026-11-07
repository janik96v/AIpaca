<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/firn-lockup-white.svg">
  <img src="docs/brand/firn-lockup-black.svg" alt="firn" width="240">
</picture>

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2028%2B-green.svg)](https://developer.android.com/about/versions/pie)
[![OpenAI Compatible](https://img.shields.io/badge/API-OpenAI%20compatible-orange.svg)](#openai-compatible-rest-api)

**On-device AI for Android — LLM, vision, speech, and agent capabilities, all running locally on your phone.**

> firn was formerly called AIpaca.

firn runs GGUF models entirely on-device via llama.cpp with GPU acceleration. It provides a built-in chat UI, speech-to-text, multimodal vision, an on-device agent with tool calling and persistent memory, and an OpenAI-compatible REST API server — all without any cloud dependency.

---

## Screenshots

| Chat | Memory | Models | Server |
|:---:|:---:|:---:|:---:|
| ![Chat tab with the model visualised from its GGUF header](docs/screenshots/chat.jpeg) | ![Memory tab with the agent's soul file](docs/screenshots/memory.jpeg) | ![Model browser with downloadable GGUF models](docs/screenshots/models.jpeg) | ![Server tab for the OpenAI-compatible endpoint](docs/screenshots/server.jpeg) |

---

## Features

### On-Device LLM Chat
- Run any GGUF model locally via llama.cpp — no cloud, no subscription
- GPU-accelerated inference on Qualcomm Adreno (OpenCL)
- Architecture-aware, RAM-aware context window sizing — options and a recommendation computed from the GGUF header and device memory
- Streaming token output with real-time display
- Encrypted conversation history with multiple saved chats
- Reasoning/thinking token support (DeepSeek-style models)
- Model World: the loaded model drawn live from its GGUF header (one ring per block, one point per KV head)

### Vision / Multimodal
- Image + text prompts via llama.cpp's mtmd library
- Auto-detection of multimodal capability from GGUF metadata
- Separate mmproj (multimodal projector) file support
- PDF text extraction for document-based queries
- Attach images and documents directly in chat

### Speech-to-Text
- On-device transcription via whisper.cpp (v1.9.1)
- GPU-accelerated (OpenCL, standard attention path)
- Real-time microphone input with automatic transcription

### Agent Mode
- Native on-device agent loop: think → tool call → observe → repeat
- Structured tool calling via llama.cpp Jinja templates + PEG parser
- Capability-based execution tiers (Plain / Assisted / Deep) — no agent toggle
- MCP (Model Context Protocol) client over Streamable HTTP
- Tavily web search integration
- Persistent agent memory (soul, user, environment) with self-learning loop
- Sandboxed `files` tool — the agent can list/read/write/edit its own memory, skills, and a scratch workspace (Deep tier only)
- Skill system with progressive disclosure and background extraction
- Cross-session recall via FTS5 full-text search
- Streaming agent steps in the UI (thinking, tool calls, observations)
- Graceful handling of hallucinated/failed tool calls

### Remote LLM (Ollama)
- Route generation through a local-network Ollama server for testing with larger models
- OpenAI-compatible streaming via Ktor HTTP client
- Full agent tool-calling pipeline over Ollama's native format
- Server URL and model set in the Modes menu (sliders icon on the rail)

### OpenAI-Compatible REST API
- HTTPS server on port 8443 with self-signed TLS certificates
- Full `/v1/chat/completions` compatibility (streaming + non-streaming)
- Works with Open WebUI, OpenClaw, LangChain, any OpenAI SDK, or `curl`
- Ed25519 asymmetric key authentication (SSH-style)
- QR code + PIN device pairing
- Android foreground service keeps server alive in background

For detailed API usage with Python, curl, Android, iOS, and OpenAI SDK examples, see [docs/api-client-guide.md](docs/api-client-guide.md).

For a full breakdown of all capabilities, see [docs/capabilities.md](docs/capabilities.md).

---

## Quick Start

### 1. Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1+ |
| Android NDK | r27.2.12479018 |
| CMake | 3.22+ |
| Device | API 28+, arm64, 4+ GB RAM (Snapdragon recommended for GPU) |

Install NDK and CMake via **Android Studio → SDK Manager → SDK Tools**.

### 2. Clone and initialise

```bash
git clone https://github.com/janik96v/AIpaca.git
cd AIpaca
git submodule update --init --recursive   # pulls llama.cpp + whisper.cpp
```

### 3. Download a model

**MDL tab** → **Browse models** → tap **Download** next to a model. You can also search Hugging Face from the same screen.
To use a GGUF file that is already on the phone, tap **Open a .gguf file** on the MDL tab instead.

| Model | Size | Quant | GPU | Vision | Tool-Calling |
|---|---|---|---|---|---|
| Gemma 4 E2B Instruct (unsloth) | ~2.5 GB | Q4_0 | Yes | Yes | Yes |
| **Qwen 2.5 3B Instruct** (recommended) | ~1.9 GB | Q4_0 | Yes | No | Yes |
| Qwen3 4B | ~2.6 GB | Q4_0 | Yes | No | Yes |
| Qwen3.5 4B (unsloth) | ~2.7 GB | Q4_K_M | Yes | Yes | Yes |
| HY-MT 1.5 1.8B (translation, experimental) | ~440 MB | TBD | TBD | No | TBD |

The model browser also lists three Whisper speech-to-text models (Tiny/Base/Small).

> **GPU compatibility:** firn uses the Adreno OpenCL backend with optimized kernels. GPU-accelerated quantizations: Q4_0 (pure), Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL. All others fall back to CPU automatically.

### 4. Build and run

```
Android Studio → Run → Select your device
```

Or via CLI:

```bash
./gradlew installDebug
```

### 5. Load model in app

**MDL tab** → under **On this phone**, tap **Load** next to the model.
The model loads in the background (~5-15 seconds depending on size and GPU offload).

### 6. (Optional) Start the API server

**SRV tab** → tap **Start server**

The notification shows: `firn server • https://192.168.x.x:8443`

### 7. (Optional) Pair a client device

**SRV tab** → tap **Pair a new device** → scan the QR code or enter the 6-digit PIN.

```bash
# Health check — no auth needed
curl -k https://192.168.1.XX:8443/health

# Chat — requires signed Authorization header (see docs/api-client-guide.md)
curl -k -X POST https://192.168.1.XX:8443/v1/chat/completions \
  -H "Authorization: Firn-Ed25519 <pubkey> <sig> <timestamp>" \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

---

## Architecture

```
UI (Jetpack Compose, Instrument design system)
    |  StateFlow
EngineState (process-scoped singleton)
    |-- LlamaCppEngine --> llama_jni.cpp --> llama.cpp (GPU/CPU)
    |-- WhisperEngine ---> whisper_jni.cpp --> whisper.cpp (GPU/CPU)
    |-- OllamaEngine ----> Ktor HTTP Client --> Ollama server (local network)
    |-- AgentOrchestrator
    |    |-- MCP tools (Streamable HTTP/SSE, e.g. Tavily web search)
    |    +-- Local tools (memory, files, session_search/view, skill_view/manage)
    +-- Memory loops (LearnPass, SessionSummarizer, idle-time consolidation)
         +-- MemoryStore / SkillStore / AgentWorkspace (filesystem)

--- parallel ---

Ktor HTTPS Server (port 8443, TLS + Ed25519 auth)
    |  calls same EngineState (serialized via generateMutex)
OpenAI-compatible REST API --> Open WebUI / curl / any OpenAI SDK
```

For project structure, tech stack, GPU details, and build configuration, see [docs/architecture.md](docs/architecture.md).

---

## API Reference

| Endpoint | Auth | Description |
|---|---|---|
| `GET /health` | None | Health check + model status |
| `POST /v1/pair` | PIN | Register client Ed25519 public key |
| `GET /v1/models` | Ed25519 | List loaded models |
| `POST /v1/chat/completions` | Ed25519 | Chat inference (streaming/non-streaming) |

Standard OpenAI `/v1/chat/completions` format with `messages`, `stream`, `temperature`, `max_tokens`, etc. Set `"stream": true` for SSE token streaming.

For full request/response examples, authentication details, and client code (Python, curl, Android, iOS, OpenAI SDK), see [docs/api-client-guide.md](docs/api-client-guide.md).

---

## Roadmap

### Implemented

- [x] On-device LLM chat via llama.cpp
- [x] GPU acceleration (Adreno OpenCL with optimized kernels)
- [x] Streaming token output
- [x] Encrypted conversation history
- [x] Multimodal / vision support (llama.cpp mtmd)
- [x] Speech-to-text (whisper.cpp)
- [x] OpenAI-compatible REST API server
- [x] TLS / HTTPS transport encryption
- [x] Ed25519 asymmetric key authentication
- [x] QR code + PIN device pairing
- [x] Android foreground service for background operation
- [x] Agent mode with think→tool→observe loop
- [x] Native tool-calling (Jinja template + PEG parser)
- [x] Capability-based execution tiers (Plain / Assisted / Deep)
- [x] MCP client (Streamable HTTP/SSE)
- [x] Tavily web search tool integration
- [x] Ollama remote backend (local-network LLM routing)
- [x] Agent memory (soul, user, environment) with self-learning loop
- [x] Skill system with progressive disclosure
- [x] FTS5-based cross-session recall (session_search + session_view)
- [x] Memory screen (view, edit, approve, undo)
- [x] Idle-time memory consolidation (WorkManager)
- [x] Reasoning/thinking token support
- [x] PDF text extraction
- [x] In-app HuggingFace model browser and downloader
- [x] Sandboxed `files` tool for agent self-inspection (Deep tier)
- [x] Architecture-aware, RAM-aware context window sizing
- [x] Instrument redesign with the GGUF Model World
- [x] Google Play readiness (API 36, 16 KB page alignment)

### Planned

- [ ] KV-cache prefix reuse across agent turns (in-session)
- [ ] Persistent KV-cache for system prompt
- [ ] Multi-request queuing for API server
- [ ] Programmatic tool calling (in-process JS sandbox)
- [ ] iOS port (shared llama.cpp core + SwiftUI)

For detailed roadmap with technical specs, see [docs/roadmap.md](docs/roadmap.md).

---

## Documentation

| Document | Description |
|---|---|
| [docs/capabilities.md](docs/capabilities.md) | Detailed breakdown of all app capabilities |
| [docs/architecture.md](docs/architecture.md) | Architecture, tech stack, GPU details, project structure |
| [docs/api-client-guide.md](docs/api-client-guide.md) | REST API guide with code examples (Python, curl, OpenAI SDK) |
| [docs/roadmap.md](docs/roadmap.md) | Implementation status and plans |

---

## Why firn?

PocketPal, SmolChat, Off Grid — all great apps, but none expose a local API.
Termux + llama-server works but it's a developer hack, not a product.

firn is the first **polished Android app** that turns your phone into a portable AI server — usable as an Ollama replacement for Open WebUI and anything that speaks the OpenAI API. Plus it adds on-device agent capabilities, vision, and speech-to-text that work completely offline.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

Bug reports and feature requests go in [GitHub Issues](https://github.com/janik96v/AIpaca/issues) — use the provided templates.

By contributing you agree that your code will be licensed under the [Apache 2.0 License](LICENSE).

---

## License

```
Copyright 2025 Janik Vollenweider

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

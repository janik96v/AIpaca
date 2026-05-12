# AIpaca 🦙📡

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://github.com/janikvollenweider/AIpaca/actions/workflows/ci.yml/badge.svg)](https://github.com/janikvollenweider/AIpaca/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-API%2028%2B-green.svg)](https://developer.android.com/about/versions/pie)
[![OpenAI Compatible](https://img.shields.io/badge/API-OpenAI%20compatible-orange.svg)](#api-reference)

**Turn your Android phone into a portable OpenAI-compatible AI server.**

Run LLMs locally on your phone. Expose them as an OpenAI API on your WiFi network.
Connect OpenClaw, Open WebUI, LangChain — or `curl` — directly to your pocket.

---

## What it does

- Runs GGUF models entirely on-device via llama.cpp (no cloud, no subscription)
- GPU-accelerated inference via Adreno OpenCL on Qualcomm devices
- Exposes `POST /v1/chat/completions` on your local network — 100% OpenAI-compatible
- Works with **OpenClaw**, Open WebUI, any OpenAI SDK, or `curl`
- Built-in chat UI with streaming tokens and conversation history
- Android foreground service keeps the server alive when the screen is off

---

## Quick Start

### 1. Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1+ |
| Android NDK | r27.2.12479018 |
| CMake | 3.22+ |
| Device | API 28+, ≥4 GB RAM (Snapdragon device recommended for GPU acceleration) |

Install NDK and CMake via **Android Studio → SDK Manager → SDK Tools**.

### 2. Clone and initialise

```bash
git clone <your-repo-url>
cd AIpaca
git submodule update --init --recursive   # pulls llama.cpp (custom Adreno OpenCL fork)
```

### 3. Download a model

Download a GGUF file and copy it to your phone (via USB or cloud storage).
The in-app **Models tab** links directly to each model's Hugging Face page.

| Model | Size | Quantization | Status |
|---|---|---|---|
| Gemma 4 E2B Instruct (unsloth) | ~2.5 GB | Q4_0 | Tested |
| **Qwen 2.5 3B Instruct** ← recommended | ~1.9 GB | Q4_0 | Tested |
| Qwen3 4B | ~2.6 GB | Q4_0 | Tested |
| HY-MT 1.5 1.8B (translation) | ~440 MB | TBD | Experimental |

> **GPU compatibility:** AIpaca uses the Adreno OpenCL backend. Formats with GPU kernels: Q4_0, Q4_1, Q4_K_S, Q4_K_M, Q5_K_S, Q5_K_M, Q6_K, Q8_0, IQ4_NL. All others fall back to CPU.

### 4. Build and run

```
Android Studio → Run → Select your device
```

### 5. Load model in app

**Chat tab** → tap **"Load model from storage"** → pick the `.gguf` file.
The model loads in the background (~5-15 seconds depending on size).

### 6. Start the API server

**Server tab** → tap **"START\_SERVER"**

The notification shows: `AIpaca Server • https://192.168.x.x:8443`

### 7. Pair your client device

**Server tab** → tap **"PAIR\_NEW\_DEVICE"** → scan the QR code or enter the 6-digit PIN.

Authentication uses **Ed25519 asymmetric keys** (SSH-style). Your private key never leaves your device.

See **[docs/api-client-guide.md](docs/api-client-guide.md)** for complete instructions for Python, curl, Android, iOS, and the OpenAI SDK.

### 8. Test with curl (after pairing)

```bash
# Health check — no auth needed
curl -k https://192.168.1.XX:8443/health

# Chat — requires signed Authorization header (see docs/api-client-guide.md)
curl -k -X POST https://192.168.1.XX:8443/v1/chat/completions \
  -H "Authorization: AIpaca-Ed25519 <pubkey> <sig> <timestamp>" \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

---

## API Reference

> **Full client guide** (Python, curl, Android, iOS, OpenAI SDK): [docs/api-client-guide.md](docs/api-client-guide.md)

### `GET /health` — public, no auth
```json
{"status": "ok", "model": "Qwen2.5-3B-Instruct-Q4_0", "loaded": true}
```

### `POST /v1/pair` — public, PIN protected
Registers a client's Ed25519 public key. Requires the 6-digit PIN shown in the app.

```json
{ "clientPublicKey": "<base64>", "pin": "123456", "displayName": "My Laptop" }
```

### `GET /v1/models` — requires auth
```json
{"object": "list", "data": [{"id": "Qwen2.5-3B-Instruct-Q4_0", "object": "model"}]}
```

### `POST /v1/chat/completions` — requires auth

**Request** (OpenAI-compatible):
```json
{
  "model": "local",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "What is 2+2?"}
  ],
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 512,
  "top_p": 0.9,
  "frequency_penalty": 0.0,
  "presence_penalty": 0.0,
  "stop": ["<string>"],
  "include_thinking": false
}
```

**Response** (non-streaming):
```json
{
  "id": "chatcmpl-uuid",
  "object": "chat.completion",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "4"}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 24, "completion_tokens": 1, "total_tokens": 25}
}
```

Set `"stream": true` for Server-Sent Events token streaming.

**Authentication header:**
```
Authorization: AIpaca-Ed25519 <base64-pubkey> <base64-signature> <unix-timestamp>
```
Signed message: `AIpaca-Ed25519:<unix-timestamp-seconds>`. Timestamp window: ±30 seconds.

---

## Architecture

```
UI (Jetpack Compose + Material 3)
    ↓ StateFlow
EngineState (singleton)
    ↓ Flow<String>
LlamaCppEngine (Kotlin + JNI)
    ↓ JNI
llama_jni.cpp (C++ bridge)
    ↓ llama.cpp API
llama.cpp (ARM64 native, compiled via NDK)
    ↓ Adreno OpenCL (GPU) / CPU fallback
GGUF model (on-device storage)

─── parallel ───

Ktor HTTPS Server (port 8443, TLS + Ed25519 auth)
    ↓ calls same EngineState
OpenAI-compatible REST API
    ↓ SSE streaming
OpenClaw / Open WebUI / curl
```

---

## Project Structure

```
app/src/main/
├── kotlin/com/aipaca/app/
│   ├── engine/
│   │   ├── InferenceEngine.kt      ← interface + data classes
│   │   └── LlamaCppEngine.kt       ← JNI wrapper + Flow streaming
│   ├── server/
│   │   ├── models/OpenAIModels.kt  ← OpenAI wire-format data classes
│   │   ├── security/
│   │   │   ├── TlsManager.kt       ← self-signed cert generation (PKCS12)
│   │   │   ├── AuthorizedKeysStore.kt ← EncryptedSharedPreferences key store
│   │   │   ├── PairingManager.kt   ← one-time PIN sessions
│   │   │   ├── Ed25519Verifier.kt  ← request signature verification
│   │   │   └── AuthPlugin.kt       ← Ktor authentication plugin
│   │   ├── ApiServer.kt            ← Ktor HTTPS embedded server
│   │   ├── ApiService.kt           ← Android Foreground Service
│   │   └── ServerManager.kt        ← Observable state (StateFlow)
│   ├── data/
│   │   └── ChatConversationStore.kt ← conversation persistence
│   ├── ui/
│   │   ├── MainActivity.kt         ← Single activity, bottom nav
│   │   ├── chat/ChatScreen.kt      ← Streaming chat UI + ViewModel
│   │   ├── server/ServerScreen.kt  ← Server dashboard + ViewModel
│   │   ├── models/ModelScreen.kt   ← Curated model library + quant guide
│   │   ├── components/             ← Shared UI components
│   │   └── theme/                  ← Material 3 dark theme
│   ├── model/
│   │   ├── ChatMessage.kt
│   │   └── StoredConversation.kt
│   ├── EngineState.kt              ← Process-scoped engine singleton
│   └── AIpacaApp.kt
└── cpp/
    ├── CMakeLists.txt
    ├── llama_jni.cpp               ← 5 JNI functions
    └── llama.cpp/                  ← git submodule (custom Adreno OpenCL fork)
```

---

## Roadmap

- [ ] In-app HuggingFace model browser (no manual download)
- [x] API key authentication for the server (Ed25519 asymmetric keys)
- [x] TLS / HTTPS transport encryption
- [x] QR code for one-tap device pairing
- [x] Chat history persistence
- [x] GPU acceleration (Adreno OpenCL)
- [ ] Multi-request queuing
- [ ] Multimodal / vision support (LLaVA)
- [ ] iOS port (shared llama.cpp core + SwiftUI)

---

## Why AIpaca?

PocketPal, SmolChat, Off Grid — all great apps, but none expose a local API.
Termux + llama-server works but it's a developer hack, not a product.

AIpaca is the first **polished Play Store app** that turns your phone into a portable AI server — usable as an Ollama replacement for OpenClaw, Open WebUI, and anything that speaks the OpenAI API.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

Bug reports and feature requests go in [GitHub Issues](https://github.com/janikvollenweider/AIpaca/issues) — use the provided templates.

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

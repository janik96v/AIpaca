# LamaPhone 🦙📡

**Turn your Android phone into a portable OpenAI-compatible AI server.**

Run LLMs locally on your phone. Expose them as an OpenAI API on your WiFi network.
Connect OpenClaw, Open WebUI, LangChain — or `curl` — directly to your pocket.

---

## What it does

- Runs GGUF models entirely on-device via llama.cpp (no cloud, no subscription)
- Exposes `POST /v1/chat/completions` on your local network — 100% OpenAI-compatible
- Works with **OpenClaw**, Open WebUI, any OpenAI SDK, or `curl`
- Built-in chat UI with streaming tokens
- Android foreground service keeps the server alive when the screen is off

---

## Quick Start

### 1. Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1+ |
| Android NDK | r27.2.12479018 |
| CMake | 3.22+ |
| Device | API 28+, ≥4 GB RAM (S24 Ultra recommended) |

Install NDK and CMake via **Android Studio → SDK Manager → SDK Tools**.

### 2. Clone and initialise

```bash
git clone <your-repo-url>
cd LamaPhone
git submodule update --init --recursive   # pulls llama.cpp
```

### 3. Download a model

Download a GGUF file and copy it to your phone (via USB or cloud storage):

| Model | Size | Speed (S24 Ultra) | Download |
|---|---|---|---|
| Qwen2.5-0.5B Q4_K_M | ~400 MB | ~35 t/s | [HuggingFace](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) |
| **Qwen2.5-3B Q4_K_M** ← recommended | ~1.9 GB | ~18 t/s | [HuggingFace](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF) |
| Qwen2.5-7B Q4_K_M | ~4.7 GB | ~8 t/s | [HuggingFace](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF) |

### 4. Build and run

```
Android Studio → Run → Select your device
```

### 5. Load model in app

**Chat tab** → tap **"Load model from storage"** → pick the `.gguf` file.
The model loads in the background (~5-15 seconds depending on size).

### 6. Start the API server

**Server tab** → tap **"Start Server"**

The notification shows: `LamaPhone Server • 192.168.x.x:8080`

### 7. Connect OpenClaw

In your OpenClaw config (`~/.openclaw/config.yml` or equivalent):

```yaml
model: local
provider: openai
baseUrl: http://192.168.1.XX:8080/v1
apiKey: none
```

Replace `192.168.1.XX` with your phone's IP shown in the Server tab.

### 8. Test with curl

```bash
# Health check
curl http://192.168.1.XX:8080/health

# Non-streaming
curl -X POST http://192.168.1.XX:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":false}'

# Streaming
curl -X POST http://192.168.1.XX:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hello!"}],"stream":true}'
```

---

## API Reference

### `GET /health`
```json
{"status": "ok", "model": "Qwen2.5-3B-Instruct-Q4_K_M", "loaded": true}
```

### `GET /v1/models`
```json
{"object": "list", "data": [{"id": "Qwen2.5-3B-Instruct-Q4_K_M", "object": "model"}]}
```

### `POST /v1/chat/completions`

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
  "max_tokens": 512
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
    ↓ file I/O
GGUF model (on-device storage)

─── parallel ───

Ktor HTTP Server (port 8080)
    ↓ calls same EngineState
OpenAI-compatible REST API
    ↓ SSE streaming
OpenClaw / Open WebUI / curl
```

---

## Project Structure

```
app/src/main/
├── kotlin/com/lamaphone/app/
│   ├── engine/
│   │   ├── InferenceEngine.kt      ← interface + data classes
│   │   └── LlamaCppEngine.kt       ← JNI wrapper + Flow streaming
│   ├── server/
│   │   ├── models/OpenAIModels.kt  ← OpenAI wire-format data classes
│   │   ├── ApiServer.kt            ← Ktor embedded server
│   │   ├── ApiService.kt           ← Android Foreground Service
│   │   └── ServerManager.kt        ← Observable state (StateFlow)
│   ├── ui/
│   │   ├── MainActivity.kt         ← Single activity, bottom nav
│   │   ├── chat/ChatScreen.kt      ← Streaming chat UI + ViewModel
│   │   ├── server/ServerScreen.kt  ← Server dashboard + ViewModel
│   │   ├── components/ModelPickerButton.kt
│   │   └── theme/                  ← Material 3 dark theme
│   ├── model/ChatMessage.kt
│   ├── EngineState.kt              ← Process-scoped engine singleton
│   └── LamaPhoneApp.kt
└── cpp/
    ├── CMakeLists.txt
    ├── llama_jni.cpp               ← 5 JNI functions
    └── llama.cpp/                  ← git submodule
```

---

## Roadmap

- [ ] In-app HuggingFace model browser (no manual download)
- [ ] API key authentication for the server
- [ ] Chat history persistence (Room DB)
- [ ] GPU acceleration (OpenCL / Vulkan)
- [ ] QR code for one-tap connection
- [ ] Multi-request queuing
- [ ] Multimodal / vision support (LLaVA)
- [ ] iOS port (shared llama.cpp core + SwiftUI)

---

## Why LamaPhone?

PocketPal, SmolChat, Off Grid — all great apps, but none expose a local API.
Termux + llama-server works but it's a developer hack, not a product.

LamaPhone is the first **polished Play Store app** that turns your phone into a portable AI server — usable as an Ollama replacement for OpenClaw, Open WebUI, and anything that speaks the OpenAI API.

# LamaPhone — Sprint Implementation Plan
> **Goal: Working APK in 3 days. Play Store within 1 week.**
> Target device: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, 12 GB RAM)
> Updated: 2026-04-30

---

## Product Vision (sharpened)

LamaPhone is **not another chat app**. It is the **first polished Play Store app that turns your Android phone into a portable OpenAI-compatible AI server** — connectable by OpenClaw, Open WebUI, LangChain, or any tool that speaks the OpenAI API format.

> PocketPal, SmolChat, Off Grid: chat only, no API server.
> Termux + llama-server: works but developer-only hack, no UI, no product.
> **LamaPhone: the first real product in this space.**

---

## Sprint Architecture (MVP-only)

```
┌──────────────────────────────────────────┐
│              UI Layer                    │
│  Jetpack Compose — minimal but clean     │
│  • Chat screen (streaming)               │
│  • Model picker (file system)            │
│  • Server dashboard (IP, port, on/off)   │
└────────────────┬─────────────────────────┘
                 │
┌────────────────▼─────────────────────────┐
│         InferenceEngine Interface        │
│         LlamaCppEngine (Kotlin+JNI)      │
└──────┬─────────────────────┬────────────┘
       │                     │
┌──────▼──────┐    ┌─────────▼──────────────┐
│ Native C++  │    │    Ktor HTTP Server     │
│ llama.cpp   │    │  POST /v1/chat/completions│
│ .so via NDK │    │  Streaming via SSE      │
│             │    │  Android Foreground Svc │
└──────┬──────┘    └────────────────────────┘
       │
┌──────▼──────┐
│ GGUF Model  │
│ (user picks │
│  from files)│
└─────────────┘
```

---

## What's IN the MVP (Day 1–3)

| Feature | Why it's in MVP |
|---|---|
| llama.cpp inference (CPU) | Core runtime — everything depends on it |
| Load GGUF model from device storage | Users need to bring their own model |
| Streaming token generation | Non-negotiable UX |
| OpenAI-compatible API server | The differentiator — reason to exist |
| LAN access (same WiFi) | OpenClaw integration works via LAN |
| Basic chat UI | Prove it works without external tools |
| Foreground Service for API server | Server stays alive when screen is off |

## What's CUT from MVP (add after launch)

| Cut feature | When to add |
|---|---|
| HuggingFace model browser/download | Week 2 |
| Room DB / persistent chat history | Week 2 |
| GPU acceleration | Week 3 |
| Personas / system prompt presets | Week 3 |
| Authentication for API | Week 2 |
| RAG / document upload | Month 2 |
| iOS port | Month 3+ |

---

## 3-Day Sprint Plan

### Day 1 — Foundation (Sequential, ~3 hours)
*Must complete before parallel agents start.*

**Tasks:**
1. Create Android project (Kotlin, Compose, API 28, `com.lamaphone.app`)
2. Configure `libs.versions.toml` with all dependencies
3. Configure `app/build.gradle.kts` with NDK + CMake
4. Add llama.cpp as git submodule: `engine/src/main/cpp/llama.cpp`
5. Verify clean build (no inference yet — just scaffolding)
6. Create module structure:
   ```
   app/src/main/
   ├── kotlin/com/lamaphone/app/
   │   ├── engine/         ← InferenceEngine.kt + LlamaCppEngine.kt
   │   ├── server/         ← ApiServer.kt + ApiService.kt (Foreground)
   │   ├── ui/             ← MainActivity, ChatScreen, ServerScreen
   │   └── model/          ← data classes
   └── cpp/
       ├── CMakeLists.txt
       ├── llama_jni.cpp
       └── llama.cpp/      ← submodule
   ```

**Deliverable:** Project builds cleanly. Git repo initialized.

---

### Day 1–2 — Three Parallel Agents

After Day 1 foundation is committed, three agents work simultaneously.

---

#### 🤖 Agent A — Native Engine Layer

**Files owned:**
- `app/src/main/cpp/llama_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/kotlin/.../engine/InferenceEngine.kt`
- `app/src/main/kotlin/.../engine/LlamaCppEngine.kt`

**Tasks:**
1. Write `CMakeLists.txt`:
   ```cmake
   cmake_minimum_required(VERSION 3.22)
   project(lamaphone)
   set(CMAKE_CXX_STANDARD 17)
   
   # llama.cpp build flags for Snapdragon 8 Gen 3
   set(LLAMA_NATIVE OFF)
   set(LLAMA_BUILD_TESTS OFF)
   set(LLAMA_BUILD_EXAMPLES OFF)
   set(GGML_OPENMP OFF)  # Android limitation
   
   add_subdirectory(llama.cpp)
   
   add_library(lamaphone SHARED llama_jni.cpp)
   target_link_libraries(lamaphone llama android log)
   ```

2. Write `llama_jni.cpp` with 4 functions:
   ```cpp
   // nativeLoadModel(path: String, nThreads: Int): Long  → context pointer
   // nativeGenerate(ctx: Long, prompt: String, callback: TokenCallback)
   // nativeUnloadModel(ctx: Long)
   // nativeSystemInfo(): String  → show device capabilities
   ```

3. Write `InferenceEngine.kt` interface:
   ```kotlin
   interface InferenceEngine {
       fun loadModel(path: String): Result<Unit>
       fun generate(prompt: String, params: GenerateParams): Flow<String>
       fun unload()
       fun isLoaded(): Boolean
   }
   ```

4. Write `LlamaCppEngine.kt` implementing interface via JNI
5. **Test criteria:** Can load a GGUF model and emit 20 tokens to Logcat

---

#### 🤖 Agent B — OpenAI-Compatible API Server

**Files owned:**
- `app/src/main/kotlin/.../server/ApiServer.kt`
- `app/src/main/kotlin/.../server/ApiService.kt`
- `app/src/main/kotlin/.../server/models/` (request/response DTOs)

**Tasks:**
1. Add Ktor to `libs.versions.toml`:
   ```toml
   ktor = "2.3.12"
   ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
   ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
   ktor-server-content-negotiation = { ... }
   ktor-serialization-kotlinx-json = { ... }
   ```

2. Implement `ApiServer.kt` — full OpenAI-compatible endpoint:
   ```
   POST /v1/chat/completions
   GET  /v1/models
   GET  /health
   ```

3. Request/response models matching OpenAI spec:
   ```kotlin
   @Serializable data class ChatCompletionRequest(
       val model: String,
       val messages: List<Message>,
       val stream: Boolean = false,
       val temperature: Double = 0.7,
       val max_tokens: Int = 512
   )
   ```

4. Streaming via Server-Sent Events (SSE):
   ```
   data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"}}]}
   data: [DONE]
   ```

5. Implement `ApiService.kt` as Android **Foreground Service**:
   - Shows persistent notification: "LamaPhone Server running on 192.168.x.x:8080"
   - Stays alive when screen is off
   - Handles `START_STICKY` for auto-restart

6. Bind to `0.0.0.0:8080` so LAN devices can reach it

7. **Test criteria:**
   ```bash
   curl http://<phone-ip>:8080/health
   curl -X POST http://<phone-ip>:8080/v1/chat/completions \
     -H "Content-Type: application/json" \
     -d '{"model":"local","messages":[{"role":"user","content":"Hello"}],"stream":false}'
   ```
   → Response with generated text from the phone

---

#### 🤖 Agent C — Chat UI + Server Dashboard

**Files owned:**
- `app/src/main/kotlin/.../ui/MainActivity.kt`
- `app/src/main/kotlin/.../ui/ChatScreen.kt`
- `app/src/main/kotlin/.../ui/ServerScreen.kt`
- `app/src/main/kotlin/.../ui/theme/`

**Tasks:**
1. `MainActivity.kt` — single-activity, bottom nav with 2 tabs:
   - 💬 Chat
   - 🖥️ Server

2. `ChatScreen.kt`:
   - Message list (LazyColumn, user + assistant bubbles)
   - Streaming: tokens appear one-by-one via Flow collection
   - Input bar + send button
   - "Load model" button if no model loaded (opens file picker)
   - "Stop" button during generation
   - Basic markdown: bold, code blocks (use `Markwon` or custom Composable)

3. `ServerScreen.kt` — the flagship screen:
   ```
   ┌──────────────────────────────┐
   │  🟢 Server Running           │
   │                              │
   │  http://192.168.1.47:8080    │
   │  [Copy URL]  [Show QR Code]  │
   │                              │
   │  Model: Qwen2.5-3B-Q4_K_M   │
   │  Requests served: 12         │
   │  Avg response time: 2.3s     │
   │                              │
   │  Compatible with:            │
   │  • OpenClaw  ✓               │
   │  • Open WebUI  ✓             │
   │  • Any OpenAI SDK  ✓         │
   │                              │
   │  [Stop Server]               │
   └──────────────────────────────┘
   ```

4. Model file picker using `ActivityResultContracts.OpenDocument` for `.gguf` files
5. **Test criteria:** App opens, model loads, chat works, server screen shows IP

---

### Day 2–3 — Integration + APK

**Sequential — one agent ties everything together:**

1. Wire `LlamaCppEngine` into `ApiServer` — server calls `engine.generate()`
2. Shared `EngineState` (StateFlow) used by both Chat UI and API Server
3. Test full flow on S24 Ultra:
   - Pick GGUF file → model loads
   - Chat works (streaming tokens visible)
   - Server starts → phone IP shown
   - `curl` from laptop → response generated by phone
   - Connect OpenClaw to phone IP → chat via WhatsApp/Telegram
4. Fix crashes, OOM edge cases
5. Build signed release APK
6. Manual smoke test: full end-to-end on S24 Ultra

**Deliverable:** Signed APK — installable, works, OpenClaw-connectable

---

### Day 3–4 — Play Store Submission

1. Create Play Store developer account (if not existing — $25 one-time fee)
2. Screenshots: Chat screen + Server screen (minimum 2)
3. App icon: simple, recognizable (🦙 + 📶 concept)
4. Short description: "Turn your phone into a private AI server. Run LLMs locally. OpenAI-compatible API — works with OpenClaw, Open WebUI, and more."
5. Privacy policy (required): one-page hosted on GitHub Pages
6. Submit to **Internal Testing** track → live within hours
7. Request promotion to **Open Testing** → 1–3 day review

---

## Parallel Agent Map (visual)

```
Hour:   0    4    8   12   16   20   24   32   40   48   56   64   72
        │    │    │    │    │    │    │    │    │    │    │    │    │
        ████████████                                                  Foundation (sequential)
                   │
                   ├──────────────────────────────────────────────►  Agent A: Native Engine
                   ├──────────────────────────────────────────────►  Agent B: API Server
                   ├──────────────────────────────────────────────►  Agent C: Chat UI
                                                       │
                                                       ████████████  Integration Agent
                                                                  │
                                                                  ██  APK Build + Test
```

---

## Key Technical Decisions for Speed

### 1. No Hilt for MVP
DI framework adds setup time. Use simple singletons and manual injection. Refactor to Hilt post-launch.

### 2. No Room DB for MVP
In-memory message list only. Messages lost on app restart. Ship it — add persistence in Week 2.

### 3. Model loading: file picker only
No HuggingFace downloader for MVP. User downloads model via browser, picks file. Adds 5 minutes of setup for user but saves 2 days of dev time.

### 4. Port 8080 hardcoded for MVP
No port configuration. Simple, no UI needed. Add settings post-launch.

### 5. No authentication for MVP
API is open on LAN. Fine for home use / OpenClaw. Add API key support in Week 2 before public production launch.

### 6. llama.cpp version to use
Pin to: `b5080` (or latest stable tag at time of implementation). Check: https://github.com/ggml-org/llama.cpp/releases

---

## Recommended First Model for Testing

**Qwen2.5-3B-Instruct-GGUF (Q4_K_M)**
- Size: ~1.9 GB
- RAM on S24 Ultra: ~2.3 GB (of 12 GB available → loads fine)
- Expected speed: ~18–22 tokens/sec on Snapdragon 8 Gen 3
- Download: https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF

**Backup (smaller, faster testing):**
Qwen2.5-0.5B-Instruct-GGUF (Q4_K_M) — ~400 MB, instant load

---

## OpenClaw Integration Test (Day 3)

After APK is running, test connectivity:

1. Install OpenClaw on laptop
2. In OpenClaw config, set provider to local Ollama-compatible:
   ```yaml
   provider: openai
   baseUrl: http://192.168.1.XX:8080/v1
   model: local
   apiKey: none
   ```
3. Send a message via Telegram → arrives at OpenClaw → routed to LamaPhone → response back on Telegram
4. This is the demo screenshot / video for the Play Store listing

---

## Post-MVP Roadmap (Week 2+)

After the APK ships and you have first users:

| Week | Feature | Why |
|---|---|---|
| 2 | HuggingFace model browser in-app | Remove manual download step |
| 2 | API key authentication | Security before public launch |
| 2 | Chat history (Room DB) | Basic user expectation |
| 3 | GPU acceleration (OpenCL) | 2–3x tokens/sec improvement |
| 3 | Model metrics dashboard | Differentiation from PocketPal |
| 4 | Automatic port forwarding / Tailscale support | Server access outside LAN |
| 5 | Multiple simultaneous requests | Real server behavior |
| 6 | Multimodal (vision) | Next major feature |

---

## Sources & References

- [llama.cpp Android docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [SmolChat — reference for clean JNI bridge](https://github.com/shubham0204/SmolChat-Android)
- [Ktor server documentation](https://ktor.io/docs/server-create-a-new-project.html)
- [OpenAI Chat Completion API spec](https://platform.openai.com/docs/api-reference/chat/create)
- [OpenClaw local model config](https://docs.openclaw.ai/gateway/local-models)
- [PocketPal API server issue #407](https://github.com/a-ghorbani/pocketpal-ai/issues/407) — proof the gap exists

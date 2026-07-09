# AIpaca — Repo-Analyse (Grundlage für Specs #42 & #43)

Repo: https://github.com/janik96v/AIpaca
Analysiert am: 2026-07-09 (flacher Klon `--depth 50`, unauthentifizierte GitHub REST API)
Erstellt von: innovation-specialist (Kanban t_bb2b4001)

## 1. Was ist AIpaca?

Android-App, die ein Smartphone in einen **portablen, OpenAI-kompatiblen LLM-Server**
verwandelt. Modelle (GGUF) laufen lokal on-device über llama.cpp; sie werden im WLAN als
`POST /v1/chat/completions` exponiert. Heute ist AIpaca ein **"Gehirn ohne Hände"** — es
antwortet, aber es *handelt* nicht selbst. Volle Agent-Runtimes (OpenClaw, Open WebUI) laufen
auf separater Hardware und nutzen AIpaca nur als Modell-Backend.

## 2. Sprache, Frameworks, Build

| Aspekt | Wert |
|---|---|
| Sprache | Kotlin `2.0.21` (34 `.kt`) + C++ (4 `.cpp`, llama.cpp/whisper.cpp via JNI) |
| Build | Gradle Kotlin DSL, AGP `8.13.2`, Version-Catalog `gradle/libs.versions.toml` |
| UI | Jetpack Compose (BOM `2025.05.01`), Material3, Navigation-Compose |
| Server | **Ktor 2.3.12 (SERVER, Netty-Engine)** — `ktor-server-*`. **Kein Ktor-Client vorhanden.** |
| Serialisierung | kotlinx.serialization JSON `1.7.3` |
| Nebenläufigkeit | kotlinx.coroutines `1.9.0` (Flow-basiert) |
| Krypto/Store | androidx.security.crypto (`EncryptedSharedPreferences`) |
| SDK | minSdk 28, targetSdk 35, compileSdk 35, NDK `27.2.12479018`, ABI `arm64-v8a` only |
| Tests | **Kein `src/test` / `src/androidTest` vorhanden — Test-Setup ist Greenfield.** |
| Submodule | `app/src/main/cpp/llama.cpp` (Fork `janik96v/llama.cpp`, Branch `aipaca/android-vulkan-build`), `whisper.cpp` |

## 3. Relevante Projektstruktur (Kotlin)

```
com/aipaca/app/
├── AIpacaApp.kt                    Application, ruft EngineState.init(context)
├── EngineState.kt                  ★ Prozess-Singleton, besitzt die EINE LlamaCppEngine
├── engine/
│   ├── InferenceEngine.kt          ★ Interface: loadModel / generate / generateChat(Flow)
│   ├── LlamaCppEngine.kt           JNI-Impl (488 Zeilen)
│   ├── WhisperEngine.kt            STT
│   └── AudioRecorder.kt
├── data/
│   ├── ChatConversationStore.kt    ★ verschlüsselte Konversations-Persistenz (Basis f. Sessions)
│   └── {Mmproj,Whisper}ModelPrefs.kt
├── model/
│   ├── ChatMessage.kt
│   └── StoredConversation.kt
├── server/
│   ├── ApiServer.kt                ★ Ktor-Server, POST /v1/chat/completions
│   ├── ApiService.kt, ServerManager.kt
│   ├── models/OpenAIModels.kt      ★ Wire-Format (ACHTUNG: KEINE tools/tool_calls Felder)
│   └── security/                   Ed25519-Auth, TLS, Pairing (PIN/QR)
└── ui/ (Compose: chat/, models/, server/, components/, theme/)
```

## 4. Kern-APIs (verifizierte Signaturen — Wiederverwendungspunkte)

### Engine-Interface (`engine/InferenceEngine.kt`)
```kotlin
data class GenerateParams(
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f, val maxTokens: Int = 512,
    val topP: Float = 0.95f, val repeatPenalty: Float = 1.1f,
    val thinkingEnabled: Boolean = true)

data class ChatTurn(val role: String, val content: String)     // role: system|user|assistant
data class GenerationChunk(val content: String = "", val thinking: String = "")

interface InferenceEngine {
    suspend fun loadModel(modelPath: String, nThreads: Int=4, contextSize: Int=1024, nGpuLayers: Int=-1): Result<Unit>
    fun generate(userPrompt: String, params: GenerateParams = ...): Flow<GenerationChunk>
    fun generateChat(turns: List<ChatTurn>, params: GenerateParams = ...): Flow<GenerationChunk>  // ★ Agent-Reuse
    fun stopGeneration(); fun unload(); fun isLoaded(): Boolean
    fun getModelInfo(): ModelInfo   // enthält modelName, supportsThinking etc.
}
```

### Orchestrierung (`EngineState.kt`, `object EngineState`)
- Besitzt `val engine: LlamaCppEngine` (genau **eine** Instanz, prozessweit).
- Langlebiger `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- Callback-Wrapper `generateChat(turns, params, onToken, onDone, onError)` setzt `_isGenerating`.
- **Agent-Loop muss über EngineState.engine.generateChat(...) gehen**, damit nur eine Engine existiert.

### Server-Pfad (`server/ApiServer.kt`)
- Ktor `routing { post("/v1/chat/completions") { ... } }`; Auth via Ed25519-Header.
- `buildTurnsFromMessages()` mappt OpenAI-`messages` → `List<ChatTurn>`; erzwingt Default-System-Prompt.
- Ruft `engineState.engine.generateChat(chatTurns, params)` unter `generateMutex.withLock { }` (serialisiert Generierung).
- Streaming: SSE mit `respondBytesWriter(ContentType.Text.EventStream)`.

### Persistenz (`data/ChatConversationStore.kt`)
- `EncryptedSharedPreferences` (AES256-GCM), `loadConversations()/upsert()/delete()`.
- Serialisiert `List<StoredConversation>`. **Basis für Agent-Sessions/Memory (Brick 3).**

## 5. Wichtigster technischer Hebel (für #43 zentral)

Der JNI-Layer (`app/src/main/cpp/llama_jni.cpp`, `format_chat_with_common`, ~Z.635-657) nutzt
**bereits** llama.cpp's `common_chat_templates_init` + `common_chat_templates_apply` mit
`inputs.use_jinja = true`. Das ist genau die Maschinerie, die **native Tool-Calling-Templates**
kennt: `common_chat_templates_inputs` besitzt ein `tools`-Feld, das aktuell **nicht** befüllt wird
(nur `inputs.messages`). → Tool-Calling lässt sich über den vorhandenen Template-Pfad
aktivieren, statt Prompt-Formate von Hand zu bauen.

## 6. Beziehung #42 ↔ #43 (verifiziert aus den Issue-Bodies)

- **#42** (Web Search via MCP / Tavily) ist laut #43 explizit **"Brick 1"** von #43.
- **#43** (natives On-Device Agent-Mode) ist das Dach: Agent-Loop + Kotlin-MCP-Client + Sessions.
- Abhängigkeit: **#42 zuerst umsetzen** → liefert AIpacas ersten echten End-to-End-Tool-Call.
  #43 verallgemeinert danach die Loop (Brick 2) und ergänzt Memory/Sessions (Brick 3).
- Gemeinsame Referenz #34: tool-calling-fähige Modelle (Qwen 2.5 3B / Mistral-7B-Instruct-v0.3).

## 7. Greenfield-Lücken (für beide Specs relevant)

1. **Kein Ktor-Client** → für HTTP/SSE-MCP muss eine Client-Dependency ergänzt werden.
2. **`OpenAIModels.kt` kennt keine tools/tool_calls** → das *öffentliche* OpenAI-Serverformat
   bleibt bewusst unverändert (Non-Goal); die Tool-Integration passiert *intern* im Agent-Loop.
3. **Kein Test-Harness** → Unit-Test-Infrastruktur (JUnit/Kotlin-Test) muss initial eingerichtet werden.

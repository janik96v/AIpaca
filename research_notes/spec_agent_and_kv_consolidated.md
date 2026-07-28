# AIpaca — Konsolidierte Implementation-Spec

**Inhalt:** (A) vollständiger App-Review · (B) ehrliche Bewertung des bestehenden Agent-Mode ·
(C) Agent-Mode-Neubau · (D) KV-Prefix-Cache (Hebel F) · (E) Roadmap, Risiken, Tests
Stand: 2026-07-28 · Alle Code-Fundstellen aus dem echten Repo verifiziert
(`/Users/janikvollenweider/…/AIpaca`, Branch-Kontext u.a. `feature/kv-cache-q8`).

---

# Teil A — Was die App heute kann (Review)

AIpaca ist heute ein **„Gehirn ohne Hände"**: ein portabler, OpenAI-kompatibler On-Device-LLM-Server.
Der Unterbau ist überraschend reif; das meiste ist Produktionsqualität. Bestandsaufnahme nach Schichten:

### A.1 Engine / Inferenz (reif, gut)
- **`EngineState`** (Prozess-Singleton, `EngineState.kt:36`) besitzt genau **eine** `LlamaCppEngine`
  (`:49`) und einen `generateMutex` (`:61`), der alle Generierungen serialisiert. Sauberes Fundament.
- **`InferenceEngine`** (`engine/InferenceEngine.kt`): `loadModel / generate / generateChat(Flow) /
  benchmark / stopGeneration / unload / getModelInfo`. Streaming über `callbackFlow`.
- **`llama_jni.cpp`** (1675 Z.) ist erstaunlich ausgereift:
  - Modell-Load mit **GPU-Probe** (Adreno-SIGSEGV überlebt via `sigsetjmp`/`siglongjmp`, `:463`),
    automatischem **CPU-Fallback** (`LlamaCppEngine.kt` loadModel), Tensor-Histogramm, GPU-Quant-
    Kompatibilitätserkennung (`speed_compatible`, `:332`).
  - **Chat-Template via `common_chat_templates_apply`** mit `use_jinja=true` (`:673–695`) — die
    Maschinerie, die native Tool-Calls *könnte* (aktuell ungenutzt, siehe Teil B).
  - **Thinking-Mode**: Stream-Parser + `<think>`-Suppressor (`:514`, `:603`).
  - **KV-Quant (Hebel A, bereits drin):** `type_k=Q8_0` immer; `type_v=Q8_0`+FA nur CPU-Pfad; GPU
    bleibt F16 wegen Adreno-FA-Risiko (`:359–395`).
  - **Vision** (mmproj/mtmd) + **Whisper-STT** vollständig integriert.
- **Bewusste Einfachheit / Kosten:** `run_generate` ist *stateless per call* — `llama_memory_clear`
  vor (`:801`) und nach (`:960`) jeder Generierung → **kein Prefix-Reuse** (das ist Teil D).

### A.2 Server (reif, gut)
- **`ApiServer`** (Ktor 2.3.12 Server, Netty): `POST /v1/chat/completions` (Streaming-SSE + non-stream,
  `:218`), `GET /v1/models` (`:203`), `GET /health`, `POST /v1/pair` (`:162`).
- **Security**: Ed25519-Header-Auth, TLS-Manager, PIN/QR-Pairing (`server/security/*`).
- Generierung unter `generateMutex.withLock` (`:272`) → teilt sich korrekt die eine Engine.
- **Wichtig:** `OpenAIModels.kt` hat **bewusst keine** `tools`/`tool_calls`-Felder — das öffentliche
  Wire-Format bleibt schlank (Non-Goal, Tool-Integration passiert intern).

### A.3 Persistenz & Daten (solide)
- **`ChatConversationStore`** (`data/`): `EncryptedSharedPreferences` (AES256-GCM),
  `loadConversations/upsert/delete`. Modell: `StoredConversation` (id, title, messages, systemPrompt),
  `ChatMessage` (Role USER/ASSISTANT/SYSTEM, content, thinking, Bild/Doc-Anhang).
- **`AgentPrefs`** (`data/`): verschlüsselt, Tavily-Key + MCP-URL + **Consent-Opt-in** (`isConfigured()`).

### A.4 UI (umfangreich)
- Compose/Material3: `ChatScreen.kt` (75 KB — sehr groß), `ModelScreen`, `ServerScreen`, Editorial-
  Retro-Theme (Terracotta), Bottom-Nav. Vision/Whisper/Thinking im Chat verdrahtet.

### A.5 Greenfield-Lücken (aus `00_repo_analysis.md`)
1. **Kein Test-Harness** (`src/test`/`androidTest` leer) — Unit-Infra muss initial eingerichtet werden.
2. Ktor-**Client** war nicht vorhanden — im Agent-Zweig wurde CIO-Client ergänzt (`HttpMcpClient`).
3. Kontext-Default **1024** (`EngineState.kt:103`) — für Agent zu klein (Hebel B).

**Fazit A:** Engine, Server, Krypto/Persistenz und UI sind tragfähig. Die einzige unfertige/kaputte
Baustelle ist der **Agent-Mode** — und dort auch nur teilweise (Teil B).

---

# Teil B — Agent-Mode: ehrliche Bewertung (was Müll ist, was bleibt)

Der Agent-Mode (`com.aipaca.app.agent.*`) ist ein Brick-1-Skelett aus #43. **Aber „alles Müll" ist zu
hart.** Trenne sauber:

### B.1 Was GUT ist und bleibt (nicht wegwerfen)
| Baustein | Warum behalten |
|---|---|
| **`HttpMcpClient`** (`agent/mcp/`) | Sauberer handgeschriebener JSON-RPC-2.0-Client über **Streamable HTTP** (Spec 2025-06-18), behandelt JSON- **und** SSE-Antwortpfad, `Mcp-Session-Id`-Handling, Timeouts. Die Entscheidung gegen das offizielle Kotlin-SDK (erzwingt Ktor 3 / Kotlin 2.4, kollidiert mit Ktor-2.3.12-Server) ist **korrekt und belegt** (`10_research_mcp_kotlin.md §1/§5`). |
| **`McpModels` / `McpClient` / `ToolRegistry`** | Schlanke, korrekte MCP-Typen; Registry aggregiert mehrere Server zu einem flachen Manifest. Zukunftsfähig. |
| **`AgentPrefs`** | Verschlüsselt, **Consent-Opt-in** vor Netz-Tools — richtig gemacht. |
| **`AgentSession`** auf `ChatConversationStore` | Vernünftige Session-Abstraktion; testbar via `ChatConversationStoreLike`. |
| **`TavilyMcp`** | Endpoint-Bau korrekt (Key als Query-Param, `tools/list` zur Laufzeit statt hartkodiert). |

### B.2 Was WIRKLICH kaputt/faul ist (neu bauen)
1. **★ Native Tool-Calls sind NICHT verdrahtet.** `format_chat_with_common` (`llama_jni.cpp:673–695`)
   füllt nur `inputs.messages`, **nie** `inputs.tools`. Das Jinja-Template bekommt die Tool-Schemata
   also gar nicht — der ganze in `10_research_mcp_kotlin.md §4` recherchierte native Pfad
   (Qwen→Hermes-2-Pro, Mistral-v0.3→Mistral-Nemo, PEG-Parser) liegt brach.
2. **Tools werden als Prompt-Text reingehackt.** `AgentConfig.renderSystemPrompt` (`agent/AgentConfig.kt`)
   klebt eine handgeschriebene `<tool_call>…`-Anweisung in den System-Prompt. Das umgeht das trainierte
   Format des Modells → bei 3B unzuverlässig.
3. **`ToolCallParser` ist Regex.** `<tool_call>\s*(\{.*?\})\s*</tool_call>` mit **non-greedy** `.*?`
   **bricht bei verschachtelten Objekten** in `arguments` (z. B. `{"a":{"b":1}}` → matcht nur bis zur
   ersten `}`). Fragil genau da, wo Tool-Argumente komplex werden.
4. **Tool-Ergebnisse als `ChatTurn("user", "Tool result for X: …")`** (`AgentLoop.run`). Keine echte
   **`tool`-Rolle** → das Chat-Template rendert die Beobachtung nie an der Stelle, die das Modell
   erwartet. Grounding über mehrere Runden leidet.
5. **Nicht-streamend + Voll-Reparse.** `generateOnce` sammelt den ganzen Text und parst danach. Keine
   Live-UI der Tool-Schritte, keine frühe Tool-Call-Erkennung.
6. **Kontext zu klein / Overflow.** Modell wird mit `contextSize=1024` geladen; Manifest + 1–2
   Tool-Ergebnisse sprengen das → **Front-Truncation wirft den System-Prompt weg** (`:782`).
7. **Append-only + Voll-Re-Prefill jede Runde.** Historie wächst unbegrenzt; jeder `generateOnce`
   re-prefillt alles (das ist Hebel F, Teil D). Der `<tool_call>`-Text landet als Assistant-Turn im
   Verlauf und wird jede Runde neu tokenisiert.
8. **Keine Tests** (Greenfield).

**Kurz:** Die **Transport-/MCP-Schicht ist gut**, die **Modell↔Tool-Brücke und der Loop sind faul**.
Der Neubau behält B.1 und ersetzt B.2.

---

# Teil C — Agent-Mode neu gedacht

**Leitprinzip:** Tool-Calling dort verankern, wo es hingehört — im **JNI/Template-Layer über
`inputs.tools`** und llama.cpps **PEG-Tool-Call-Parser** — statt in Kotlin-Regex. Der Loop arbeitet mit
**strukturierten** Tool-Calls und echten **Tool-Rollen**.

### C.1 Zielarchitektur (Schichten)

```
UI (Agent-Tab)                          ← Brick 3, Produktentscheidung
  │  AgentStep-Flow (Thinking/ToolCall/Observation/Final) — streamend
AgentOrchestrator (NEU, ersetzt AgentLoop)
  │  strukturierte tool_calls, echte tool-Rollen, Kompaktierung
  ├── ToolRegistry ──► HttpMcpClient (BEHALTEN)  ──► Tavily / weitere MCP-Server
  └── EngineState.generateAgent(...) ─ generateMutex ─┐
InferenceEngine.generateAgent (NEU)                    │  eine Engine
  │  messages(+tool-Rollen) + tools-Manifest           │
LlamaCppEngine.nativeGenerateAgent (JNI, NEU)          │
  │  inputs.tools füllen · use_jinja · common_chat_parse│
llama_context (mit KV-Prefix-Cache, Teil D) ───────────┘
```

### C.2 JNI-Umbau: natives Tool-Calling (Kern des Neubaus)

**Neue JNI-Funktion** statt `nativeGenerateChat` für den Agent-Pfad (Chat-Pfad bleibt unverändert):

```cpp
// Übergibt Messages (inkl. tool-Rollen) + Tool-Manifest als JSON. Liefert strukturiert:
//   { "content": "...", "tool_calls": [ {"id","name","arguments":{...}} ] }
JNIEXPORT void nativeGenerateAgent(
    jlong ctxPtr,
    jstring messagesJson,   // [{role, content, tool_calls?, tool_call_id?}]  (OpenAI-nah)
    jstring toolsJson,      // [{name, description, parameters(JSON-Schema)}]
    jfloat temperature, jfloat topP, jfloat repeatPenalty,
    jint maxTokens, jint thinkingBudget,
    jobject callback);      // streamt content/thinking; final: strukturierte tool_calls
```

Im Kern von `format_chat_with_common` (`llama_jni.cpp:673`) zusätzlich befüllen:

```cpp
common_chat_templates_inputs inputs;
inputs.use_jinja = true;
inputs.messages  = /* aus messagesJson, inkl. role=="tool" + tool_call_id */;
inputs.tools     = parse_tools(toolsJson);   // ★ common_chat_tool{name, description, parameters}
inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
common_chat_params cp = common_chat_templates_apply(tmpls.get(), inputs);
// … generieren wie bisher (streamend) …
// Danach NATIV parsen statt Kotlin-Regex:
common_chat_msg msg = common_chat_parse(full_output, /*is_partial=*/false, {.format = cp.format});
// msg.tool_calls[i] = {name, arguments (JSON), id}  →  als JSON an Kotlin zurück
```

**Warum das der Hebel ist** (`00_repo_analysis.md §5`, `10_research_mcp_kotlin.md §4`): `common/chat.h`
hat `tools` + `use_jinja` + `parse_tool_calls`; neuere llama.cpp nutzt einen **PEG-Parser**
(`COMMON_CHAT_FORMAT_PEG_*`) → robustes, modellnatives Parsing statt Brace-fragiler Regex. Voraussetzung:
Modell mit tool-fähigem Jinja-Template (Qwen2.5-3B hat es; via `chat_template_tool_use` prüfbar).

### C.3 Echte Tool-Rollen im Message-Modell

Der Turn muss über `system/user/assistant` hinaus:
- **assistant mit `tool_calls`** (die Modell-Anfrage) und
- **`tool` mit `tool_call_id` + content** (das Ergebnis).

→ `ChatTurn` erweitern oder ein `AgentMessage` einführen, das nach `messagesJson` serialisiert. Damit
rendert das Template die Beobachtung an der korrekten Stelle, und Multi-Round-Grounding funktioniert.

### C.4 `AgentOrchestrator` (ersetzt `AgentLoop`)

```
run(goal, session):
  messages = [system(persona+instr)] + session.history + [user(goal)]
  tools    = registry.manifest()  → als JSON-Schema (kompakt, Hebel C)
  round = 0
  while round++ < maxToolRounds:
     result = engine.generateAgent(messages, tools, params)   // streamt Thinking/Content
     emit Thinking(result.content)
     if result.tool_calls is empty:
         emit FinalAnswer(result.content); persist; return
     messages += assistant(content, tool_calls=result.tool_calls)   // echte Rolle
     for call in result.tool_calls:                                 // ggf. parallel_tool_calls
         emit ToolCall(call)
         obs = registry.callTool(call.name, call.arguments)         // HttpMcpClient (behalten)
         emit ToolObservation(obs)
         messages += tool(tool_call_id=call.id, content=compact(obs))  // Hebel C/D: kürzen
  finalize()  // letzte, tool-freie Antwort erzwingen
```

Verbesserungen ggü. heute: strukturierte Calls (kein Regex), echte Tool-Rollen, streamende Steps,
optional parallele Tool-Calls, **Kompaktierung** der Beobachtungen (nur relevante Felder ins Message).

### C.5 Kontext & Modell (Hebel B/C aus dem Kurzbericht anwenden)
- **Agent-Preset lädt Modell mit `contextSize ≥ 4096`** (nicht 1024) — sonst scheitert es am Manifest.
- **Kompakte Tool-Schemata** ans Modell (knappe Description, minimales JSON-Schema), nicht das rohe
  Tavily-JSON. `20_kurzbericht §4 Hebel C`: binärer Enablement-Effekt bei knappem Budget.
- **Default-Modell Qwen2.5-3B-Instruct Q4_0** (Hermes-2-Pro-Handler, 2 KV-Heads → KV-sparsam;
  `20_kurzbericht §5`). Mistral-7B-v0.3 als optionale Qualitätsstufe für 12-GB-Geräte.
- **Nebenläufigkeit:** unbedingt weiter über `EngineState.generateMutex` (Server + Agent teilen die
  eine Engine — `AgentLoop`-Doc, `EngineState.kt:61`).

### C.6 Was aus dem alten Code konkret wird
| Alt | Neu |
|---|---|
| `AgentLoop` (Regex, user-Rolle, non-stream) | **`AgentOrchestrator`** (strukturiert, tool-Rolle, streamend) — Neubau |
| `ToolCallParser` (Regex) | **entfällt** als Primärpfad; nativ via `common_chat_parse`. Optional als Fallback-Netz behalten. |
| `AgentConfig.renderSystemPrompt` (klebt `<tool_call>`-Instruktion) | **`AgentConfig`** ohne Tool-Instruktion; Persona+Instr bleiben, Tools kommen über `inputs.tools` |
| `HttpMcpClient`, `McpModels`, `ToolRegistry`, `AgentPrefs`, `TavilyMcp`, `AgentSession` | **behalten** (ggf. minimale Anpassung) |

---

# Teil D — KV-Prefix-Cache (Hebel F), integriert mit dem neuen Agent

Unverändert gültig aus der separaten Spec — **aber der Neubau macht ihn wertvoller**, weil der stabile
Präfix jetzt **sauber definiert** ist: `system (persona+instr)` + das über `inputs.tools` gerenderte
**Tool-Manifest**. Das ist ein deterministischer, sessionstabiler Präfix — idealer Cache-Kandidat.

### D.1 Prinzip (Wiederholung, knapp)
Heute: `llama_memory_clear` vor/nach jeder Generierung (`:801`/`:960`) → jeder Turn re-prefillt alles.
Hebel F: KV über Turns **behalten**, längstes gemeinsames Token-Präfix (LCP) bestimmen, nur den
divergenten Suffix aus dem KV entfernen (`llama_memory_seq_rm`), nur neue Tokens dekodieren.

### D.2 Phasen (engine-layer, agent-agnostisch)
- **Phase 1 — In-Session Incremental Prefill.** `LlamaContext` merkt `cached_tokens` + `conversationId`.
  In `run_generate`/`nativeGenerateAgent` LCP-Trim statt `clear`. **Größter Gewinn genau im
  Agent-Loop:** Runde N prefillt nur die neue `tool`-Message statt System+Manifest+Historie erneut.
- **Phase 2 — Persistenter Präfix-Cache.** `llama_state_seq_save_file`/`load_file` für den stabilen
  Präfix (persona+instr+Manifest), Key = `sha256(modelHash + ctxParams{n_ctx,type_k,type_v,gpu|cpu} +
  templateId + prefixText)`. Restore in `EngineState.loadModel` → erster Agent-Turn spart den
  System+Manifest-Prefill komplett.
- **Phase 3 — Block-Hash-Radix** (optional): mehrere Präfixe/Personas, block-granular, LRU. Nur bei Bedarf.

### D.3 Zwei Verträge an den (neuen) Agent
Der KV-Layer bleibt entkoppelt und verlangt vom `AgentOrchestrator` nur:
1. **stabile `conversationId`** pro Session (→ LCP nur innerhalb derselben Konversation; Cross-Leak-Reset),
2. **stabilen System-Präfix** (persona+instr+Manifest ändern sich nicht mitten in der Session).
Beide erfüllt der Neubau ohnehin.

### D.4 Kritische Randbedingungen (unverändert)
- **Front-Truncation (`:782`) inkompatibel** → in Phase 1 bei Overflow Cache invalidieren + Full-Prefill.
  Phase 3: System-Präfix pinnen, mittlere Turns evicten + Positions-Shift (StreamingLLM). Alternativ
  löst **Hebel D (Kompaktierung)** den Overflow ohnehin früher.
- **Positions-API** von `llama_batch_get_one` gegen den gepinnten llama.cpp-Stand verifizieren
  (Golden-Test „Reuse == Full-Prefill", identisches erstes Logit).
- **Adreno/OpenCL zuerst nur CPU-Pfad** freischalten; `seq_rm`/`state_seq_*` auf echtem Gerät prüfen.
- **`type_v` (CPU Q8_0 vs GPU F16)** gehört in den Cache-Key — sonst inkompatibler State.

---

# Teil E — Umsetzungsreihenfolge, Risiken, Tests

### E.1 Reihenfolge (empfohlen)
| # | Schritt | Ergebnis | Aufwand |
|---|---|---|---|
| **0** | **Test-Harness** aufsetzen (JUnit/Kotlin-Test, MockEngine, MockMcp) — fehlt komplett | Grundlage für alles Weitere | 0.5 d |
| **1** | **JNI natives Tool-Calling**: `nativeGenerateAgent` (`inputs.tools` + `common_chat_parse`) | Strukturierte tool_calls statt Regex | 2–3 d |
| **2** | **Message-Modell** um tool-Rollen erweitern (assistant.tool_calls, tool.tool_call_id) | Korrektes Multi-Round-Grounding | 1 d |
| **3** | **`AgentOrchestrator`** (streamend, strukturiert) ersetzt `AgentLoop`; MCP-Schicht wiederverwenden | Funktionierender Agent-MVP | 2 d |
| **4** | **Kontext/Manifest** (Hebel B/C): Agent-Preset ≥4096, kompakte Schemata | Kein Overflow, stabiles Tool-Calling | 1 d |
| **5** | **KV Hebel F Phase 1** (In-Session Reuse, CPU-Pfad, Golden-Test) | ~50–70 % weniger Prefill im Loop | 1.5–2 d |
| **6** | **KV Hebel F Phase 2** (persistenter Präfix-Cache) | Erster Turn ohne System-Prefill | 1.5–2 d |
| **7** | Gerätetest Adreno; GPU-Pfad für Reuse/Quant nach Verifikation | — | 1 d |
| **8** | (optional) Hebel D Kompaktierung, Phase 3 Radix, Agent-UI-Tab | Langhorizont-Agent | später |

**Erster PR-Schnitt:** Schritte 0–3 (funktionierender, sauberer Agent) — *vor* KV. Denn: solange das
Fundament regex-basiert und rollen-falsch ist, bringt KV-Optimierung wenig. **Fundament zuerst, dann
Performance.** KV (5–6) als separater PR danach.

### E.2 Risiken
- **Tool-fähiges Jinja-Template** muss im geladenen GGUF vorhanden sein (Qwen2.5: ja). Prüfen; sonst
  `--chat-template`-Äquivalent im JNI setzen.
- **3B-Tool-Call-Robustheit** ist real begrenzt (`10_research §4`): defensives Parsing + ein Retry/
  Reprompt-Pfad einplanen; **starke KV-Quant meiden** (Keys nie unter q8_0 — deckt sich mit Hebel A).
- **Ein-Engine-Serialisierung**: Agent + Server dürfen nie gleichzeitig dekodieren → `generateMutex`
  strikt. Mit residentem KV (Hebel F) wird Identity-Reset zwischen Konversationen zur Pflicht.
- **Adreno**: FA/Quant/`seq_rm` unverifiziert → CPU-Pfad zuerst, Gerätetest als Gate.

### E.3 Tests (Minimum)
- **Nativer Tool-Call**: gegen ein geladenes Qwen2.5-3B GGUF echte `tavily_search`-Runde, strukturierte
  tool_calls, gegroundete Endantwort mit Quelle — vollständig on-device (Akzeptanzkriterium #43).
- **MCP-Client**: MockEngine-basierte Unit-Tests für initialize/tools_list/tools_call, JSON- **und**
  SSE-Antwortpfad (der SSE-Parser `parseSseForResponse` ist schon da → testen).
- **KV Golden-Test**: Reuse-Pfad == clear+Full-Prefill (identisches erstes Logit / greedy-Sequenz).
- **Server unverändert**: `/v1/chat/completions` weiter grün (Regressionsschutz).

---

## Anhang — Verhältnis zur Hermes-Analyse
Der Neubau bringt AIpaca strukturell näher an Hermes: **native Tool-Calls** (Hermes' Tool-Registry →
Modell-Format), **echte Tool-Rollen** (Hermes' persist-before-execute-Disziplin), **Kompaktierung**
(Hermes' Kontext-Engine) und **Warm-KV** (Hermes' Prefix-Cache, on-device sogar stärker weil der
KV-Cache dir gehört). Der `AgentOrchestrator` ist das On-Device-Pendant zu Hermes' Turn-Schleife;
Hebel F ist der Prefix-Cache-Schlussstein.

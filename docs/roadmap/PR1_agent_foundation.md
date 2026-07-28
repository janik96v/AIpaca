# PR1 — Agent-Foundation (natives Tool-Calling)

Ziel: sauberer Agent-Loop, der Tool-Calling **nativ** über llama.cpps Jinja-Template + PEG-Parser macht,
mit echten Tool-Rollen und Streaming. Die MCP-Schicht (`HttpMcpClient`, `McpModels`, `ToolRegistry`,
`AgentPrefs`, `TavilyMcp`, `AgentSession`) wird **wiederverwendet**, nicht neu gebaut.

Prinzip: Bestehende Chat-/Server-Pfade bleiben unangetastet. Alles Neue ist additiv und hinter dem
Agent-Pfad gated, bis es verifiziert ist.

---

## Increment 0 — Test-Harness (build-berührend)
- `src/test` (JVM-Unit-Tests) einrichten: JUnit5 + kotlinx-serialization-Test.
- `MockEngine`-basierte MCP-Tests (der `HttpMcpClient` nimmt bereits einen injizierbaren `HttpClient`).
- **Berührt `build.gradle.kts`/Version-Catalog** → separater kleiner Commit, lokal bauen.

## Increment 1 — Kotlin-Foundation ✅ (dieser PR-Anfang, additiv, build-sicher)
Neue Dateien, keine Änderung bestehender Dateien:
- `engine/AgentModels.kt` — `AgentToolCall(id,name,argumentsJson)`, `AgentChunk`, `AgentResult(content,toolCalls)`.
- `agent/AgentMessage.kt` — Message-Modell mit **tool-Rollen** (System/User/Assistant(+toolCalls)/Tool)
  + `toMessagesJson()` → exakt das JSON, das die JNI-Funktion konsumiert.
- `agent/ToolManifestJson.kt` — `List<ToolSpec>.toToolsJson()` (kompaktes Schema, Hebel C).

Diese Dateien definieren den **Vertrag** zwischen Kotlin und JNI; alles Weitere baut darauf.

## Increment 2 — JNI natives Tool-Calling + Engine-Wiring (build-berührend, Kern)
**Neue JNI-Funktion** (additiv; `run_generate` wird um einen optionalen `tools`-Parameter erweitert,
statt eine zweite Kopie zu pflegen). Signatur:

```cpp
JNIEXPORT jstring nativeGenerateAgent(
    jlong ctxPtr,
    jstring messagesJson,   // [{role, content, tool_calls?, tool_call_id?, name?}]
    jstring toolsJson,      // [{name, description, parameters:{...JSON-Schema...}}]
    jfloat temperature, jfloat topP, jfloat repeatPenalty,
    jint maxTokens, jint thinkingBudget,
    jobject callback);      // streamt content/thinking wie bisher (TokenCallback.onToken)
// return: JSON-String {"content":"...","tool_calls":[{"id","name","arguments":{...}}]}
```

C++-Kern (API oben verifiziert):
1. `messagesJson` mit nlohmann-JSON (über `chat.h` verfügbar) → `std::vector<common_chat_msg>`
   (inkl. `tool_calls`, `tool_name`, `tool_call_id`).
2. `toolsJson` → `std::vector<common_chat_tool>{name, description, parameters=<schema-string>}`.
3. `common_chat_templates_inputs inputs; inputs.use_jinja=true; inputs.messages=…; inputs.tools=…;`
   `inputs.enable_thinking = thinking_budget!=0;` `inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;`
4. `auto cp = common_chat_templates_apply(tmpls.get(), inputs);` → `cp.prompt` tokenisieren, prefillen,
   samplen (Generierungs-Loop aus `run_generate` wiederverwenden — streamt via `onToken`).
5. Nach der Generierung **nativ parsen**: `common_chat_msg out = common_chat_parse(full_text, false,
   common_chat_parser_params(cp));` → `out.content` + `out.tool_calls[i]{name,arguments,id}`.
6. Ergebnis als JSON-String zurück (content + tool_calls).

Engine-Wiring (bestehende Dateien, chirurgisch):
- `engine/InferenceEngine.kt`: neue Methode `fun generateAgent(messages, tools, params): Flow<AgentChunk>`
  **plus** eine `suspend fun lastAgentResult(): AgentResult` — oder sauberer: `generateAgent` liefert
  einen `Flow<AgentEvent>` (Chunk|Result). Entscheidung im Code-Review; MVP: Flow streamt Chunks, der
  finale `AgentResult` kommt über einen `onResult`-Callback / Deferred.
- `engine/LlamaCppEngine.kt`: `external fun nativeGenerateAgent(...)` (kompiliert ohne Native-Symbol;
  Symbol wird erst zur Laufzeit gebraucht → bestehender Build/Run bleibt heil) + Impl in `callbackFlow`.
- **Nicht** angefasst: `nativeGenerateChat` (Chat/Server-Pfad) bleibt bitgenau.

## Increment 3 — AgentOrchestrator (ersetzt AgentLoop)
- `agent/AgentOrchestrator.kt`: think→tool→observe-Loop mit **strukturierten** `AgentToolCall`s,
  echten Tool-Rollen (Assistant(toolCalls) + Tool(result)), streamenden `AgentStep`s.
- Reuse: `ToolRegistry` (Tool-Exekution über `HttpMcpClient`), `AgentSession` (Persistenz).
- Serialisierung strikt über `EngineState.generateMutex`.
- `ToolCallParser` bleibt nur als optionales Fallback-Netz (nativer Pfad ist primär).

## Increment 4 — Kontext & Manifest (Hebel B/C)
- Agent-Preset lädt Modell mit `contextSize ≥ 4096` (statt 1024).
- Tool-Manifest kompakt halten (`toToolsJson` bereits kompakt; ggf. Description kürzen).
- Default-Modell Qwen2.5-3B (Hermes-2-Pro-Handler).

## Increment 5 — Aufräumen
- Alten `AgentLoop` entfernen (durch `AgentOrchestrator` ersetzt), `AgentConfig.renderSystemPrompt`
  von der `<tool_call>`-Instruktion befreien (Tools kommen jetzt über `inputs.tools`).
- UI (`agent`-Toggle, bereits in Commit 474caa4) auf den Orchestrator umstellen.

---

## Akzeptanzkriterien (MVP)
- [ ] Gegen ein geladenes Qwen2.5-3B GGUF: echte `tavily_search`-Runde, **strukturierte** tool_calls
      (nativ, kein Regex), gegroundete Endantwort mit Quelle — vollständig on-device.
- [ ] Bestehender OpenAI-Server + Chat-UI **unverändert** grün.
- [ ] MCP-Client Unit-Tests (JSON- + SSE-Pfad) grün.
- [ ] Kein Absturz bei fehlerhaftem/halluziniertem Tool-Call (defensiver Pfad + ein Reprompt).

## Risiken
- Tool-fähiges Jinja-Template muss im GGUF vorhanden sein (Qwen2.5: ja) — sonst `--chat-template`-Äquiv.
- 3B-Tool-Call-Robustheit begrenzt → defensives Parsing + Retry; starke KV-Quant meiden (Keys ≥ q8_0).
- JNI berührt den C++/Submodul-Pfad → NDK-Build nötig; `common_chat_parse`-Nutzung gegen den gepinnten
  Submodul-Stand verifizieren (API oben aus `common/chat.h` bestätigt).

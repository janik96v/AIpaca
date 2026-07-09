# Implementierungs-Spec — Issue #42: Web Search via MCP (Tavily)

Issue: #42 "Web Search capability through MCP servers" · Label: enhancement · State: open
Rolle im Gesamtbild: **Brick 1 von #43** — erster echter End-to-End-Tool-Call in AIpaca.
Grundlage: siehe `00_repo_analysis.md`.

> Hinweis: Diese Spec ist bewusst als "kleinstes sinnvolles Inkrement" geschnitten, das für sich
> lauffähig ist UND direkt die Fundamente für #43 legt (MCP-Client-Abstraktion + ein Tool).

---

## 1. Ziel

Ein lokales, on-device geladenes Modell soll das Web durchsuchen können, indem AIpaca einen
**MCP-Client** bekommt, der über HTTP an den **Tavily-MCP-Server** spricht und dessen
`tavily_search`-Tool aufruft (Tool-Namen zur Laufzeit über `tools/list` ermitteln — der
Tavily-Server liefert `tavily_search`, `tavily_extract`, `tavily_crawl`, `tavily_map`).
Ergebnis: das Modell beantwortet eine Recherchefrage **mit Quellenangaben**, gestützt auf reale
Websuche — ohne Cloud-LLM. Remote-Endpoint: `https://mcp.tavily.com/mcp/?tavilyApiKey=<key>`.

## 2. Scope / Non-Scope

**In Scope**
- Minimaler Kotlin **MCP-Client** über **Streamable HTTP** (Fallback: HTTP+SSE) — remote MCP-Server.
- Anbindung genau **eines** MCP-Servers (Tavily) und genau **eines** Tools (Websuche).
- Tool-Manifest an das Modell durchreichen und Tool-Calls des Modells parsen/ausführen/rückspeisen.
- Konfiguration: Tavily-API-Key + Server-URL, sicher gespeichert (EncryptedSharedPreferences).

**Out of Scope** (→ #43)
- Verallgemeinerte Tool-Loop für beliebige MCP-Tools (Brick 2), Sessions/Memory (Brick 3), UI-Agent-Tab.
- Änderungen am öffentlichen `POST /v1/chat/completions`-Format (bleibt OpenAI-kompatibel/unangetastet).
- stdio-Transport / gebündelte Binaries, Headless-Browser.

## 3. Betroffene / neue Module

Neu (`com.aipaca.app.agent.mcp`):
```
agent/mcp/McpClient.kt          Interface: connect(), listTools(), callTool(name, argsJson): ToolResult
agent/mcp/HttpMcpClient.kt      Streamable-HTTP/SSE-Impl (JSON-RPC 2.0)
agent/mcp/McpModels.kt          @Serializable: ToolSpec, ToolResult, JsonRpcRequest/Response
agent/tool/ToolRegistry.kt      hält verbundene MCP-Clients + aggregiertes Tool-Manifest
agent/tool/TavilyMcp.kt         Config-Wrapper (URL, API-Key) für den Tavily-Server
data/AgentPrefs.kt              verschlüsselte Ablage von Tavily-Key + MCP-URL
```
Berührt:
```
EngineState.kt                  optionaler Zugriffspunkt auf ToolRegistry (nur lesend)
gradle/libs.versions.toml       + Ktor-Client (CIO/OkHttp) ODER offizielles kotlin-sdk (siehe §5)
app/build.gradle.kts            + Dependencies, + Test-Setup (JUnit/kotlin-test)
```
**Kein** Eingriff in `server/` in diesem Brick.

## 4. Der minimale Agent-Aufruf (Brick-1-Loop)

```
Frage ─▶ generateChat(turns + toolManifest)  (Modell entscheidet)
             │  emittiert Tool-Call-Token (nativ, via llama.cpp chat-template)
             ▼
        parse tool call ─▶ McpClient.callTool("tavily-search", args)
             │                         │ HTTP → Tavily MCP
             ▼                         ▼
        observe (Ergebnis + Quellen zurück in den Context)
             │
             └─▶ finaler generateChat-Durchlauf ─▶ Antwort mit Quellen
```
Max. 1 Tool-Runde reicht für die Akzeptanz; die generische n-Runden-Loop ist #43/Brick 2.

## 5. Technische Entscheidungen & Randbedingungen

1. **MCP-Client-Implementierung** — FINALE EMPFEHLUNG (belegt in `10_research_mcp_kotlin.md`):
   **Schlanker, selbst geschriebener JSON-RPC-2.0-Client über OkHttp oder den vorhandenen
   Ktor-2.3.12-Client** — NICHT das offizielle Kotlin MCP SDK.
   - Begründung: Das offizielle SDK (`io.modelcontextprotocol:kotlin-sdk` 0.13.0, pre-1.0)
     baut gegen **Ktor 3.5.1 / Kotlin 2.4.0** und ist nicht binärkompatibel zu Ktor 2.x.
     Es einzuziehen würde die App (Ktor 2.3.12-Server, Kotlin 2.0.21) zu einem teuren
     Full-Stack-Upgrade auf Ktor 3.x zwingen — inkl. Migration des bestehenden Servers.
   - Der benötigte MCP-Umfang ist klein/statisch: `initialize` → `notifications/initialized`
     → `tools/list` → `tools/call`. Das ist JSON-RPC 2.0 über einen HTTP-POST-Endpoint,
     mit kotlinx.serialization 1.7.3 (bereits vorhanden) trivial baubar. Kein Stack-Upgrade.
   - Das offizielle SDK nur wählen, falls ein Ktor-3-Upgrade ohnehin ansteht.
2. **Transport**: **Streamable HTTP**, Basis-Protokollversion **`2025-06-18`** (Header
   `Mcp-Protocol-Version`), Session über Header `Mcp-Session-Id`. Client sendet
   `Accept: application/json, text/event-stream` und behandelt **beide** Antworttypen; für
   reine Tool-Calls reicht meist der `application/json`-Pfad (SSE nur bei Server-Streaming).
   Streamable HTTP ersetzt offiziell den alten HTTP+SSE-Transport (Spec 2024-11-05).
3. **Tool-Call-Format**: **nativ** über den bereits vorhandenen JNI-Pfad
   (`common_chat_templates_apply`, `use_jinja=true`) — das `tools`-Feld von
   `common_chat_templates_inputs` (siehe `common/chat.h`) befüllen, statt Prompt-Formate von
   Hand zu bauen. Neuere llama.cpp parst Tool-Calls per PEG-Parser (robuster als Regex).
   Fallback: Kotlin-seitiges Tool-Prompting + JSON-Parsing. **Präferenz: JNI-Pfad.**
   ⚠️ Starke KV-Quantisierung (z.B. `-ctk q4_0`) verschlechtert Tool-Calling — vermeiden.
4. **Modell** (verifiziert, Handler-Zuordnung entscheidet über Qualität):
   - **Qwen 2.5 3B Instruct** → Handler "Hermes 2 Pro" (`<tool_call>…</tool_call>`).
   - **Mistral-7B-Instruct-v0.3** → Handler "Mistral Nemo" (`[TOOL_CALLS]`). **Nur v0.3!**
     v0.1/v0.2 fallen auf den generischen Handler zurück (schlechteres Tool-Calling).
5. **Sicherheit/Consent**: Tavily-API-Key nur in `EncryptedSharedPreferences`; Netzzugriff durch
   den Agenten nur nach expliziter User-Aktivierung (Setting-Toggle). Kein Key im Log/Handoff.
6. **Nebenläufigkeit**: MCP-Calls auf `Dispatchers.IO`; Generierung bleibt unter `generateMutex`.
7. **Ktor-Version**: Client-Artefakt kompatibel zu Ktor 2.3.12 wählen (nicht auf 3.x zwingen,
   solange der Server auf 2.3.12 bleibt).

## 6. Akzeptanzkriterien (aus Issue abgeleitet + präzisiert)

- [ ] MCP-Client verbindet zum Tavily-MCP-Server und listet dessen Tools (`listTools()` liefert
      `tavily-search`).
- [ ] Bei einer Recherchefrage fordert das Modell den Tool-Call an; AIpaca führt `tavily-search`
      aus; das Ergebnis wird in den Context zurückgespeist.
- [ ] Das Modell liefert eine finale, **auf dem Tool-Ergebnis gegroundete Antwort mit Quellen**.
- [ ] Läuft vollständig **on-device** gegen das geladene GGUF — kein Cloud-LLM-Call.
- [ ] Bestehender OpenAI-Server + Chat-UI funktionieren **unverändert** weiter.
- [ ] API-Key wird verschlüsselt gespeichert und erscheint in keinem Log/Handoff.

## 7. Test- & Verifikationsplan (Greenfield — Harness mit einrichten)

- Unit: `HttpMcpClient` gegen einen **MockEngine/MockWebServer** (JSON-RPC-Roundtrip, SSE-Framing,
  Fehlerfälle: Timeout, 401 falscher Key, leeres Tool-Ergebnis).
- Unit: Tool-Call-Parser (falls Fallback-Pfad) mit echten Qwen/Mistral-Tool-Call-Samples.
- Instrumented/manuell: reale Recherchefrage mit gültigem Tavily-Key → Antwort enthält Quellen-URLs.
- Regression: `curl` gegen `/v1/chat/completions` zeigt unveränderte Response-Form.

## 8. Risiken / offene Punkte (nach Recherche aktualisiert)

- **Client-Wahl geklärt:** hand-geschriebener JSON-RPC-Client statt SDK (vermeidet Ktor-2→3-Zwang).
- Zuverlässigkeit des nativen Tool-Call-Parsings kleiner Modelle → ggf. few-shot + strenges JSON +
  Retry/Reprompt; starke KV-Quantisierung meiden.
- Tavily-Endpoint geklärt: `https://mcp.tavily.com/mcp/?tavilyApiKey=<key>` (Streamable HTTP);
  Auth alternativ `Authorization: Bearer` oder OAuth. Tool-Namen dynamisch via `tools/list`.
- Kontextfenster: Default `contextSize=1024` ist knapp für Tool-Ergebnisse → für Agent-Sessions
  höheren `contextSize` beim Modell-Load empfehlen.
- Streamable HTTP verlangt Behandlung **beider** Antworttypen (`application/json` UND
  `text/event-stream`) sowie Session-Header `Mcp-Session-Id` — beim eigenen Client einplanen.

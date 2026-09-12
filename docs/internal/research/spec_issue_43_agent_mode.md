# Implementierungs-Spec — Issue #43: On-Device Agent Mode ("mini-OpenClaw")

> **Status: SUPERSEDED.** This spec plans a user-facing agent-mode toggle (`ui/agent/AgentScreen.kt`
> or a chat-input toggle, see §"Agent-Modus/Tab bzw. Chat-Toggle" below). A chat-toggle version of
> this was built in `433c3c2` and then deliberately removed in favour of capability-based execution
> tiers — see the header comment in `AgentTier.kt` for why. No `ui/agent/` directory exists in the
> current codebase. This is the last doc that still describes a user-facing agent toggle as the plan
> of record; treat it as historical. Current behavior: `docs/capabilities.md` §5 (Agent Mode).

Issue: #43 "[Feature] On-device agent mode — a native 'mini-OpenClaw' (agent loop + MCP client)"
Label: enhancement · State: open
Referenz-Architektur zum Spiegeln: OpenClaw (https://github.com/openclaw/openclaw · https://docs.openclaw.ai)
Grundlage: siehe `00_repo_analysis.md`. Baut auf #42 (= Brick 1) auf.

---

## 1. Ziel

Eine native, in-process **Agent-Loop in Kotlin**, die:
- die vorhandene `LlamaCppEngine` **direkt** (JNI, kein localhost-HTTP-Hop) wiederverwendet,
- externe Tools über einen Kotlin-**MCP-Client** aufruft,
- **Memory über Turns hinweg** hält,
- und die bestehende OpenAI-Server-Rolle **vollständig intakt** lässt.

Beide Modi ("Chatbot" und "Agent") teilen dasselbe Modell — der Agent ergänzt nur Loop + Tool-Zugriff.

## 2. Die Agent-Loop

```
goal ─▶ 1. think (LLM entscheidet nächsten Schritt)
           │
           ▼
        2. tool call ─▶ 3. execute (MCP-Client führt Tool aus)
                              │
                              ▼
        ◀── 4. observe (Ergebnis zurück in den Context)
           │
           └── wiederholen bis Ziel erreicht ─▶ finale gegroundete Antwort
```

## 3. Design — nah an OpenClaw, nativ wo es zählt (Mapping aus Issue)

| OpenClaw-Konzept | AIpaca (nativ) | Notiz |
|---|---|---|
| **Agent**-Orchestrator | `AgentLoop` (Kotlin) | think → tool → observe → repeat |
| Model-Provider (`provider/model-id`) | vorhandene `LlamaCppEngine` via JNI | in-process, on-device; kein HTTP-Hop |
| **Skills / Tools** | MCP-Tools via Kotlin-MCP-Client | spiegelt OpenClaws MCP-Integration |
| MCP-Registry | kleine kuratierte In-App-Serverliste | Tavily-Websuche zuerst (#42) |
| Prompt-Files (AGENTS.md/SOUL.md/TOOLS.md) | system-prompt / persona / tool-manifest Config | gleiche Aufteilung für Vertrautheit |
| **Sessions** (list/history) | Aufbau auf `ChatConversationStore` | Memory & Context über Turns |
| **Gateway** (Multi-Channel) | *out of scope* | OpenClaw bleibt "großer Bruder" auf Pi/VPS |
| **Nodes** (Companion-Geräte) | *out of scope* | AIpaca ist hier das Ganze |

Modell-Tool-Calling: Qwen 2.5 3B / Mistral-7B-Instruct exponieren native tool-call tokens (#34);
die Loop parst diese und speist Ergebnisse zurück in den Context.

## 4. Scope — inkrementelle Bricks

- **Brick 1 — MCP-Client + erstes Tool (Websuche).** = **Issue #42.** Zuerst umsetzen; liefert den
  ersten echten End-to-End-Tool-Call. → siehe `spec_issue_42_web_search_mcp.md`.
- **Brick 2 — Tool-Calling-Loop verallgemeinern** für beliebige MCP-Tools; robustes
  Tool-Call-Parsing für Qwen/Mistral (#34).
- **Brick 3 — Memory & Sessions** auf `ChatConversationStore` (OpenClaw-Sessions spiegeln).

## 5. Betroffene / neue Module

Neu (aufbauend auf #42, `com.aipaca.app.agent`):
```
agent/AgentLoop.kt              ★ think→tool→observe→repeat; nutzt EngineState.engine.generateChat
agent/AgentConfig.kt            systemPrompt / persona / tool-manifest (AGENTS/SOUL/TOOLS-Split)
agent/ToolCallParser.kt         parst native tool-call tokens (Qwen/Mistral) → strukturierter Call
agent/AgentSession.kt           Session-Modell, baut auf StoredConversation/ChatConversationStore
agent/mcp/*                     aus #42 (McpClient, HttpMcpClient) — generalisiert in Brick 2
ui/agent/AgentScreen.kt         (Brick 3) Agent-Modus/Tab bzw. Chat-Toggle
```
Berührt: `EngineState.kt` (Agent-Zugriff auf Engine + Registry), `data/ChatConversationStore.kt`
(Sessions), ggf. `app/src/main/cpp/llama_jni.cpp` (`inputs.tools` befüllen für native Tool-Calls).
**Nicht** berührt: `server/ApiServer.kt` öffentliches Format (Server-Rolle bleibt unverändert).

## 6. Technische Randbedingungen & Entscheidungen

1. **Engine-Reuse in-process**: Agent-Loop ruft `EngineState.engine.generateChat(turns, params)` —
   **kein** Umweg über den lokalen HTTP-Server (Non-Goal: localhost-Hop). Genau eine Engine-Instanz.
2. **Nebenläufigkeit**: Generierung im Server ist bereits per `generateMutex` serialisiert. Der
   Agent-Loop teilt sich die **eine Engine** → gemeinsamer Serialisierungsmechanismus nötig
   (Agent und Server dürfen nicht gleichzeitig generieren). Auf `EngineState.scope` laufen lassen.
3. **Native Tool-Calls bevorzugt**: `inputs.tools` im JNI-Template-Pfad
   (`common_chat_templates_apply`, `use_jinja=true`, bereits vorhanden) befüllen; Fallback:
   Kotlin-seitiges Tool-Prompting + `ToolCallParser`.
4. **MCP-Transport**: HTTP/SSE bzw. Streamable HTTP zuerst (remote Server) — kein stdio/Binaries.
5. **Sessions/Memory**: `ChatConversationStore` (EncryptedSharedPreferences, AES256-GCM) erweitern
   um Tool-Turns; Kontextfenster erhöhen (`contextSize` beim Load; Default 1024 zu klein).
6. **Modell-Default**: Qwen 2.5 3B vs. Mistral-7B-Instruct-v0.3 — Entscheidung per Tool-Call-
   Zuverlässigkeit (#34), siehe Recherche.
7. **Consent/Permissions**: Agent löst Netz-Tools im Namen des Users aus → explizites Opt-in +
   sichtbare Tool-Aktivität; API-Keys verschlüsselt, nie im Log.

## 7. Non-Goals (aus Issue)

- ❌ Kein eingebettetes Node.js / kein OpenClaw-Code-Port.
- ❌ Keine Headless-Browser-Automation (Chromium auf Android/Termux unzuverlässig).
- ❌ Keine Messaging-Gateways (Telegram/WhatsApp/…) — OpenClaw bleibt der volle "große Bruder".

## 8. Offene Fragen (aus Issue) — nach Recherche beantwortet, siehe `10_research_mcp_kotlin.md`

- MCP-Transport: nur remote HTTP/SSE, oder auch stdio? → **Streamable HTTP** (Basis 2025-06-18),
  ersetzt HTTP+SSE offiziell. Kein stdio/Binaries.
- MCP-Client-Bibliothek: → **schlanker eigener JSON-RPC-2.0-Client** (OkHttp/Ktor-2), NICHT das
  offizielle Kotlin-SDK — dieses zwingt Ktor 3.x/Kotlin 2.4 (Konflikt mit Ktor-2.3.12-Server).
- Default-Agent-Modell: → **Qwen 2.5 3B** (Handler "Hermes 2 Pro") oder
  **Mistral-7B-Instruct-v0.3** (Handler "Mistral Nemo", nur v0.3!). Tool-fähiges Jinja-Template
  sicherstellen; starke KV-Quantisierung meiden.
- Tool-Execution & Permissions: explizites Opt-in + sichtbare Tool-Aktivität; Keys verschlüsselt.
- UI: eigener "Agent"-Modus/Tab oder in Chat mit Toggle? → **noch offen** (Produktentscheidung, Brick 3).

## 9. Akzeptanzkriterien (MVP = Brick 1, aus Issue)

- [ ] Kotlin-MCP-Client verbindet zu ≥1 MCP-Server (z.B. Tavily) und listet dessen Tools.
- [ ] Agent-Loop funktioniert: Modell fordert Tool → App führt aus → Ergebnis zurück → Modell
      liefert finale, auf dem Tool-Ergebnis gegroundete Antwort (mit Quellen).
- [ ] Läuft vollständig **on-device** gegen das geladene GGUF — kein Cloud-LLM-Call.
- [ ] Bestehender OpenAI-Server + Chat-UI funktionieren **unverändert**.

## 10. Vorhandene Bausteine zum Aufsetzen (aus Issue verifiziert)

`LlamaCppEngine` (JNI), `EngineState`, `ChatConversationStore`, `ApiServer` — alle vorhanden und
in `00_repo_analysis.md` mit Signaturen dokumentiert.

## 11. Empfohlene Umsetzungsreihenfolge

1. **#42 / Brick 1** komplett (MCP-Client + Tavily + eine Tool-Runde) — eigenständige Spec.
2. **Brick 2**: `AgentLoop` + `ToolCallParser` generalisieren (n Runden, beliebige MCP-Tools).
3. **Brick 3**: `AgentSession` + UI-Agent-Modus auf `ChatConversationStore`.

## 12. Risiken

- Zwei Generierungs-Konsumenten (Server + Agent) an einer Engine → strikte Serialisierung nötig,
  sonst native Crashes/State-Korruption.
- Tool-Call-Robustheit kleiner Modelle; Kontext-Overflow bei mehreren Tool-Runden.
- JNI-Erweiterung (`inputs.tools`) berührt den C++/Submodul-Pfad — Build/Submodul-Init beachten.

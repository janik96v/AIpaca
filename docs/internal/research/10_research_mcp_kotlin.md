# State-of-the-Art: Nativer MCP-Client in Android/Kotlin mit Tavily-Websuche

**Stand der Recherche:** Juli 2026 (Quellen live abgerufen). Alle Aussagen sind mit
URLs belegt; Unsicherheiten sind explizit gekennzeichnet.

**Ziel-Stack der App (gegeben):** Kotlin 2.0.21, minSdk 28, targetSdk 35, Ktor **2.3.12
Server** (kein Ktor-Client), kotlinx.serialization 1.7.3, kotlinx.coroutines 1.9.0.
Gewünscht: nativer MCP-Client über HTTP/SSE bzw. Streamable HTTP gegen remote MCP-Server,
erstes Tool = Tavily Web Search.

---

## 1. Offizielles Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`)

**Gesichert:**

- Aktuelles Release: **0.13.0** (02.06.2026). Tag **0.14.0** existiert bereits im Repo
  (noch kein GitHub-Release-Eintrag zum Abrufzeitpunkt → wahrscheinlich in Vorbereitung).
  Version ist also weiterhin **< 1.0 / pre-stable**; API-Brüche zwischen Minor-Versionen
  sind zu erwarten.
- Maven-Koordinaten (Maven Central):
  - `io.modelcontextprotocol:kotlin-sdk` (Umbrella: Client + Server)
  - `io.modelcontextprotocol:kotlin-sdk-client` (nur Client)
  - `io.modelcontextprotocol:kotlin-sdk-server` (nur Server)
- Es ist ein **Kotlin Multiplatform SDK**: Targets **JVM, Native, JS, Wasm**.
  **Es gibt KEIN dediziertes `androidTarget()`** im SDK-Build. Android nutzt das
  **JVM-Artefakt** (KMP-JVM). Das funktioniert in der Praxis, ist aber kein offiziell
  getesteter Android-Target.
- **Transports (alle über dieselbe API):** STDIO, **Streamable HTTP**
  (`StreamableHttpClientTransport`), SSE (Legacy, „for backwards compatibility"),
  WebSocket, ChannelTransport (in-process/Test).
- **Ktor-Abhängigkeit:** Das SDK nutzt Ktor, zieht aber **keine Ktor-Engine transitiv**.
  Man muss selbst eine Ktor-**Client**-Engine deklarieren (z. B. `ktor-client-cio`,
  `ktor-client-okhttp`, `ktor-client-apache5`). Der Client-Quickstart installiert
  zusätzlich das SSE-Plugin: `HttpClient { install(SSE) }`.

**Android-Fallstricke (wichtig, teils Einschätzung):**

- **Versions-Mismatch ist das zentrale Problem.** SDK 0.13.0 baut gegen
  **Kotlin 2.4.0, Ktor 3.5.1, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.11.0**
  (aus `gradle/libs.versions.toml`). Die App liegt auf Kotlin 2.0.21, Ktor 2.3.12,
  coroutines 1.9.0, serialization 1.7.3.
  → **Ktor 2.x und Ktor 3.x sind nicht binärkompatibel.** Ein nativer Client mit dem
  offiziellen SDK zwingt praktisch zum **Upgrade des gesamten App-Stacks auf Ktor 3.x**
  und neuere Kotlin/coroutines/serialization-Versionen. Das ist der grösste
  Integrationskostenblock, insbesondere weil bereits ein **Ktor 2.3.12 Server** in der
  App läuft (der müsste mitgezogen werden).
- **JVM-Target/Desugaring:** SDK-README nennt **JVM 11+** (Badge). Für Android bedeutet
  das i. d. R. `compileOptions { sourceCompatibility/targetCompatibility = 11 }` bzw.
  `jvmTarget = "11"` und ggf. Core-Library-Desugaring, je nach genutzten `java.time`/NIO-
  APIs. minSdk 28 ist grundsätzlich unkritisch, aber die kotlinx-io-Abhängigkeit
  (`kotlinx-io 0.9.1`) und Coroutines-Version sollten geprüft werden.
- **Engine-Wahl Netty vs. OkHttp/CIO:** Netty ist eine **Server-Engine** und für einen
  Android-**Client** irrelevant/ungeeignet. Für den Client auf Android sind
  **`ktor-client-okhttp`** (nutzt OkHttp, auf Android bewährt) oder **`ktor-client-cio`**
  (rein Kotlin/Coroutines) die sinnvollen Optionen. OkHttp ist auf Android meist die
  robustere Wahl (TLS, Proxy, HTTP/2). SSE/Streamable-HTTP-Streaming funktioniert mit
  CIO und OkHttp; für langlebige SSE-Streams ist die Timeout-Konfiguration der Engine zu
  beachten.
- **Android-Support offiziell:** Es gibt ein **geschlossenes Issue #234 „A MCP SDK for
  Android Apps"**, das lediglich auf ein Drittprojekt (`AnswerZhao/android-mcp-sdk`)
  verweist und den Hinweis gibt, `ChannelTransport` für In-Process-Kommunikation zu
  nutzen. → **Kein dediziertes offizielles Android-Commitment**; Android ist „JVM-Target,
  läuft, aber nicht als Erstklasse-Plattform beworben".

**Bewertung:** Das SDK ist funktional vollständig und deckt Streamable HTTP + SSE ab,
aber der erzwungene Ktor-3.x-/Kotlin-2.4-Sprung ist für diese konkrete App teuer.

---

## 2. MCP-Transport-Landschaft 2025/2026

**Gesichert (aus der offiziellen Spec):**

- Spezifikations-Revisionen im Repo: `2024-11-05`, `2025-03-26`, `2025-06-18`,
  **`2025-11-25`** (neueste), plus `draft`.
- **„Streamable HTTP" ersetzt offiziell den alten „HTTP+SSE"-Transport** aus Protokoll-
  Version 2024-11-05. Zitat der Spec (2025-06-18, Abschnitt Transports):
  *„This replaces the HTTP+SSE transport from protocol version 2024-11-05."*
- Die Spec definiert **heute nur noch zwei Standard-Transports: stdio und Streamable
  HTTP.** SSE erscheint nur noch als **Backwards-Compatibility-Pfad**.
- **Streamable HTTP – Kernmechanik:**
  - **Ein einziger HTTP-Endpoint** (z. B. `https://example.com/mcp`), der **POST und GET**
    unterstützt.
  - Jede Client→Server-JSON-RPC-Nachricht ist ein **HTTP POST**.
  - Client **MUSS** `Accept: application/json, text/event-stream` senden.
  - Server antwortet auf eine Request entweder mit **`application/json`** (eine Antwort)
    **oder** mit **`text/event-stream`** (SSE-Stream) — der Client muss **beide** Fälle
    behandeln.
  - **Session-Management** über Header **`Mcp-Session-Id`**; Protokollversion über
    **`Mcp-Protocol-Version`**.
  - Sicherheits-Pflichten: Server müssen den **`Origin`-Header validieren** (DNS-Rebinding-
    Schutz); ab 2025-11-25 explizit **HTTP 403** bei ungültigem Origin.
- **Neuerungen 2025-11-25 (relevant für Clients):** OAuth-Verbesserungen (OIDC Discovery,
  inkrementelle Scopes via `WWW-Authenticate`, Client-ID-Metadata-Documents), Icons als
  Tool-Metadaten, `tools`/`toolChoice` bei Sampling, experimentelle „Tasks" (durable
  requests mit Polling), JSON Schema 2020-12 als Default-Dialekt, klarere SSE-Polling-/
  Resumption-Regeln.

**Empfehlung für eine Neu-Implementierung heute:**

- **Streamable HTTP wählen**, nicht den alten HTTP+SSE-Transport. Es ist der aktuelle
  Standard und die von Tavily & Co. gehostete Variante.
- Als Basis-Protokollversion **`2025-06-18`** annehmen und im Header
  `Mcp-Protocol-Version` mitschicken; `2025-11-25`-Features (OAuth-Flows, Tasks) nur bei
  Bedarf. **(Einschätzung, nicht normativ.)**
- Für den einfachen „ein Request → eine Antwort"-Fall (Tool-Call) reicht der
  **JSON-Antwortpfad** (`application/json`); der SSE-Pfad wird nur gebraucht, wenn der
  Server streamt oder Server→Client-Notifications schickt.

---

## 3. Tavily MCP Server

**Gesichert (aus offiziellem `tavily-ai/tavily-mcp` Repo, npm 0.2.20):**

- **Remote hosted (empfohlen, kein Node.js nötig):**
  - Endpoint-URL: **`https://mcp.tavily.com/mcp/?tavilyApiKey=<your-api-key>`**
  - Transport: **Streamable HTTP** (in der Doku als `--transport http` referenziert).
  - **Auth-Alternativen:**
    1. API-Key als Query-Param `?tavilyApiKey=...`
    2. **`Authorization: Bearer <token>`**-Header (falls Client das unterstützt)
    3. **OAuth-Flow** (Metadata-Discovery, Client-Registration, Token) — optional.
  - Optionale Header: `DEFAULT_PARAMETERS` (JSON mit Default-Suchparametern),
    `X-Human-Id` (via `TAVILY_HUMAN_ID`, für Analytics; server-seitig SHA-256-gehasht).
  - API-Key beziehen: `https://app.tavily.com/home`.
- **Lokal (npx, für diese Android-App NICHT relevant):**
  `npx -y tavily-mcp@latest` mit `TAVILY_API_KEY` als Env-Var; braucht Node.js ≥ 20.
- **Bereitgestellte Tools (4, aus `src/index.ts`):**
  - **`tavily_search`** — Web-Suche. Wichtige Parameter: `query` (Pflicht), `search_depth`
    (`basic`/`advanced`/`fast`/`ultra-fast`), `topic`, `time_range`, `start_date`/
    `end_date`, `max_results`, `include_images`, `include_image_descriptions`,
    `include_raw_content`, `include_domains`/`exclude_domains`, `country`,
    `include_favicon`, exakte Phrasen.
  - **`tavily_extract`** — Inhalt aus URLs extrahieren (markdown/text).
  - **`tavily_crawl`** — Website ab Start-URL crawlen (depth/breadth konfigurierbar).
  - **`tavily_map`** — strukturierte Website-Map erzeugen.

  ⚠️ **Namens-Hinweis:** Der aktuelle Quellcode nutzt **Unterstriche** (`tavily_search`,
  `tavily_extract`, `tavily_crawl`, `tavily_map`). Das README/Marketing spricht teils von
  „`tavily-search`" (Bindestrich). **Verlasse dich zur Laufzeit auf `tools/list`** statt
  auf hartkodierte Namen — die Server-Antwort ist die Wahrheit.

**Für die App:** Remote-Endpoint `https://mcp.tavily.com/mcp/` mit API-Key ist der direkte
Weg. `initialize` → `tools/list` → `tools/call` mit `name="tavily_search"` und
`arguments={"query": ...}`.

---

## 4. Tool-Calling mit kleinen lokalen Modellen über llama.cpp

**Gesichert (aus `ggml-org/llama.cpp` `docs/function-calling.md` + `common/chat.h`):**

- Function Calling wird in **`llama-server` mit dem `--jinja`-Flag** aktiviert
  (OpenAI-kompatibles `/v1/chat/completions` mit `tools`-Array). Implementiert über
  `common/chat.*` (ursprünglich PR #9639).
- **Format-Handler pro Modell (direkt relevant):**
  - **Qwen 2.5 3B Instruct → Handler „Hermes 2 Pro"** (Tool-Calls in
    `<tool_call>…</tool_call>`-JSON-Blöcken). Gilt laut Tabelle für die gesamte Qwen-2.5-
    Reihe inkl. 3B.
  - **Mistral-7B-Instruct-v0.3 → Handler „Mistral Nemo"** (nutzt `[TOOL_CALLS]`-Format).
    **Achtung Versionsabhängigkeit:** **v0.1 und v0.2 → nur „Generic"**, erst
    **v0.3 → „Mistral Nemo"** (natives Format). Die Modellversion entscheidet über die
    Tool-Call-Qualität.
- **`common_chat_templates_inputs` (in `common/chat.h`, ~Zeile 249):**
  - Feld **`std::vector<common_chat_tool> tools`** (Zeile 257) trägt die Tool-Definitionen.
  - **`bool use_jinja = true`** (Zeile 255); Tool-/Template-Parameter greifen nur bei
    `use_jinja = true`.
  - Beim Parsen: `common_chat_parser_params` mit **`bool parse_tool_calls = true`**.
  - Neuere llama.cpp nutzt einen **PEG-Parser** für Tool-Calls
    (`COMMON_CHAT_FORMAT_PEG_SIMPLE/NATIVE/GEMMA4`) statt reiner Regex/String-Suche →
    robusteres Parsing als früher.
- **Ablauf:** Bei `--jinja` rendert llama.cpp das Jinja-Chat-Template des Modells mit dem
  `tools`-Feld, das Modell generiert im modellnativen Format, und der Format-Handler
  parst es zurück in strukturierte `tool_calls` in der OpenAI-kompatiblen Antwort.

**Robustheit (Einschätzung, mit Belegen):**

- Qwen 2.5 (Hermes-2-Pro-Format) und Mistral-7B-v0.3 (Mistral-Nemo-Format) haben **native
  Handler** → grundsätzlich brauchbares, aber nicht perfektes Tool-Calling bei 3B–7B.
- **Warnhinweise aus der Doku:** Ein **tool-fähiges Jinja-Template ist Pflicht** (prüfbar
  über `chat_template_tool_use` unter `http://<server>/props`); bei fehlendem Template
  `--chat-template chatml` oder eigenes Template. **Aggressive KV-Quantisierung
  (z. B. `-ctk q4_0`) kann Tool-Calling deutlich verschlechtern** (explizite CAUTION in
  der Doku).
- Parallele Tool-Calls sind standardmässig **aus** (`"parallel_tool_calls": true` nötig).
- **Praxis-Erwartung:** Bei 3B/7B ist mit gelegentlich fehlerhaftem/halluziniertem
  Tool-Call-JSON zu rechnen; defensives Parsing + Retry/Reprompt einplanen. **Das ist
  Erfahrungswert, keine harte Spec-Aussage.**

**Wichtig:** llama.cpp löst nur **LLM ↔ Tool-Call-Format**. Die **MCP-Schicht** (Verbindung
zum Tavily-Server, `tools/list`, `tools/call`) ist davon getrennt — der App-Code muss die
von llama.cpp erkannten Tool-Calls auf MCP-`tools/call`-Requests mappen und das Ergebnis
als `tool`-Message zurückfüttern.

---

## 5. Unkonventionelle Alternative zum offiziellen SDK

**Empfohlen als Prüfung: Minimaler handgeschriebener JSON-RPC-2.0-Client über Streamable
HTTP** — statt das ganze Kotlin-MCP-SDK (und damit Ktor 3.x) einzuziehen.

**Warum das hier attraktiv ist:**

- Der zu unterstützende Umfang ist **klein und statisch**: `initialize`,
  `notifications/initialized`, `tools/list`, `tools/call`. Das ist **JSON-RPC 2.0 über
  HTTP POST** gegen **einen** Endpoint — genau das, was Streamable HTTP im einfachen Fall
  verlangt (POST mit `Accept: application/json, text/event-stream`, Session über
  `Mcp-Session-Id`).
- **Kein Ktor-3.x-Zwang.** Man kann direkt **OkHttp** (auf Android ohnehin verfügbar,
  battle-tested) oder den **Ktor-2.3.12-Client** nutzen und mit der bereits vorhandenen
  **kotlinx.serialization 1.7.3** die JSON-RPC-Envelopes bauen. → **Kein grosser
  Stack-Upgrade**, keine Kollision mit dem vorhandenen Ktor-2-Server.
- SSE-Handling nur dann nötig, wenn der Server `text/event-stream` zurückgibt; für reine
  Tool-Calls reicht oft der JSON-Antwortpfad. OkHttp hat mit `EventSource` eine
  SSE-Fallback-Option, falls doch gestreamt wird.

**Ehrlicher Trade-off:**

- **Contra:** Man implementiert Protokoll-Details selbst (Session-Header,
  Protocol-Version-Negotiation, Fehler-/Reconnect-Handling, evtl. SSE-Parsing, künftige
  Spec-Änderungen). Kein „geschenktes" Mitwachsen mit der Spec; OAuth-Flows (2025-11-25)
  müsste man selbst nachziehen. Mehr eigener Testaufwand.
- **Pro:** Minimale Abhängigkeiten, volle Kontrolle, **keine erzwungene Migration** von
  Ktor 2 → 3 / Kotlin 2.0 → 2.4, kleinere APK, kein pre-1.0-SDK-API-Bruchrisiko.

**Noch radikaler: Tavily-REST direkt, ganz ohne MCP-Layer.**

- Tavily bietet eine **reguläre REST-Such-API** (`https://api.tavily.com`, Bearer-API-Key).
  Wenn Tavily **das einzige Tool** bleibt, ist ein direkter REST-Call **deutlich einfacher**
  als der ganze MCP-Stack — ein POST, eine JSON-Antwort, fertig.
- **Trade-off:** Man verliert die **MCP-Abstraktion** (einheitliches `tools/list`/
  `tools/call` über mehrere Server, dynamische Tool-Discovery, Wiederverwendung derselben
  Tool-Schleife für weitere MCP-Server). Sobald ein **zweiter** MCP-Server dazukommt,
  zahlt sich MCP aus; für „nur Tavily" ist es Overhead.
- **Einschätzung:** Wenn das erklärte Architekturziel „nativer MCP-Client, Tavily ist nur
  das erste Tool" ist, dann ist der **handgeschriebene JSON-RPC-MCP-Client die beste
  Balance**: MCP-konform, aber ohne den teuren SDK-/Ktor-3-Zwang. Der reine
  REST-Direktweg ist nur sinnvoll, wenn MCP als Ziel fallen gelassen wird.

---

## Konkrete Empfehlung (verdichtet)

1. **Transport:** Streamable HTTP, Protokollversion `2025-06-18` als Basis.
2. **Tavily:** Remote-Endpoint `https://mcp.tavily.com/mcp/?tavilyApiKey=<key>`,
   Tools zur Laufzeit über `tools/list` ermitteln (nicht hartkodieren).
3. **Client-Implementierung:** Wegen des Ktor-2→3-/Kotlin-2.0→2.4-Konflikts primär den
   **schlanken eigenen JSON-RPC-2.0-Client über OkHttp oder Ktor-2-Client** prüfen; das
   offizielle SDK nur wählen, wenn ein Full-Stack-Upgrade auf Ktor 3.x ohnehin ansteht.
4. **LLM-Tool-Calls:** `llama-server --jinja`, Qwen 2.5 3B (Hermes-2-Pro) bzw.
   Mistral-7B-Instruct-**v0.3** (Mistral-Nemo); tool-fähiges Jinja-Template sicherstellen,
   starke KV-Quantisierung vermeiden, defensives Tool-Call-Parsing + Retry.

---

## Quellen (URLs)

**Kotlin MCP SDK**
- Repo/README: https://github.com/modelcontextprotocol/kotlin-sdk
- README (raw): https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/main/README.md
- Releases (0.13.0 etc.): https://github.com/modelcontextprotocol/kotlin-sdk/releases
- Versions-Katalog (Ktor 3.5.1, Kotlin 2.4.0): https://github.com/modelcontextprotocol/kotlin-sdk/blob/main/gradle/libs.versions.toml
- Maven Central: https://central.sonatype.com/artifact/io.modelcontextprotocol/kotlin-sdk
- Android-Issue #234: https://github.com/modelcontextprotocol/kotlin-sdk/issues/234

**MCP-Spezifikation / Transports**
- Transports (2025-06-18): https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
- Transports (raw): https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/specification/2025-06-18/basic/transports.mdx
- Changelog 2025-11-25: https://modelcontextprotocol.io/specification/2025-11-25/changelog
- Spec-Übersicht: https://modelcontextprotocol.io/specification

**Tavily MCP**
- Repo/README: https://github.com/tavily-ai/tavily-mcp
- Tool-Definitionen (src/index.ts): https://raw.githubusercontent.com/tavily-ai/tavily-mcp/main/src/index.ts
- npm: https://www.npmjs.com/package/tavily-mcp
- Remote-Endpoint: https://mcp.tavily.com/mcp/?tavilyApiKey=<your-api-key>
- API-Key / Konto: https://app.tavily.com/home
- Tavily REST-Doku (Direkt-Alternative): https://docs.tavily.com/documentation/api-reference/search

**llama.cpp Tool-Calling**
- Function-Calling-Doku: https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md
- Doku (raw): https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/function-calling.md
- `common/chat.h` (common_chat_templates_inputs, tools, use_jinja): https://github.com/ggml-org/llama.cpp/blob/master/common/chat.h
- Function-Calling-Grundlagen-PR #9639: https://github.com/ggml-org/llama.cpp/pull/9639

**Ktor-Client-Engines (Android)**
- Client-Engine-Abhängigkeiten (OkHttp/CIO/Apache): https://ktor.io/docs/client-dependencies.html

---

### Sicherheits-/Unsicherheits-Kennzeichnung

- **Gesichert (aus Primärquellen belegt):** SDK-Version/Koordinaten/Targets, Ktor-3.5.1-/
  Kotlin-2.4-Abhängigkeit des SDK, Transport-Standardisierung (Streamable HTTP ersetzt
  HTTP+SSE), Streamable-HTTP-Mechanik/Header, Tavily-Remote-URL/Auth/Tools, llama.cpp
  `--jinja`/Format-Handler-Zuordnung (Qwen 2.5→Hermes 2 Pro, Mistral-7B-v0.3→Mistral Nemo),
  `common_chat_templates_inputs.tools`/`use_jinja`.
- **Einschätzung/Erfahrungswert (nicht normativ):** konkrete Robustheit des Tool-Callings
  bei 3B/7B, „welche Protokollversion wählen", OkHttp-vs-CIO-Empfehlung, die Bewertung
  „handgeschriebener Client ist beste Balance". Diese Punkte sind als Empfehlung markiert,
  nicht als Spec-Fakt.
- **Offen:** Ob 0.14.0 des Kotlin-SDK Android-spezifische Verbesserungen bringt (Tag
  existiert, Release-Notes zum Abrufzeitpunkt noch nicht veröffentlicht).

# Wie das "Wunder" Hermes Agent funktioniert

*Code-Analyse des Repos `nousresearch/hermes-agent`, mit Blick auf AIpaca als Edge-Pendant.*

---

## 0. Die Entzauberung vorweg

Bevor wir eintauchen, die wichtigste Erkenntnis aus dem Code: **Hermes lernt nicht durch Training.** Es gibt keinen RL-Loop, keine Gewichts-Updates, kein Fine-Tuning zur Laufzeit. Das beworbene "self-improving" ist zu 100 % **Prompt- und Dateisystem-Engineering** – der Agent lernt, indem er Markdown-Dateien (Skills und Memory) schreibt, die zukünftige Sessions über einen kompakten Index wieder einladen.

Das ist die eigentlich gute Nachricht für AIpaca: Das "Wunder" hängt nicht an teurem Training oder großen Modellen. Es besteht aus einer Handvoll cleverer, größtenteils modell-unabhängiger Mechaniken, von denen die meisten auf einem Edge-Device genauso funktionieren wie auf einem GPU-Cluster.

Das Repo ist riesig (allein `cli.py` 17k Zeilen, `hermes_state.py` 11k, `run_agent.py` 7k) und deckt viel Infrastruktur ab (Gateway für 25+ Chat-Plattformen, 6 Terminal-Backends, Trajectory-Compression für Trainingsdaten). Der konzeptionelle Kern ist aber überschaubar und lässt sich in sechs Ideen zusammenfassen.

---

## 1. Der Agent-Loop – das Herzstück

**Wo:** `agent/conversation_loop.py::run_conversation` (ab Zeile 1061). `run_agent.py` enthält nur die riesige `AIAgent`-Klasse (State/Clients), der eigentliche Loop ist herausgezogen.

Der Ablauf ist der klassische Agentic-Loop, aber industriell gehärtet:

1. **Turn-Setup** (`build_turn_context`): User-Message säubern, System-Prompt bauen-oder-wiederherstellen, ggf. vorab komprimieren, Memory prefetchen.
2. **Hauptschleife** (`while api_call_count < max_iterations and iteration_budget.remaining > 0`):
   - Model-Call (standardmäßig **streaming**)
   - Antwort hat **Tool-Calls** → assistant-Message anhängen, **in DB flushen**, Tools ausführen, `continue`
   - Antwort hat **keine** Tool-Calls → das ist die finale Antwort, `break`
3. **Turn-Ende**: Result-Dict bauen, Background-Review anstoßen (dazu später).

Drei Details, die den Loop von einem Tutorial-Beispiel unterscheiden:

**Persist-before-execute** (`conversation_loop.py:5948`). Die Tool-Call-Message wird in die SQLite-Session-DB geschrieben, *bevor* das Tool läuft. Wenn ein Kommando Hermes mitten im Turn abschießt, sieht der Resume-Pfad exakt den schon ausgeführten Block – keine Doppelausführung, kein verlorener Turn. Reliability-Detail, das man selten sauber sieht.

**Iteration-Budget mit Refund** (`agent/iteration_budget.py`). Ein einziger thread-safe Zähler (Default 500) begrenzt Endlosschleifen. Der Clou: "billige" Operationen wie `execute_code` oder Recovery-Retries werden dem Budget **zurückerstattet** (`conversation_loop.py:6040`). So zählt nur echter Modell-Fortschritt gegen das Limit, nicht die RPC-artigen Sammel-Calls.

**Turn-Retry-State** (`agent/turn_retry_state.py`). ~16 One-shot-Guards (OAuth-Refresh, Bild-verkleinern, Grammar-Fallback, Kompressions-Restart) in einer Dataclass. Jeder Recovery-Zweig feuert garantiert **max. einmal pro Versuch** – Recovery-Endlosschleifen sind strukturell verhindert, nicht per Ad-hoc-Check.

**Provider-Abstraktion:** Zwei orthogonale Achsen – `api_mode` (Wire-Format: OpenAI-chat, Anthropic-messages, Bedrock, Codex) und `provider` (konkretes Backend/Auth). Der Transport macht *nur* Format-Konvertierung; Streaming, Retry, Caching und Credential-Refresh bleiben zentral im Loop. Deshalb teilen ~ein Dutzend verschiedene Backends denselben Loop-Code. Ein neuer Provider braucht nur Endpoint-Erkennung + einen Transport.

---

## 2. Der eigentliche Star: Programmatic Tool Calling (Zero-Context-RPC)

Das ist die technisch cleverste Idee im ganzen Repo – und die, die den größten Unterschied zu normalen Agents macht.

**Wo:** `tools/code_execution_tool.py`. Dem Modell exponiert als Tool `execute_code`.

**Das Problem, das es löst:** Ein normaler Agent, der 50 URLs durchsuchen und filtern soll, macht 50 Tool-Calls über 50 Inferenz-Turns – und jedes einzelne Zwischenergebnis landet im Context-Window und kostet Tokens. Das ist langsam und teuer.

**Die Lösung:** Das Modell schreibt *ein Python-Skript*, das die Tool-Calls in einer Schleife macht, filtert/aggregiert, und nur das kondensierte Endergebnis per `print()` zurückgibt.

So funktioniert es mechanisch:

1. **Stub-Modul zur Laufzeit generieren** (`generate_hermes_tools_module`): Für die 7 erlaubten Sandbox-Tools (`web_search, web_extract, read_file, write_file, search_files, patch, terminal`) wird eine `hermes_tools.py` erzeugt, deren Funktionen nur `return _call("tool", args)` enthalten – dünne RPC-Proxies.
2. **Unix-Socket + Token**: Der Parent legt ein AF_UNIX-Socket an (`chmod 0600`), startet einen Listener-Thread, generiert ein Zufalls-Token zur Authentifizierung.
3. **Kind-Prozess**: Ein separater Python-Prozess wird gespawnt – mit **von Credentials gesäubertem Environment** (`_scrub_child_env`, Exfil-Schutz). Er kennt nur Socket-Pfad und Token.
4. **RPC-Roundtrip**: Ruft das Skript `web_extract(...)` auf, geht das als JSON über das Socket zurück an den Parent, der es durch **denselben** `handle_function_call`-Dispatcher schickt wie ein normaler Model-Tool-Call. Ergebnis zurück ins Socket.
5. **Rückgabe ans Modell**: Nur der **stdout des Skripts** (gedeckelt auf 50 KB) kostet Kontext. Die 50 Websuchen, alle gelesenen Dateien, alle Terminal-Outputs – **tauchen nie im Context-Window auf**.

Eine Multi-Step-Pipeline kollabiert so in *einen* Turn zu quasi-null Kontextkosten. Für Remote-Backends (Docker/SSH/Modal) gibt es dieselbe Mechanik file-basiert statt über Socket.

**Sicherheit:** Da das Skript `subprocess`/`os.system` direkt aufrufen könnte, wird das **ganze Skript vorab** approved (`check_execute_code_guard`), und der RPC-Thread läuft im Approval-Kontext des Turns.

> **Für AIpaca hochrelevant:** Genau dieser Trick adressiert den teuersten Constraint auf einem Edge-Device – das winzige Kontextfenster. Ein 3B-Modell auf dem Handy kann keine 50 Zwischenergebnisse im Kontext halten; wenn es stattdessen ein kleines Skript schreibt, das aggregiert, wird Batch-Arbeit überhaupt erst machbar. Der Mechanismus ist reines OS-Handwerk (Subprozess + Socket), völlig modell-unabhängig.

---

## 3. Das beworbene "Wunder": Skills & der Learning-Loop

**Wo:** `agent/learn_prompt.py`, `agent/background_review.py`, der `skills/`-Ordner.

Hermes hat kein monolithisches Lern-System, sondern zwei saubere Pfade, die beide in denselben Dateispeicher schreiben.

### Skill-Struktur (progressive disclosure)

Ein Skill ist ein Ordner mit `SKILL.md` (YAML-Frontmatter + Markdown-Body), optional `references/`, `templates/`, `scripts/`. Zentrale Design-Entscheidung:

- Im System-Prompt steht nur ein **kompakter Index**: Name + eine Beschreibung von **max. 60 Zeichen** (das Limit ist funktional – längere Beschreibungen werden abgeschnitten und routen nie).
- Der volle Body wird erst **on demand** via `skill_view(name)` geladen.
- Unter Kontextdruck werden geladene Bodies wieder rausgeworfen (`[SKILL_PRUNED]`-Marker) und vor erneuter Nutzung neu geladen.

Also: *Index immer sichtbar → Body bei Bedarf laden → unter Druck evicten → vor Nutzung neu laden.* Genau das richtige Muster für kleine Kontextfenster.

### Wie neue Skills entstehen

**Explizit (`/learn`):** `build_learn_prompt` weist den *laufenden* Agenten an, mit seinen normalen Tools Quellen zu sammeln und via `skill_manage(action="create")` eine SKILL.md zu schreiben. Kein separater Distillations-Engine – der Agent macht die Arbeit selbst.

**Automatisch (der echte Loop):** Nach einem Turn, falls der Trigger feuert, forkt Hermes einen **Background-Review-Agenten** in einem Daemon-Thread (`_spawn_background_review`). Der:
- läuft **nach** Auslieferung der Antwort (konkurriert nicht mit dem User-Task)
- **erbt den gecachten System-Prompt des Parents** → trifft denselben Prefix-Cache → billige Cache-Reads beim Replay der Konversation
- darf **nur** `memory` + `skill_manage` benutzen (alles andere wird hart verweigert)
- spawnt keine weiteren Reviews (Rekursionssperre)

Der Review-Prompt ist bewusst aktivistisch ("die meisten Sessions produzieren mindestens ein Skill-Update") und präferiert: aktuell geladenen Skill patchen → bestehenden Umbrella-Skill erweitern → Support-Datei anlegen → neuen Skill erzeugen.

### Der Trigger ("periodic nudges")

Rein zählerbasiert, **kein LLM-Judge**: `_iters_since_skill >= 10` (Default). Der Zähler wird bei jeder Tool-Iteration erhöht und auf 0 zurückgesetzt, sobald `skill_manage` benutzt wurde. Ein Task mit ≥10 Tool-Calls gilt als "komplex genug" für einen Review; simple Ein-Antwort-Turns lösen nie einen aus. Der teure LLM-Pass läuft also nur, wenn sich Arbeit angesammelt hat.

### Bemerkenswert: Anti-Poisoning

Es gibt eine explizite **"Do NOT capture"-Liste** (`background_review.py:271`): Umgebungsfehler und **negative Tool-Claims** ("browser tools do not work") dürfen *nicht* persistiert werden – genau weil sie sonst "zu Refusals verhärten, die der Agent monatelang gegen sich selbst zitiert". Das ist echtes Design gegen Selbstvergiftung, und für schwächere on-device-Modelle sogar wichtiger als für große.

### Der "Learning Graph"

`agent/learning_graph.py` macht das Gelernte sichtbar: Knoten = gelernte Skills + Memory-Chunks, Kanten aus deklarierten `related_skills` und lexikalischer Token-Überlappung. Im Terminal als Timeline-Balken mit Age-Gradient, im Desktop als GPU-"Starmap". Reines Visualisierungs-Feature.

---

## 4. Memory & User-Modeling: drei Schichten

**Wo:** `tools/memory_tool.py`, `tools/session_search_tool.py`, `hermes_state.py`, `plugins/memory/honcho/`.

### Schicht 1 – Kuratiertes Memory (Dateien, rein lokal)

Zwei Markdown-Dateien: `MEMORY.md` (Agent-Notizen) und `USER.md` (Wissen über den User), Einträge durch `§` getrennt, mit Zeichen-Limits. Der Agent schreibt selbst via `memory`-Tool (proaktiv laut Schema-Anweisung, plus periodischer Nudge, plus Background-Review).

**Das clevere Detail – Frozen Snapshot:** Ein Write geht *sofort* atomar auf die Platte, ändert aber **nicht** den System-Prompt der laufenden Session. Der Prompt wird nur einmal pro Session eingefroren. Damit bleibt der KV-Cache die ganze Session warm, obwohl Memory sich ändert. Löst elegant den Konflikt "dauerhaftes Memory" vs. "warmer Cache".

### Schicht 2 – Session-Suche (SQLite FTS5, rein lokal)

Cross-Session-Recall **ohne LLM und ohne Embeddings**. Ein External-Content-FTS5-Index über den Message-Store, plus ein Trigram-Index für CJK-Substring-Suche. Suche via BM25-Ranking. Statt alte Sessions teuer zusammenzufassen, liefert die Suche **Bookends**: die ersten 3 + letzten 3 Nachrichten einer Session + das Match-Window (Ziel → Treffer → Auflösung), und das Modell rekonstruiert den Kontext selbst.

> *Hinweis:* Das im README erwähnte "FTS5 mit LLM summarization" ist im aktuellen Code **nicht mehr** zutreffend – der LLM-Pfad wurde entfernt, `session_search` macht heute null LLM-Calls. Das macht es noch edge-freundlicher.

### Schicht 3 – Dialectic User Modeling (Honcho, extern/Cloud)

Ein pluggable `MemoryProvider` (ABC in `agent/memory_provider.py`). Honcho baut serverseitig eine "Representation" des Users aus jedem Turn und ist per natürlicher Sprache abfragbar (`peer.chat()`, adaptive Reasoning-Tiefe). Auto-Injektion erfolgt nicht in den (gecachten) System-Prompt, sondern per-Turn in die API-Kopie der User-Message. Eine Brücke spiegelt jeden lokalen `USER.md`-Write automatisch als Honcho-Conclusion.

**Das ist die einzige Schicht mit Cloud-Abhängigkeit** (semantic search + LLM-Reasoning). Self-hostbar, aber nie ganz on-device. Die `MemoryProvider`-ABC ist aber genau der saubere Austauschpunkt: Ein lokaler Embedding-Provider könnte Honcho on-device ersetzen (es existiert bereits ein `plugins/memory/holographic`-Ansatz).

---

## 5. Der rote Faden: Prompt-Caching als Kostenhebel

Auffällig ist, wie viele Design-Entscheidungen sich um **einen** Punkt drehen: den Upstream-KV/Prefix-Cache warm zu halten. Das ist der größte wiederkehrende Kostenfaktor bei API-Modellen.

- **Drei-Tier-System-Prompt** (`stable | context | volatile`): Als *ein* String gespeichert, aber im Outgoing-Request an Cache-Breakpoints gesplittet. Der stabile Prefix (Identität, Tool-Guidance, Skills-Index) bleibt über Turns identisch.
- **Datumsgenauer Timestamp** statt minutengenau (`"%A, %B %d, %Y"`) → der Prompt bleibt byte-stabil über den ganzen Tag.
- **Frozen-Snapshot-Memory** (s.o.) → Writes invalidieren den Cache nicht.
- **Background-Review erbt den warmen Cache** → Self-Improvement ist fast gratis.

Für ein on-device-Modell ist das teils direkt übertragbar (llama.cpp/MLX haben Prompt-/KV-Caching), teils nicht nötig, weil keine API-Kosten anfallen – aber die *Latenz*-Vorteile eines stabilen Prefix gelten auch lokal.

---

## 6. Orchestrierung: Subagents & Mixture of Agents

**Subagents** (`tools/delegate_tool.py`, `agent/subagent_lifecycle.py`): `delegate_task` baut ein **frisches AIAgent** mit isoliertem Kontext – das Kind bekommt nur ein Ziel + optionalen Kontext-String, **nicht** die Historie des Parents (Kontext-Isolation). Es erbt die Toolsets nur **einschränkend** (kann keine Rechte dazugewinnen), teilt die DB, hat eigene Session-ID. Batches laufen parallel auf einem Daemon-Thread-Pool. Ergebnisse kommen als eine kompakte JSON-Liste zurück. Async-Delegation (`background=true`) persistiert in SQLite und überlebt Prozess-Restarts (durable, at-most-once).

**MoA = Mixture of Agents** (`agent/moa_loop.py`) ist etwas *anderes* als Subagents: N "Advisor"-Modelle analysieren parallel den Zustand, 1 "Aggregator"-Modell (das eigentliche handelnde Modell) synthetisiert deren Rat in die Antwort + Tool-Calls. Transparent in den normalen Loop eingeklinkt via OpenAI-kompatible Fassade. Kadenz konfigurierbar (1× pro User-Turn bis pro Iteration). Für AIpaca vermutlich nachrangig – es ist ein Qualitäts-über-Kosten-Feature für Multi-Modell-Setups.

---

## 7. Infrastruktur (für AIpaca meist nachrangig, der Vollständigkeit halber)

- **Gateway** (`gateway/`): *Ein* asyncio-Prozess bedient 25+ Chat-Plattformen über ein einheitliches `BasePlatformAdapter`-Interface. Telegram/Discord/Slack sind bewusst **Plugins**, nicht Core. Session-Zuordnung über `contextvars` (nicht `os.environ`) → nebenläufigkeitssicher. Kontinuität *innerhalb* einer Plattform über deterministische Session-Keys.
- **Cron** (`cron/`): JSON-Storage, 60-s-Ticker mit File-Lock (at-most-once), Schedule-Typen once/interval/cron. "Natural language scheduling" ist LLM-vermittelt: Das `cronjob`-Tool lässt das Modell "morgen um 9" in `"0 9 * * *"` übersetzen; einen separaten NL-Parser gibt es nicht. Cron-Jobs laufen in isolierter Session mit `skip_memory=True`.
- **6 Terminal-Backends** (`tools/environments/`): local, Docker, SSH, Singularity, Modal, Daytona – eine ABC (`_run_bash()` + `cleanup()`), sechs Substrate. "Serverless hibernation" bei Modal (FS-Snapshot-als-Image) und Daytona (echtes Stop/Start) – zwei Wege zum selben Effekt: Arbeitszustand überlebt, obwohl die Live-Sandbox stirbt.

---

## 8. Was das "Wunder" wirklich ist – Synthese

Wenn man den Marketing-Nebel abzieht, besteht Hermes aus sechs Ideen:

1. **Ein gehärteter Agent-Loop** – mit persist-before-execute, refundierbarem Budget und strukturell begrenztem Recovery.
2. **Zero-Context-RPC** (`execute_code`) – das Modell schreibt Skripte, die Tools aufrufen; nur das Ergebnis kostet Kontext. *Der technisch stärkste Trick.*
3. **Skills als Dateien mit progressive disclosure** – Index im Prompt, Body on demand, Prune-und-Reload unter Druck.
4. **Ein Learning-Loop ohne Training** – ein Background-Agent schreibt nach komplexen Tasks Skills/Memory, zähler-getriggert, mit Anti-Poisoning-Regeln.
5. **Dreischichtiges Memory** – kuratierte Dateien + lokale FTS5-Suche + (optional) Cloud-User-Modeling, alles cache-schonend.
6. **Filesystem-als-Datenbank** – Skills und Memory sind Markdown-Ordner + JSON-Sidecars, kein DB-Schema; SQLite nur für die Konversationshistorie.

Das "Selbstverbessern" ist nüchtern betrachtet: **Der Agent schreibt sich selbst Notizen und Anleitungen, die er später über einen billigen Index wiederfindet.** Kein RL, kein Gradientenabstieg. Aber sauber, robust und mit viel Liebe zum Detail umgesetzt – und genau deshalb reproduzierbar.

---

## 9. Was davon auf AIpaca / Edge portierbar ist

Sortiert nach Aufwand/Nutzen für ein Android-first on-device LLM:

**Direkt übernehmbar, hoher Nutzen (kein Cloud, kein großes Modell nötig):**

- **Skills als Dateien + progressive disclosure.** Adressiert exakt den Edge-Killer-Constraint (Kontextlänge). Rein filesystem-basiert, läuft unverändert auf Android/Termux. *Das würde ich als Erstes bauen.*
- **FTS5-Session-Suche mit Bookends.** SQLite FTS5 läuft überall, null LLM-Calls, deterministisch. Der stärkste Kandidat für on-device Cross-Session-Recall.
- **Kuratiertes File-Memory mit Frozen-Snapshot.** Kein Netz, kein Embedding. Der Snapshot-Trick spart auf dem Handy vor allem *Latenz* (stabiler Prefix = schnelleres Prefill).
- **Zähler-basierter Nudge statt LLM-Judge.** Null Modellkosten für die Meta-Entscheidung "wann lernen" – ideal, wenn jede Inferenz teuer (Akku!) ist.
- **Anti-Poisoning-Regeln.** Reines Prompt-Engineering, kostenlos, für schwächere Modelle *wichtiger*. Eventuell zusätzlich als deterministische Regex-Vorfilter, weil ein 3B-Modell die lange Negativliste evtl. schlechter befolgt.

**Portierbar mit Anpassung:**

- **Zero-Context-RPC (`execute_code`).** Der Mechanismus (Subprozess + Socket) ist modell-unabhängig und auf dem Handy machbar (Termux/Python), aber die Prozess-Isolation ist auf Android restriktiver – ggf. auf einen In-Process-Interpreter mit sauberem Namespace ausweichen. Riesiger Nutzen, weil es Batch-Arbeit trotz Mini-Kontext ermöglicht.
- **Background-Review-Loop.** Auf einer Single-NPU/GPU konkurriert ein paralleler zweiter Inferenz-"Thread" um KV-Cache und RAM. Besser **sequenziell nach dem Turn** statt echt-parallel, und mit einem **Digest** (letzte N Messages) statt Full-Replay – dieser Digest-Pfad existiert im Code bereits (`background_review.py:122`).

**Cloud-gebunden, für Edge ersetzen oder weglassen:**

- **Honcho / dialectic user modeling.** Braucht einen laufenden Server + Embeddings/LLM. Für AIpaca entweder weglassen (Schichten 1+2 reichen weit) oder die `MemoryProvider`-ABC nachbauen und mit einem *lokalen* Embedding-Modell füllen.
- **Gateway / 6 Backends / Cron-Delivery an Chat-Plattformen.** Für ein On-Device-App-Konzept weitgehend irrelevant; höchstens ein lokaler Scheduler ist interessant.

**Empfohlene Reihenfolge für AIpaca:** (1) File-Skills + Index → (2) FTS5-Recall → (3) File-Memory mit Snapshot → (4) zähler-getriggerter sequenzieller Learn-Pass mit Digest → (5) In-Process-Variante von `execute_code`. Damit hättest du ~80 % des Hermes-"Wunders" on-device, ohne eine einzige Cloud-Abhängigkeit.

---

### Wichtigste Code-Anker zum Nachlesen

| Thema | Datei |
|---|---|
| Agent-Loop | `agent/conversation_loop.py:1061` |
| Iteration-Budget | `agent/iteration_budget.py` |
| Zero-Context-RPC | `tools/code_execution_tool.py:346, 564, 1188` |
| Tool-Registry | `tools/registry.py:217, 530` |
| Skill-Erzeugung | `agent/learn_prompt.py`, `agent/background_review.py:181` |
| Nudge-Trigger | `agent/turn_finalizer.py:634` |
| Learning Graph | `agent/learning_graph.py:254` |
| File-Memory (Snapshot) | `tools/memory_tool.py:11, 205` |
| FTS5-Suche | `hermes_state.py:1427`, `tools/session_search_tool.py` |
| Honcho (Provider-ABC) | `agent/memory_provider.py`, `plugins/memory/honcho/` |
| Prompt-Caching | `agent/system_prompt.py:152`, `agent/prompt_caching.py` |
| Subagents / MoA | `tools/delegate_tool.py`, `agent/moa_loop.py` |

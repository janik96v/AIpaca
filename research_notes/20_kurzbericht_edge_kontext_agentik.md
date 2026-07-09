# Kurzbericht — Reicht die LLM-Kontextgröße on-device für agentisches Arbeiten? (AIpaca)

Autor: innovation-specialist (Kanban t_a3d9f1f5) · Stand: 2026-07-09
Bezug: Issues #42 (Web Search via MCP) und #43 (On-Device Agent Mode)
Beleg-Stränge: `arxiv_belege_edge_kontext.md` (akademisch), `10_research_mcp_kotlin.md` (MCP/Tool-Calling),
`00_repo_analysis.md` (Code), plus die in diesem Bericht verlinkten Modell-/llama.cpp-Quellen.
Reproduzierbare KV-Rechnung: `<workspace>/kv_calc.py` (Formel + Zahlen unten).

---

## 0. Antwort in einem Satz

**Ja — die Kontextgröße aktueller Edge-LLMs reicht für agentisches Arbeiten, aber der Engpass ist
nicht das trainierte Kontextfenster, sondern der RAM-Verbrauch des KV-Cache.** Und AIpacas heutige
Default-Konfiguration (`contextSize = 1024`, KV-Cache in f16) ist für einen Agent-Modus mit
Tool-Manifest + mehreren Tool-Runden **deutlich zu knapp**. Drei konkrete, günstige Hebel lösen das:
(1) höherer Default-Kontext, (2) KV-Cache-Quantisierung, (3) kompakte Tool-Schemata. Details unten.

---

## 1. Der eigentliche Engpass: RAM, nicht das Kontextfenster

Kleine Instruct-Modelle sind heute nativ langkontextfähig — 32K bis 256K Tokens (siehe §3). On-device
kann man dieses Fenster **RAM-seitig gar nicht ausfahren**, weil der KV-Cache linear mit der
Kontextlänge wächst und **zusätzlich** zum Modellgewicht in den Speicher muss.

**KV-Cache-Formel** (llama.cpp, pro Token):
`bytes_per_token = 2 · n_layers · n_kv_heads · head_dim · bytes_per_elem`
(Faktor 2 = K und V; `bytes_per_elem`: f16 = 2.0, q8_0 ≈ 1.06, q4_0 ≈ 0.56)

Reproduziert (`kv_calc.py`), KV-Cache-RAM **zusätzlich** zum ~1.9 GB Modellgewicht:

| Modell (Q4_0-Gewichte) | KV/Token (f16) | 4K | 8K | 16K | 32K |
|---|---|---|---|---|---|
| **Qwen2.5-3B-Instruct** (GQA 2 KV-Heads) | 36 KB | 144 MB | 288 MB | 576 MB | 1152 MB |
| Qwen3-4B-Instruct-2507 (8 KV-Heads) | 144 KB | 576 MB | 1152 MB | 2304 MB | 4608 MB |
| Llama-3.2-3B (8 KV-Heads) | 112 KB | 448 MB | 896 MB | 1792 MB | 3584 MB |
| Mistral-7B-Instruct-v0.3 (8 KV-Heads) | 128 KB | 512 MB | 1024 MB | 2048 MB | 4096 MB |

**Erkenntnis:** Qwen2.5-3B ist wegen seiner aggressiven GQA (nur **2 KV-Heads**) der mit Abstand
KV-sparsamste Kandidat — bei gleichem Kontext braucht es **~4× weniger KV-RAM** als Qwen3-4B oder
Llama-3.2-3B. Für ein Handy mit 8–12 GB RAM (davon nutzbar oft < 6 GB) ist das der entscheidende
Vorteil und bestätigt AIpacas bisherige Default-Wahl. Belegt akademisch: auf einem M4 Pro mit
10.2 GB Cache-Budget passen in FP16 nur **3 Agenten @ 8K** gleichzeitig — der Limiter ist Cache-RAM
([arXiv:2603.04428](https://arxiv.org/abs/2603.04428)).

---

## 2. Der Ist-Zustand in AIpaca (aus dem Code verifiziert)

| Punkt | Fundstelle | Bewertung für Agent-Modus |
|---|---|---|
| Default-Kontext **1024 Tokens** | `EngineState.kt:91` (`MutableStateFlow(1024)`), Empfehlung `1024` in `ChatScreen.kt:801` | **Zu klein.** Ein MCP-Tool-Manifest (Tavily: 4 Tools) + ein Suchergebnis (mehrere Snippets + URLs) sprengt das leicht. |
| UI-Kontextoptionen max **8192** | `ChatScreen.kt:800` (`listOf(512,1024,2048,4096,8192)`) | 8192 reicht für 1–2 Tool-Runden; für längere Agent-Loops knapp. |
| **KV-Cache = f16** (kein `type_k`/`type_v` gesetzt) | `llama_jni.cpp:351–357` — nur `n_ctx/n_batch/n_ubatch/n_threads` | **Ungenutzter Hebel.** q8_0-KV halbiert den KV-RAM bei ~0 Qualitätsverlust. |
| `n_batch = n_ctx` | `llama_jni.cpp:354` (`// must fit full prompt`) | Höherer Kontext erhöht auch die Prefill-Batch-Größe → Peak-RAM beim Laden beachten. |
| 25 % Kontext für Output reserviert, ~4 Chars/Token | `ChatScreen.kt:519–520` | Vernünftige Heuristik; für Agent-Turns eher großzügiger Output-Reserve einplanen. |

**Fazit §2:** Die technischen Anschlusspunkte für alle Empfehlungen existieren bereits im JNI-Pfad;
es sind kleine, chirurgische Änderungen (ein zusätzlicher Parameter für KV-Typ, ein höherer Default).

---

## 3. State-of-the-Art edge-fähiger Modelle (2025/2026)

Bewertet nach den drei für AIpaca relevanten Achsen: **Kontextfenster · Tool-Calling in llama.cpp ·
KV-RAM-Effizienz**.

| Modell | Natives Kontextfenster | Tool-Calling (llama.cpp) | KV-Effizienz | Einschätzung für AIpaca |
|---|---|---|---|---|
| **Qwen2.5-3B-Instruct** ⭐ | 32.768 (voll, GGUF) — [HF](https://huggingface.co/Qwen/Qwen2.5-3B-Instruct) | Handler **Hermes-2-Pro** (`<tool_call>…`), `--jinja` | **Beste** (2 KV-Heads) | **Empfohlener Default.** Tool-fähig, KV-sparsam, bereits getestet. |
| Qwen3-4B-Instruct-2507 | sehr lang (nativ ≫32K; YaRN-Scaling dokumentiert) | Qwen3-Handler, gutes Agentic-Verhalten | schwach (8 KV-Heads → 4× KV-RAM) | Stärker im Reasoning, aber KV-teuer. Kandidat, wenn RAM reicht (12 GB+). |
| Llama-3.2-3B-Instruct | 128K (RoPE) | Llama-3-Handler | schwach (8 KV-Heads) | Solide, aber KV-teuer wie Qwen3-4B. |
| Mistral-7B-Instruct-**v0.3** | 32K | Handler **Mistral-Nemo** (`[TOOL_CALLS]`) — **nur v0.3!** v0.1/v0.2 fallen auf den schlechteren Generic-Handler | mittel, aber 7B-Gewicht ~4 GB | Bestes Tool-Calling, aber Gewicht + KV grenzwertig für 8-GB-Geräte. |
| Phi-4-mini-instruct | 128K | Handler vorhanden | mittel (8 KV-Heads) | Ordentlich; kein klarer Vorteil ggü. Qwen2.5-3B on-device. |
| Gemma-2-2B-it | 8K (SWA) | begrenzt | mittel (head_dim 256) | Klein/schnell, aber kurzes Fenster + schwächeres Tool-Calling → für Agent-Modus ungeeignet. |

**Effektiver vs. nomineller Kontext (wichtige Einschränkung):** Kleine Modelle *halten* langen Kontext
schlechter, als das nominelle Fenster verspricht ("lost in the middle"; RULER-/NoLiMa-Benchmarks
zeigen deutlichen Abfall unterhalb 8B). **Praktische Konsequenz für AIpaca:** Nicht auf riesige
Fenster setzen, sondern den Kontext **kurz und relevant halten** — was ohnehin RAM spart. Das deckt
sich mit dem Befund, dass relevanzbasiertes Kürzen Volltext-Cache sogar schlagen kann
([ReST-KV, arXiv:2605.08840](https://arxiv.org/abs/2605.08840): RULER +15.2 %).

> Belegtiefe zu Modellen/Benchmarks wird durch den laufenden Web-Recherche-Strang ergänzt; die
> obige Handler-Zuordnung ist aus `10_research_mcp_kotlin.md` verifiziert.

---

## 4. Techniken zur Kontextreduktion — priorisiert für AIpaca

Reihenfolge = Aufwand-/Nutzen-Empfehlung. Jeder Punkt mit ehrlichem Trade-off.

### Hebel A (sofort, hoher Nutzen): KV-Cache-Quantisierung
- **Was:** In `llama_jni.cpp` `cparams.type_k`/`cparams.type_v` auf `GGML_TYPE_Q8_0` setzen
  (bisher ungesetzt → f16). Optional asymmetrisch: **Keys q8_0, Values q4_0**.
- **Nutzen:** q8_0-KV ≈ **halber** KV-RAM (36 → 19 KB/Tok bei Qwen2.5-3B), q4_0 ≈ **Viertel**.
  Damit passt bei gleichem RAM 2–4× mehr Agent-Kontext.
- **Qualität:** real gemessen praktisch vernachlässigbar — z.B. **+2.8 % PPL** (Llama-3.1-8B) für Q4-KV
  ([arXiv:2603.04428](https://arxiv.org/abs/2603.04428)); asymmetrisch (q8 Keys / 3-bit Values)
  sogar **+0.57 % PPL** ([PolyKV, arXiv:2604.24971](https://arxiv.org/abs/2604.24971)).
- **⚠️ Trade-off / Randbedingung:**
  1. **Starke KV-Quantisierung (q4_0) verschlechtert Tool-Calling** (siehe `10_research_mcp_kotlin.md`
     §5.3 und Issue-#42-Spec §5.3). → **Empfehlung: Keys nie unter q8_0.** Für den Agent-Modus
     lieber **q8_0 symmetrisch** als aggressives q4.
  2. KV-Quantisierung profitiert stark von **Flash-Attention** — die ist auf AIpacas Adreno-OpenCL-
     Backend aber problematisch (Whisper-Erfahrung: `flash_attn` liefert falsche Ergebnisse,
     `lab_journal.md` 2026-06-05). **→ Vor dem Ausrollen auf dem Zielgerät verifizieren**, ob
     quantisierter KV-Cache ohne Flash-Attn korrekt/schnell genug läuft; sonst als CPU-Pfad-Option.

### Hebel B (sofort, niedriger Aufwand): Kontext-Default erhöhen
- **Was:** Default von `1024` auf **4096** (Agent-Preset ggf. **8192**) anheben; UI-Empfehlung
  entsprechend. Bei aktivem Agent-Modus höheren `contextSize` beim Modell-Load erzwingen.
- **Nutzen:** Ohne diesen Schritt scheitert Tool-Calling schon am Manifest. Bei Qwen2.5-3B kostet
  4096 nur **144 MB** KV (f16) bzw. **~76 MB** (q8_0) — problemlos tragbar.
- **Trade-off:** `n_batch = n_ctx` → höherer Prefill-Peak-RAM. Auf Low-RAM-Geräten abfangen.

### Hebel C (zwingend für Tool-Calling bei knappem Budget): kompakte Tool-Schemata
- **Was:** Tool-Definitionen, die ans Modell gehen, **komprimieren** (knappe Beschreibungen,
  minimale JSON-Schemas), statt volle Tavily-JSON-Schemata durchzureichen.
- **Nutzen:** **Binärer Enablement-Effekt** — bei 8K Budget überläuft unkomprimiertes Tool-JSON den
  Kontext und Tool-Calling bricht auf **~2.6 % EM total zusammen**; kompakte Schemata (44–50 %
  Token-Ersparnis) stellen es wieder her: **+20.5 pp EM**
  ([arXiv:2605.26165](https://arxiv.org/abs/2605.26165)). Bei AIpacas heutigem 1024er-Default ist das
  **kein Nice-to-have, sondern Voraussetzung**.
- **Trade-off:** Zu aggressive Kürzung kann Tool-Semantik verwässern → knapp, aber eindeutig halten.

### Hebel D (für Multi-Turn-Agent / Brick 2–3): proaktive Kontext-Kompaktierung
- **Was:** Agent-Historie **nicht append-only** wachsen lassen. An Meilensteinen zusammenfassen;
  vollständige Tool-Ergebnisse **auslagern** und nur eine Referenz/Kurzfassung im Kontext halten
  ("Context as a Tool").
- **Nutzen:** Schlägt passives Truncation bei beschränktem Budget — SWE-Compressor 57.6 % solved auf
  SWE-Bench-Verified unter Kontext-Limit ([arXiv:2512.22087](https://arxiv.org/abs/2512.22087)).
- **Trade-off:** Mehr Engineering (Summarizer-Turns kosten selbst Inferenz). Für Brick 3 (Sessions/
  Memory) einplanen, nicht für den MVP.

### Hebel E (später / optional): RAG statt langem Kontext
- **Was:** Statt lange Dokumente in den Kontext zu laden, chunk + retrieve (Embeddings) und nur die
  Top-k-Passagen einspeisen.
- **Nutzen:** Entkoppelt Wissensmenge vom Kontextfenster; passt zur "kurz & relevant"-Linie aus §3.
- **Trade-off:** Braucht ein On-Device-Embedding-Modell + Vektorindex → zusätzlicher RAM/Komplexität.
  Für #42/#43 **nicht** nötig (Tavily liefert bereits kuratierte, kurze Ergebnisse). Erst relevant,
  wenn eigene Dokument-/Wissensbasis dazukommt.

---

## 5. Konkrete Empfehlung für #42/#43 (entscheidungsreif)

**Modell:** **Qwen2.5-3B-Instruct Q4_0** als Agent-Default beibehalten — bestes Verhältnis aus
Tool-Calling (Hermes-2-Pro-Handler), KV-Effizienz (2 KV-Heads) und getestetem Status. Mistral-7B-v0.3
als optionale "Qualitäts-Stufe" für 12-GB-Geräte, wo Tool-Robustheit über RAM geht.

**Konfiguration für den Agent-Modus (Minimal-Set):**
1. `contextSize`-Default im Agent-Pfad auf **4096** (Preset "Agent": 8192).
2. **KV-Cache q8_0** aktivieren (`type_k = type_v = Q8_0` im JNI-Pfad) — **Keys nicht unter q8_0**.
   Auf dem Zielgerät (Adreno/OpenCL, ohne Flash-Attn) verifizieren.
3. **Tool-Schemata komprimiert** an das Modell geben (kompaktes Manifest, nicht das rohe Tavily-JSON).
4. Output-Reserve großzügig halten (Tool-Turns brauchen Platz für Argument-JSON).

**Was NICHT nötig ist (Hype-Check):** Kein Wechsel auf ein 128K/256K-Modell "wegen Kontext" — das
löst das Problem nicht (RAM bleibt der Limiter, effektiver Kontext < nominell). Kein RAG für den MVP.
Keine aggressive q4-KV (killt Tool-Calling). Keine lernbaren KV-Eviction-Verfahren (P5) im MVP —
konzeptioneller Rückenwind, aber Overkill für ein 3B-Modell auf dem Handy.

---

## 6. Offene Risiken / zu verifizieren

- **KV-Quant-Umsetzung (Stand: `feature/kv-cache-q8`, `llama_jni.cpp` `nativeLoadModel`):**
  `type_k = GGML_TYPE_Q8_0` ist jetzt **immer** gesetzt (GPU- und CPU-Pfad) — Keys sind nie unter
  q8_0, wie empfohlen. Für `type_v` wurde die llama.cpp-Randbedingung genutzt statt umgangen:
  quantisiertes V **erfordert** Flash-Attention (`llama-context.cpp`: harter Fail "V cache
  quantization requires flash_attn" wenn `type_v` quantisiert und FA disabled ist). Da FA auf
  AIpacas Adreno/OpenCL-Pfad unverifiziert riskant ist (Analogie Whisper-`flash_attn`-Bug,
  `lab_journal.md` 2026-06-05), gilt jetzt:
  - **GPU-Offload aktiv** (`effective_gpu_layers > 0`): `flash_attn` bleibt **disabled**,
    `type_v` bleibt **F16** (nur Keys quantisiert → ~25 % KV-RAM-Ersparnis statt ~50 %, aber
    sicher ohne FA).
  - **CPU-only** (`effective_gpu_layers == 0`): `flash_attn` wird **enabled** (CPU-FA ist die
    gut getestete llama.cpp-Referenzimplementierung, nicht vom Adreno-OpenCL-Bug betroffen),
    `type_v = Q8_0` → volle ~50 % KV-RAM-Ersparnis.
  - **Noch zu verifizieren auf echtem Snapdragon-Gerät:** ob `FLASH_ATTN_EXT` im OpenCL-Backend
    für llama.cpps Decode-Graph (nicht nur Whisper) tatsächlich fehlerhaft ist. Falls sich FA auf
    Adreno als korrekt herausstellt, kann der GPU-Zweig ebenfalls auf `flash_attn=enabled` +
    `type_v=Q8_0` umgestellt werden (Code-Kommentar in `llama_jni.cpp` markiert die Stelle mit
    einem TODO).
- **Effektiver Kontext von Qwen2.5-3B bei 8K+** unter Tool-Last: mit einer echten Tavily-Recherchefrage
  gegen das geladene GGUF messen (nicht nur nominell vertrauen).
- **Prefill-Peak-RAM** bei `n_batch = n_ctx` auf 8-GB-Geräten mit 8192er-Kontext.
- Web-Recherche-Strang (aktuelle Modell-Benchmarks, RULER-Zahlen <8B) wird nachgetragen und
  konkretisiert §3.

---

## Quellen (Kurzform)

Akademisch (harte Zahlen, Details in `arxiv_belege_edge_kontext.md`):
- Persistent Q4 KV Cache on Edge — https://arxiv.org/abs/2603.04428
- Tool-Schema Compression under Constrained Context — https://arxiv.org/abs/2605.26165
- Context as a Tool (Long-Horizon Agents) — https://arxiv.org/abs/2512.22087
- PolyKV (asymmetrische KV-Quantisierung) — https://arxiv.org/abs/2604.24971
- ReST-KV / Make Each Token Count — https://arxiv.org/abs/2605.08840 · https://arxiv.org/abs/2605.09649
- Agent-Native Memory Survey — https://arxiv.org/abs/2606.24775

Modelle / Tooling:
- Qwen2.5-3B-Instruct — https://huggingface.co/Qwen/Qwen2.5-3B-Instruct
- Tool-Calling-Handler-Zuordnung (Qwen→Hermes-2-Pro, Mistral-v0.3→Nemo) — `10_research_mcp_kotlin.md`

Code-Fakten (AIpaca-Repo):
- `EngineState.kt:91` (Default 1024) · `ChatScreen.kt:800` (UI-Optionen) ·
  `llama_jni.cpp:351–357` (Context-Params, KV=f16, n_batch=n_ctx) · `lab_journal.md` (Adreno/Flash-Attn).

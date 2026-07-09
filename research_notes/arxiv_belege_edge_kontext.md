# arxiv-Belege — Edge-LLM-Kontext & agentisches Kontext-Management (Stand 2026-07)

Recherchiert von innovation-specialist (Kanban t_a3d9f1f5) via arxiv-API.
Diese Datei ist der akademische Beleg-Strang für den Kurzbericht.

## Kern-Paper (mit harten Zahlen)

### [P1] Agent Memory Below the Prompt: Persistent Q4 KV Cache for Multi-Agent LLM Inference on Edge Devices
- https://arxiv.org/abs/2603.04428 (2026-02-17) · Code: https://github.com/yshk-mxim/agent-memory
- KERNZAHLEN (Edge, direkt relevant):
  - Auf Apple M4 Pro, 10.2 GB Cache-Budget: nur **3 Agenten @ 8K Kontext in FP16** gleichzeitig.
  - **Q4-KV-Quantisierung = 4x mehr Agent-Kontexte** in fixem Gerätespeicher als FP16.
  - Perplexity-Kosten der Q4-KV real gemessen: **-0.7% (Gemma 3 12B), +2.8% (Llama 3.1 8B), +3.0% (DeepSeek-Coder-V2-Lite 16B)** → praktisch vernachlässigbar.
  - Cache-Restore statt Re-Prefill: TTFT bis 136x schneller; ohne Persistenz kostet jede Eviction ein volles Re-Prefill (15.7 s/Agent @ 4K).
- BEDEUTUNG für AIpaca: Der harte Limiter on-device ist RAM (KV-Cache), nicht das trainierte Kontextfenster. KV-Quantisierung (q8/q4) ist der Hebel, der agentische Kontexte on-device überhaupt ermöglicht — mit minimalem Qualitätsverlust.

### [P2] Tool-Schema Compression Enables Agentic RAG Under Constrained Context Budgets
- https://arxiv.org/abs/2605.26165 (2026-05-24)
- KERNZAHLEN: 14 Modelle (1.5B–32B) + 1 Frontier-API, 6.566 API-Calls, Budgets 8K/16K/32K, 28 Tool-Defs.
  - **Binärer Enablement-Effekt**: Bei **8K** überlaufen unkomprimierte JSON-Schema-Tool-Defs den Kontext → **~2.6% EM (Totalausfall)**. Komprimierte Schemata (TSCG, 44–50% Token-Ersparnis) stellen RAG wieder her: **+20.5 pp EM im Schnitt** (+24.7 pp bei den 6 voll aktivierten Modellen).
  - Bei **32K** (beide Formate passen): Δ ≤ 1 pp → Effekt ist rein budget-getrieben.
  - HotpotQA-Validierung: +48 pp EM im Overflow-Szenario.
- BEDEUTUNG für AIpaca: Das **Tool-Manifest selbst** frisst bei knappem Kontext (aktuell 1024!) das Budget auf. Kompakte Tool-Schemata sind bei kleinem Kontext kein Nice-to-have, sondern Voraussetzung dafür, dass Tool-Calling überhaupt funktioniert.

### [P3] Context as a Tool (CAT): Context Management for Long-Horizon SWE-Agents
- https://arxiv.org/abs/2512.22087 (2025-12-26)
- Kontext-Management als **aufrufbares Tool** in der Agent-Entscheidung; strukturierter Workspace: stabile Task-Semantik + kondensiertes Langzeitgedächtnis + hochauflösende Kurzzeit-Interaktion. Proaktive Kompression an Meilensteinen.
- Ergebnis: SWE-Compressor **57.6% solved** auf SWE-Bench-Verified, schlägt ReAct + statische Kompression bei **beschränktem Kontextbudget**.
- BEDEUTUNG: Append-only-Kontext ("alles anhängen") führt zu Context-Explosion, Semantic-Drift, Reasoning-Zerfall. Proaktive, tool-gesteuerte Kompression schlägt passives Abschneiden — überträgt sich direkt auf AIpacas Agent-Loop.

## Ergänzende Paper (KV-Kompression / Eviction — für Tiefe)

### [P4] PolyKV: Shared Asymmetrically-Compressed KV Cache Pool
- https://arxiv.org/abs/2604.24971 (2026-04-27)
- Keys @ int8 (q8_0) für Softmax-Stabilität, Values @ 3-bit; getestet u.a. SmolLM2-1.7B, Llama-3-8B.
- Llama-3-8B, 15 Agenten, 4K: KV von 19.8 GB → 0.45 GB (−97.7%), nur +0.57% PPL.
- BEDEUTUNG: Bestätigt asymmetrische KV-Quantisierung (Keys vorsichtiger quantisieren als Values) als robuste Praxis.

### [P5] Make Each Token Count / ReST-KV (KV-Eviction, lernbar)
- https://arxiv.org/abs/2605.09649 (2026-05-10) · https://arxiv.org/abs/2605.08840 (2026-05-09, Code: https://github.com/an-yongqi/rest-kv)
- Selektive/lernbare KV-Eviction kann Volltext-Cache sogar SCHLAGEN (irrelevante Tokens verwässern Attention). LongBench +2.58%, RULER +15.2%.
- BEDEUTUNG: Weniger Kontext kann besser sein — relevanzbasiertes Kürzen > blindes Volltext-Anhängen. (Fortgeschritten; für AIpaca eher konzeptueller Rückenwind für Retrieval/Re-Ranking als sofort umsetzbar.)

## Survey / Landkarte
### [P6] Are We Ready For An Agent-Native Memory System?
- https://arxiv.org/abs/2606.24775 (2026-06-23) — Agent-Memory hat sich von simplem RAG zu einem Datenmanagement-System (Speicher/Retrieval/Update/Konsolidierung/Lifecycle) entwickelt. Nützlich als Rahmen für Brick 3 (Sessions/Memory).

## Take-away des akademischen Strangs (gesichert)
1. **Der Engpass on-device ist RAM/KV-Cache, nicht das trainierte Kontextfenster.** Moderne kleine Modelle haben nativ 32K–128K; on-device kann man das RAM-seitig gar nicht voll ausfahren.
2. **KV-Quantisierung (q8 Keys / q4 Values)** vervierfacht den nutzbaren Kontext bei ~0–3% Qualitätsverlust → der wichtigste Hebel.
3. **Tool-Schema-Kompression** ist bei knappem Budget zwingend, sonst kein Tool-Calling (binärer Effekt).
4. **Proaktive, tool-gesteuerte Kontext-Kompression** (Summaries an Meilensteinen) schlägt passives Truncation für Multi-Turn-Agenten.

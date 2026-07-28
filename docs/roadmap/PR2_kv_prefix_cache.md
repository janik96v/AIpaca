# PR2 — KV-Prefix-Cache (Hebel F)

Vollständige Spec: `research_notes/spec_kv_prefix_cache.md`. Hier nur die PR-Sicht.

Ziel: KV-Cache über Turns **behalten** statt bei jeder Generierung zu verwerfen
(`llama_jni.cpp` `run_generate` ruft heute `llama_memory_clear` vor Z.801 und nach Z.960). Nur die
**neuen** Tokens werden geprefillt. Orthogonal zu Hebel A (Quant spart RAM; Reuse spart Prefill-Zeit).

**Voraussetzung:** PR1 abgeschlossen. Der größte Gewinn liegt im Multi-Turn-Agent-Loop, und der ist erst
nach PR1 sauber (echte Tool-Rollen, stabiler System+Manifest-Präfix).

## Entkopplung
Hebel F lebt komplett in der Engine-Schicht (`run_generate`/`nativeGenerateAgent`, `LlamaCppEngine`,
`EngineState`). Vom Agent verlangt er nur zwei Verträge:
1. stabile `conversationId` pro Session (LCP nur innerhalb derselben Konversation; Cross-Leak-Reset),
2. stabiler System-Präfix (persona+instr+Manifest ändern sich nicht mitten in der Session).
Beides erfüllt der PR1-`AgentOrchestrator` ohnehin.

## Phasen
- **Phase 1 — In-Session Incremental Prefill.** `LlamaContext` merkt `cached_tokens`+`conversationId`;
  `run_generate` ersetzt `clear` durch LCP-Trim (`llama_memory_seq_rm(mem,0,n_common,-1)`), dekodiert
  nur neue Tokens. CPU-Pfad zuerst. Golden-Test „Reuse == Full-Prefill".
- **Phase 2 — Persistenter Präfix-Cache.** `llama_state_seq_save_file`/`load_file` für den stabilen
  Präfix; Key = `sha256(modelHash + ctxParams{n_ctx,type_k,type_v,gpu|cpu} + templateId + prefixText)`.
  Restore in `EngineState.loadModel`. `PrefixCacheStore` (LRU, Datei-basiert).
- **Phase 3 — Block-Hash-Radix** (optional): mehrere Präfixe, block-granular, LRU.

## Kritische Randbedingungen
- Front-Truncation (`llama_jni.cpp:782`) inkompatibel → Phase 1: bei Overflow Cache invalidieren + Full-Prefill.
- Positions-API von `llama_batch_get_one` gegen den gepinnten Stand verifizieren (Golden-Test).
- Adreno/OpenCL: zuerst nur CPU-Pfad; `seq_rm`/`state_seq_*` auf echtem Gerät prüfen.
- `type_v` (CPU Q8_0 vs GPU F16) gehört in den Cache-Key.

## Akzeptanz
- [ ] Agent-Loop 4 Runden: Summe Prefill-ms ≥ 50 % niedriger als Baseline.
- [ ] Persistenz-Roundtrip: save→unload→load→generate identische Ausgabe; Key-Mismatch wird verworfen.
- [ ] Kein RAM-Regress; Server/Chat weiter grün.

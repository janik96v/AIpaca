# Implementierungs-Spec — Hebel F: Prefix-KV-Cache (Warm-KV-Reuse)

Stand: 2026-07-28 · Bezug: `20_kurzbericht_edge_kontext_agentik.md` (Hebel A–E), Issue #43 (Agent-Modus),
Branch-Vorbild: `feature/kv-cache-q8` (Hebel A) · Vorbild-Mechanik: Hermes/vLLM/SGLang Prefix-Caching
Ziel-Leser: Umsetzer (du oder ein Coding-Agent). Alle Code-Fundstellen aus dem echten Repo verifiziert.

---

## 0. Antwort in einem Satz

**Der KV-Cache wird heute bei jedem Turn weggeworfen und der ganze Prompt neu geprefillt — Hebel F
behält den KV-Cache über Turns hinweg und prefillt nur noch die *neuen* Tokens.** Das ist orthogonal
zu Hebel A (KV-Quantisierung spart RAM; Prefix-Reuse spart Prefill-Rechenzeit) und bringt den größten
Gewinn genau dort, wo AIpaca hin will: bei jedem Multi-Turn-Konsumenten (Chat und v.a. der künftige Agent-Mode, #43).

> ⚠️ **Wichtig — Entkopplung vom bestehenden Agent-Mode:** Der aktuelle `com.aipaca.app.agent.*`
> (AgentLoop/AgentConfig/AgentSession/ToolCallParser) ist ein wegwerfbares Brick-1-Skelett und wird
> neu gebaut (u.a. weil native Tool-Calls nicht verdrahtet sind — `inputs.tools` wird im JNI nie
> gesetzt, siehe §11). **Dieser Plan hängt bewusst NICHT am heutigen Agent-Code.** Hebel F lebt
> vollständig in der Engine-Schicht (`run_generate` / `LlamaCppEngine` / `EngineState`) und stellt an
> jeden Konsumenten — Chat-UI, Server, künftigen Agent — nur **zwei Verträge**:
> (1) eine **stabile `conversationId`** pro Konversation durchreichen, (2) einen **stabilen
> System-Präfix** verwenden. Mehr braucht der KV-Layer nicht zu wissen.

---

## 1. Ist-Zustand (aus dem Code verifiziert)

| Fakt | Fundstelle | Konsequenz |
|---|---|---|
| **KV wird pro Generierung gelöscht** | `llama_jni.cpp:801` (`llama_memory_clear` vor Prefill) **und** `:960` (nach Generierung) | Kein State überlebt einen `generateChat`-Aufruf. |
| **Voller Prompt wird jedes Mal neu geprefillt** | `run_generate` `:727–808`: Template auf *alle* Turns → tokenisieren → ein `llama_decode` über den ganzen Prompt (`:804`) | Turn N zahlt Prefill für System-Prompt + gesamte Historie erneut. |
| **Front-Truncation** hält den *Tail* | `:782–792` (`tokens.erase(begin … n-max)`) | Kürzt vorne weg → **inkompatibel mit Präfix-Reuse** (Position 0 verschiebt sich). Muss in Cached-Mode anders laufen. |
| Ein einziger `llama_context*`, geteilt | `LlamaContext` struct `:45–58`; `EngineState.engine` Singleton `EngineState.kt:49` | Genau **ein** KV-Cache im Prozess → „welche Konversation ist gerade resident?" muss getrackt werden. |
| Zwei Konsumenten, serialisiert | `EngineState.generateMutex` `EngineState.kt:61`; `AgentLoop.generateOnce` `AgentLoop.kt` unter `withLock` | Gut: kein paralleler Decode. Aber Cached-State darf nicht zwischen Konversationen leaken. |
| Jeder Multi-Turn-Konsument re-prefillt die ganze Historie | Chat-UI + (künftiger) Agent rufen `generateChat(turns)` mit wachsender Turn-Liste | **Hier lohnt sich Hebel F am meisten** — unabhängig davon, wie der Agent-Mode am Ende aussieht. |
| KV-Quant schon vorhanden | `:359–395` (`type_k=Q8_0` immer; `type_v=Q8_0`+FA nur CPU) | Cache-Bytes hängen von `type_k/type_v` ab → gehört in den Cache-Key. |
| Prefill wird bereits gemessen | `:809–813` (`t_prefill_ms`) | Instrumentierung für Vorher/Nachher ist da. |

**Kernproblem:** `run_generate` ist *stateless per call* — bewusst simpel, aber verschenkt bei jedem Turn
die teuerste Operation (Prefill des stabilen Präfixes).

---

## 2. Zielbild

Ein **resident-prefix Modell** auf dem einen `llama_context`:

```
Turn n:   [System | Manifest | Turn 1 | … | Turn n-1 | Turn n(neu)]
                     ▲──────── bereits im KV resident ────────▲   ▲ nur das prefillen
```

Zwei Ausbaustufen, unabhängig auslieferbar:

- **Phase 1 — In-Session-Reuse (der 80 %-Gewinn):** KV über Turns behalten, längstes gemeinsames
  Token-Präfix (LCP) gegen den residenten Stand bestimmen, nur den divergenten Suffix aus dem KV
  entfernen und nur die neuen Tokens dekodieren. Wirkt sofort für Chat-Multi-Turn **und** Agent-Loop.
- **Phase 2 — Persistenter Präfix-Cache (der Hermes-Gewinn):** Den KV-State eines stabilen Präfixes
  (System-Prompt + Persona + Tool-Manifest) via `llama_state_seq_save_file` auf Platte schreiben und
  beim Modell-Load wiederherstellen → schon der **erste** Turn einer Session spart den System-Prompt-Prefill.
- **Phase 3 — Block-Hash-Radix-Cache (optional):** mehrere Präfixe (Personas, Dokumente) block-granular,
  LRU-Eviction, quantisiert auf Disk. Erst wenn Phase 1+2 stehen.

Grundsatz durchgehend: **Correctness-first.** Jeder Unsicherheitsfall (Truncation, Konfig-Wechsel,
Konversationswechsel) fällt sauber auf den heutigen „clear + full prefill"-Pfad zurück. Ein falscher
Cache-Treffer ist schlimmer als ein verpasster.

---

## 3. Phase 1 — In-Session Incremental Prefill

### 3.1 Idee

Den `llama_memory_clear`-Aufruf am Anfang von `run_generate` durch eine **LCP-Trim-Logik** ersetzen.
`LlamaContext` merkt sich die zuletzt residenten Prompt-Tokens. Beim nächsten Aufruf:

1. Neuen Prompt wie gehabt via Chat-Template rendern + tokenisieren → `new_tokens`.
2. `n_common = longestCommonPrefix(cached_tokens, new_tokens)`.
3. Divergenten Suffix aus dem KV entfernen: `llama_memory_seq_rm(mem, seq_id=0, p0=n_common, p1=-1)`.
4. Nur `new_tokens[n_common .. end]` dekodieren, Positionen ab `n_common` fortsetzend.
5. `cached_tokens = new_tokens` merken. **Kein** `llama_memory_clear` mehr am Anfang/Ende.

### 3.2 Änderungen in `LlamaContext` (llama_jni.cpp `:45`)

```cpp
struct LlamaContext {
    // … bestehende Felder …
    std::vector<llama_token> cached_tokens;   // resident im KV (seq 0)
    uint64_t                 cached_cfg_hash = 0;  // model+ctxparams identity
    bool                     prefix_reuse_enabled = true;  // Feature-Flag
};
```

### 3.3 Umbau von `run_generate` (llama_jni.cpp `:697`)

Ersetze den Block `:801–808` (clear → `llama_batch_get_one(all)` → decode) durch:

```cpp
llama_memory_t mem = llama_get_memory(lc->ctx);

// Longest common prefix gegen den residenten Stand
size_t n_common = 0;
if (lc->prefix_reuse_enabled) {
    const size_t lim = std::min(lc->cached_tokens.size(), (size_t)n_tokens);
    while (n_common < lim && lc->cached_tokens[n_common] == tokens[n_common]) ++n_common;
    // Wichtig: mind. 1 Token muss neu dekodiert werden (llama braucht einen Decode,
    // um Logits für das erste Sample zu erzeugen). Wenn der ganze Prompt matcht,
    // n_common auf n_tokens-1 zurücknehmen.
    if (n_common == (size_t)n_tokens) n_common = n_tokens - 1;
} else {
    llama_memory_clear(mem, true);
}

// Divergenten Suffix aus dem KV werfen (behält [0, n_common) resident)
if (n_common < lc->cached_tokens.size())
    llama_memory_seq_rm(mem, /*seq_id=*/0, /*p0=*/(llama_pos)n_common, /*p1=*/-1);

// Nur die neuen Tokens dekodieren, Positionen ab n_common
const int n_eval = n_tokens - (int)n_common;
llama_batch batch = llama_batch_get_one(tokens.data() + n_common, n_eval);
// ⚠️ VERIFY gegen den gepinnten llama.cpp-Stand: llama_batch_get_one vergibt Positionen
//    ab dem aktuellen seq-Max automatisch, ODER es muss ein manueller llama_batch mit
//    pos[i] = n_common + i gebaut werden. In neueren llama.cpp-Ständen kann ein manuell
//    positionierter Batch nötig sein. Siehe §3.6.
if (llama_decode(lc->ctx, batch) != 0) { /* Fehlerpfad: clear + full retry */ }

lc->cached_tokens.assign(tokens.begin(), tokens.end());
```

Und am **Ende** von `run_generate` (`:960`): den finalen `llama_memory_clear` **entfernen** (der State
soll ja residieren). Die vom Modell generierten Tokens gehören *nicht* in `cached_tokens` (der nächste
Prompt enthält die Assistant-Antwort ohnehin re-gerendert über das Template) — es sei denn du willst
auch die Generierung im KV halten (siehe §3.6, „Generierte Tokens resident halten").

### 3.4 Konversations-Identität (Pflicht — sonst Cross-Leak)

Es gibt **einen** KV für Chat-UI, Agent und Server. Ein LCP gegen den falschen residenten Stand wäre
zufällig kurz (kein Schaden, nur kein Nutzen) — **aber** wenn zwei Konversationen zufällig denselben
System-Prompt teilen, würde Konversation B den KV von A „erben" bis zum Divergenzpunkt. Das ist meist
sogar *korrekt* (gemeinsamer System-Prompt), aber um Überraschungen zu vermeiden:

- `nativeGenerateChat` bekommt einen zusätzlichen Parameter `conversationId: Long` (oder String-Hash).
- `LlamaContext` merkt sich `cached_conversation_id`. Bei Wechsel → `cached_tokens.clear()` + `n_common=0`
  (voller Prefill), damit LCP nur *innerhalb* derselben Konversation greift.
- Alternativ (sauberer, aber teurer): pro Konversation eine eigene `seq_id` im selben Context. Das
  vervielfacht aber KV-RAM → für ein 3B-Handy **nicht** empfohlen. Single-Seq + Identity-Reset ist richtig.

### 3.5 Front-Truncation reparieren (llama_jni.cpp `:782–792`)

Die heutige Front-Truncation ist mit Präfix-Reuse **inkompatibel**: sie wirft die vordersten Tokens
(inkl. System-Prompt) weg, wodurch sich alle Positionen verschieben und der Cache wertlos wird.

- **Phase 1 (correctness-first):** Wenn `n_tokens > max_prompt_tokens` → Cache **invalidieren**
  (`cached_tokens.clear()`, `llama_memory_clear`) und den heutigen Front-Truncation-Pfad fahren.
  Kein Reuse in diesem Turn, aber korrekt. Loggen (`LOGW`), damit man sieht wie oft es passiert.
- **Phase 3 (richtig):** System-Präfix **pinnen** und stattdessen die ältesten *mittleren* Turns
  evicten (`llama_memory_seq_rm` auf einen Mittelbereich) + Positionen der nachfolgenden Tokens per
  `llama_memory_seq_add` nach vorn schieben (Context-Shift / StreamingLLM-Muster). Aufwändig — erst
  wenn Hebel D (Kompaktierung) es nicht ohnehin überflüssig macht.

### 3.6 Zwei Detailrisiken (unbedingt auf echtem Gerät verifizieren)

1. **Positionen bei `llama_batch_get_one`.** Ob der Batch automatisch ab `n_common` positioniert oder
   bei 0 startet, hängt vom llama.cpp-Stand ab. Falls falsch → manuell `llama_batch_init` mit
   `batch.pos[i] = n_common + i`, `batch.seq_id[i][0] = 0`, `batch.logits[last] = true`. **Einen
   Unit-/Instrumententest schreiben, der Reuse gegen Full-Prefill auf identisches erstes Logit prüft.**
2. **Generierte Tokens resident halten (optional, +Gewinn).** Wenn du auch die Assistant-Generierung
   im KV lässt, spart der nächste Turn zusätzlich deren Re-Prefill. Dann muss `cached_tokens` nach der
   Generierung um die *tatsächlich gesampelten* Tokens erweitert werden — und das Chat-Template muss
   beim nächsten Turn exakt dieselben Tokens für die Assistant-Nachricht rendern (Template-Determinismus
   prüfen; Sonderfälle: `<think>`-Suppression `:826`, Tool-Call-Marker). Wenn unsicher: erstmal nur
   den *Prompt*-Präfix cachen (obige Variante), das ist der Löwenanteil.

### 3.7 Erwarteter Gewinn (Phase 1)

Multi-Turn-Szenario (z. B. der neu gebaute Agent-Loop) mit System+Manifest ≈ 500 Tokens, 4 Runden, je +150 Tokens neu:
- **Heute:** Runde 1 prefillt 650, Runde 2 prefillt 800, … Runde 4 prefillt 1100 → Σ ≈ 3650 Token-Prefills.
- **Mit Reuse:** Runde 1 prefillt 650, danach je nur die ~150 neuen → Σ ≈ 650 + 3×150 ≈ 1100.
- **~70 % weniger Prefill-Arbeit** über den Loop, wachsend mit Rundenzahl und Präfixlänge. Auf CPU
  (wo Prefill spürbar Sekunden kostet) ist das der Unterschied zwischen zäh und flüssig.

---

## 4. Phase 2 — Persistenter Präfix-Cache (cross-session)

### 4.1 Idee

Den KV-State des **stabilen Präfixes** (System-Prompt + Persona + Tool-Manifest — genau dein „stable
tier" aus der Hermes-Analyse) einmal auf Platte schreiben und beim nächsten App-/Modell-Start laden.
Dann kostet der System-Prompt schon beim allerersten Turn **null** Prefill.

### 4.2 llama.cpp-APIs

llama.cpp hat native Sequenz-State-Serialisierung — **kein** eigener Tensor-Export nötig:

```cpp
// Speichern: schreibt KV-State von seq_id + die zugehörige Token-Liste in eine Datei
size_t llama_state_seq_save_file(ctx, path, seq_id, tokens, n_token_count);
// Laden: stellt KV-State wieder her, gibt die gespeicherten Tokens zurück (zur Validierung)
size_t llama_state_seq_load_file(ctx, path, dest_seq_id, tokens_out, n_cap, &n_out);
```
(Alternativ die In-Memory-Varianten `llama_state_seq_get_data`/`set_data` + eigener Store.)

Die File-Variante ist ideal, weil sie die **Tokens mitspeichert** → beim Laden kannst du prüfen, dass
das Präfix wirklich passt, bevor du es als resident markierst.

### 4.3 Neue JNI-Funktionen (llama_jni.cpp)

```cpp
// gibt true zurück, wenn nPrefixTokens KV-State erfolgreich geschrieben wurde
JNIEXPORT jboolean nativeSaveKvPrefix(ctxPtr, jstring path, jint nPrefixTokens);
// lädt Präfix-State; setzt lc->cached_tokens = geladene Tokens; false bei Mismatch/Fehler
JNIEXPORT jboolean nativeLoadKvPrefix(ctxPtr, jstring path);
```

`nativeSaveKvPrefix` schreibt `llama_state_seq_save_file(ctx, path, 0, lc->cached_tokens.data(),
nPrefixTokens)`. `nativeLoadKvPrefix` ruft `..._load_file`, füllt `lc->cached_tokens` mit den
zurückgegebenen Tokens und setzt den residenten Stand — der nächste `generateChat` findet dann per LCP
(Phase 1) sofort den vollen System-Präfix vor.

### 4.4 Cache-Key & Store (Kotlin)

Der Cache-Key muss **jede** Größe umfassen, die die KV-Bytes bestimmt — sonst lädst du inkompatiblen
State (Absturz/Garbage):

```
key = sha256(
   modelFileHash          // welches GGUF (Gewichte)
 + normalizedContextParams // n_ctx, type_k, type_v, flash_attn, n_gpu_layers>0 ? "gpu":"cpu"
 + chatTemplateId          // Template-Version (Jinja) — ändert die Präfix-Tokens
 + prefixText             // System-Prompt + Persona + Tool-Manifest, exakt
)
```

**Kritisch, AIpaca-spezifisch:** `type_v` unterscheidet sich zwischen CPU- (Q8_0) und GPU-Pfad (F16)
(`llama_jni.cpp:386–395`). Ein auf CPU geschriebener Cache ist auf dem GPU-Pfad **nicht** ladbar →
`gpu_offload` gehört zwingend in den Key. Beim Laden zusätzlich die von `..._load_file` zurückgegebenen
Tokens gegen die erwarteten Präfix-Tokens vergleichen; bei Abweichung verwerfen.

**Store:** kleine Tabelle (Room oder simples Verzeichnis) `~/files/kv_cache/<key>.bin` + Metadaten
(Key, Bytes, `lastUsedAt`, `nPrefixTokens`). LRU-Eviction über ein RAM-Budget (z. B. 3 Einträge /
Deckel 150 MB). Passt zu deinem bestehenden `*Prefs`/`Store`-Muster (`ChatConversationStore` etc.).

### 4.5 Wo einhängen

- `EngineState.loadModel` (`EngineState.kt:148`): nach erfolgreichem Load prüfen, ob für den aktuellen
  (Modell + Präfix-Text)-Key eine Cache-Datei existiert → `nativeLoadKvPrefix`. Sonst: beim ersten
  Turn den System-Präfix einmal prefillen und via `nativeSaveKvPrefix` schreiben.
- **Präfix-Text-Quelle bewusst offen halten:** Der stabile Präfix (System-Prompt + ggf. Persona +
  Tool-Manifest) kommt von *irgendeinem* Konsumenten — heute der Chat-System-Prompt, später der neu
  gebaute Agent-Mode. Der KV-Layer nimmt nur einen `String prefixText` + `conversationId` entgegen und
  kennt weder `AgentConfig` noch `renderSystemPrompt`. So überlebt Hebel F den Agent-Rewrite unverändert.

### 4.6 Größenbudget (aus `kv_calc.py`)

Qwen2.5-3B, q8_0-KV ≈ **19 KB/Token**. Ein 512-Token-Präfix ≈ **~10 MB** auf Platte — trivial. Selbst
ein 2000-Token-Präfix ≈ ~38 MB. Laden = Datei-Read + `set_data` (Millisekunden) statt Prefill-Sekunden.
Deckel 150 MB reicht für mehrere Personas. (Auf dem GPU-Pfad mit F16-V entsprechend ~1.5×.)

---

## 5. Phase 3 — Block-Hash-Radix-Cache (optional, später)

Verallgemeinerung von „ein Präfix" auf „viele Präfixe, block-granular" — das vLLM/SGLang-Muster:

- Präfix in feste Blöcke (z. B. 128 Tokens) schneiden; pro Block ein **verketteter** Hash
  `h_k = sha256(h_{k-1} ‖ block_k)` (der verkettete Hash beweist Präfix-Gleichheit, siehe Prefix-Baum-Doku).
- Store = Map `blockHash → KV-Blob`; Lookup = längster übereinstimmender Block-Lauf.
- LRU-Eviction, on-disk quantisiert.
- **Nur bauen, wenn** mehrere sich überlappende Präfixe real vorkommen (mehrere Personas/Docs). Für den
  Agent-MVP ist Phase 2 (ein stabiler System-Präfix) ausreichend. Ehrlich kennzeichnen, nicht spekulativ bauen.

---

## 6. Betroffene / neue Dateien (Übersicht)

```
app/src/main/cpp/llama_jni.cpp
  · LlamaContext: + cached_tokens, cached_cfg_hash, cached_conversation_id, prefix_reuse_enabled
  · run_generate: clear→LCP-Trim ersetzen (:801/:960); Front-Truncation invalidiert Cache (:782)
  · nativeGenerateChat/…WithImage: + jlong conversationId Param
  · NEU: nativeSaveKvPrefix, nativeLoadKvPrefix, nativeSetPrefixReuseEnabled

app/src/main/kotlin/com/aipaca/app/engine/InferenceEngine.kt
  · generateChat(...): + conversationId + prefixReuse Flag (default an)
  · NEU: suspend fun saveKvPrefix(key, nPrefixTokens): Boolean / loadKvPrefix(key): Boolean

app/src/main/kotlin/com/aipaca/app/engine/LlamaCppEngine.kt
  · external fun nativeSaveKvPrefix/nativeLoadKvPrefix/nativeSetPrefixReuseEnabled
  · conversationId durch generateChat schleifen

app/src/main/kotlin/com/aipaca/app/engine/PrefixCacheStore.kt   (NEU)
  · Key-Berechnung (§4.4), LRU-Datei-Store, Metadaten (Room oder Verzeichnis)

app/src/main/kotlin/com/aipaca/app/EngineState.kt
  · loadModel(): Präfix-Cache-Restore-Hook (§4.5); Feature-Flag durchreichen

(künftiger Agent-Mode — NICHT der heutige, kaputte `agent/*`-Stand)
  · muss nur die zwei Verträge erfüllen: stabile conversationId + stabiler System-Präfix-Text
  · KEINE KV-Logik im Agent-Code — die lebt in der Engine-Schicht
```

**Nicht** berührt: `server/ApiServer.kt` öffentliches Format. Der Server profitiert automatisch (er
ruft dieselbe Engine) — nur eine stabile `conversationId` pro API-Session durchreichen. Ebenso **nicht**
berührt / **nicht** vorausgesetzt: der heutige `agent/*`-Code (wird ersetzt, siehe §11).

---

## 7. Randbedingungen & Wechselwirkungen (AIpaca-spezifisch)

1. **Hebel A (KV-Quant) ist Voraussetzung, kein Konflikt.** `type_k/type_v` bestimmen die KV-Bytes →
   gehören in den Cache-Key (§4.4). Persistierter State ist quant-spezifisch.
2. **Adreno/OpenCL-Pfad zuerst verifizieren.** `llama_memory_seq_rm` und `state_seq_*` sind
   backend-agnostisch definiert, aber auf dem OpenCL-Backend **nicht** verifiziert (dieselbe Vorsicht
   wie beim Flash-Attn-/Whisper-Bug, `lab_journal.md` 2026-06-05). → Phase 1 zuerst auf dem **CPU-Pfad**
   aktivieren (Feature-Flag `prefix_reuse_enabled` an GPU-Status koppeln), GPU-Pfad erst nach
   Geräte-Test freischalten.
3. **Serialisierung ist schon da.** Alles läuft unter `EngineState.generateMutex` → kein paralleler
   Zugriff auf `cached_tokens`/KV. Der neue residente State macht den Mutex aber *semantisch wichtiger*:
   nie zwei Konversationen ohne Identity-Reset dazwischen.
4. **Effektiver Kontext vs. nominell** (Kurzbericht §3): Reuse ändert nichts an der „lost in the
   middle"-Grenze — es macht denselben Kontext nur billiger. Hebel D (Kompaktierung) bleibt komplementär.
5. **mmap ist aus** (`:305` `use_mmap=false`) — irrelevant für KV-State, aber beim Peak-RAM mitdenken.

---

## 8. Testplan & Akzeptanzkriterien

**Korrektheit (blockierend):**
- [ ] **Golden-Test Reuse == Full-Prefill:** Für einen festen Prompt liefert der Reuse-Pfad exakt
      dasselbe erste Logit / dieselbe greedy-Sequenz wie `llama_memory_clear`+Full-Prefill. (Fängt das
      Positions-Risiko §3.6 ab.)
- [ ] **Divergenz-Test:** Zwei Turns mit gemeinsamem Präfix + unterschiedlichem Suffix → `n_common`
      stimmt, Ausgabe korrekt.
- [ ] **Identity-Reset:** Konversationswechsel invalidiert den residenten Stand (kein Cross-Leak).
- [ ] **Truncation-Fallback:** Prompt > `max_prompt_tokens` → sauberer Full-Prefill, kein Crash.
- [ ] **Persistenz-Roundtrip:** save→unload→load→generate liefert dieselbe Ausgabe; Key-Mismatch
      (anderer ctx/quant/Template) wird verworfen, nicht geladen.

**Performance (Ziel):**
- [ ] Agent-Loop 4 Runden: Summe der Prefill-ms (`:809` Log) **≥ 50 %** niedriger als Baseline.
- [ ] Erster Turn nach Persistenz-Restore: System-Präfix-Prefill ≈ 0 ms.
- [ ] Kein RAM-Regress (Reuse fügt nur `cached_tokens` als int-Liste hinzu; Disk-Cache LRU-gedeckelt).

**Gerät:**
- [ ] Auf echtem Snapdragon/Adreno: CPU-Pfad grün, dann GPU-Pfad separat verifizieren (seq_rm/state_seq).

---

## 9. Sequenz & Aufwand (Schätzung)

| Schritt | Inhalt | Aufwand | Risiko |
|---|---|---|---|
| **F0** | Feature-Flag + Prefill-Instrumentierung/Logging schärfen; Golden-Test-Harness | 0.5 d | niedrig |
| **F1a** | In-Session LCP-Reuse (CPU-Pfad), Truncation-Fallback, Identity-Reset | 1–2 d | **mittel** (Positions-API §3.6) |
| **F1b** | Auf echtem Gerät verifizieren, GPU-Pfad freischalten falls grün | 0.5–1 d | mittel (Adreno) |
| **F2** | `PrefixCacheStore` + `nativeSaveKvPrefix/Load` + loadModel-Hook | 1.5–2 d | niedrig |
| **F3** | Block-Hash-Radix (nur bei Bedarf) | 3–5 d | — |

**Empfohlener Schnitt für den ersten PR:** F0 + F1a hinter Flag, CPU-only, mit Golden-Test. Das ist der
eigenständige, messbare Gewinn. F2 als zweiter PR. F3 nur, wenn mehrere Präfixe real gebraucht werden.

---

## 10. Non-Goals

- ❌ Kein Multi-Sequence-KV (eine seq_id pro Konversation) — vervierfacht KV-RAM, falsch fürs Handy.
- ❌ Kein fuzzy/„ähnlichstes Präfix" — Reuse ist immer **exaktes** längstes Token-Präfix (sonst korrupt).
- ❌ Keine Cloud-Sync des KV-Cache — rein on-device, gerät- und modellspezifisch.
- ❌ Kein Ersatz für Hebel A/D — Reuse spart Rechenzeit, nicht KV-RAM; Kompaktierung bleibt nötig.

---

## 11. Verhältnis zum bestehenden (kaputten) Agent-Mode

Der heutige `com.aipaca.app.agent.*` ist ein Brick-1-Skelett aus #43 und **kein tragfähiges Fundament**.
Beobachtete Schwächen (aus dem Code, soweit gelesen):

- **Native Tool-Calls nicht verdrahtet.** `format_chat_with_common` (`llama_jni.cpp:673–695`) setzt
  `inputs.tools` **nie** — das Jinja-Template bekommt die Tool-Schemata also gar nicht. Der im Spec
  (#43 §6.3) als „bevorzugt" markierte native Pfad (Qwen→Hermes-2-Pro, Mistral-v0.3→Nemo) ist tot;
  faktisch läuft alles über heuristisches Kotlin-Parsing (`ToolCallParser`) — fragil bei 3B-Modellen.
- **Rohe Tool-Result-Rückführung.** `AgentLoop` hängt Ergebnisse als `ChatTurn("user", "Tool result …")`
  an — keine echten Tool-Rollen, kein strukturiertes Format, das das Modell-Template erwartet.
- **Kontext zu klein.** Default 1024 (`EngineState.kt:103`) läuft mit Manifest + 1–2 Tool-Runden über
  (Hebel B/C aus dem Kurzbericht sind im Agent-Pfad noch nicht angewandt).
- Nicht-streamendes Einsammeln + nachträgliches Parsen; wächst append-only (Hebel D fehlt).

**Konsequenz für Hebel F:** bewusst **entkoppeln** (siehe Kasten in §0). Der KV-Prefix-Cache wird nicht
in `AgentLoop` eingebaut, sondern in die Engine-Schicht, und stellt an den (neu zu bauenden) Agent-Mode
nur die zwei Verträge *stabile conversationId* + *stabiler System-Präfix*. Egal wie der Agent-Rewrite
aussieht (native Tool-Calls via `inputs.tools`, echte Tool-Rollen, Kompaktierung/Hebel D) — Hebel F
funktioniert unverändert, weil er eine Schicht tiefer sitzt.

> Offen (Bridge war beim Verfassen kurz getrennt): `AgentConfig.kt`, `AgentSession.kt`,
> `ToolCallParser.kt` und `agent/mcp/*` wurden **nicht vollständig** gelesen. Vor dem Agent-Rewrite
> lohnt ein separater, ehrlicher Review dieser Dateien — dieser Plan setzt keine davon voraus.

---

### Anhang — Warum das der „Hermes-Schlussstein" ist

Hermes hält seinen Cloud-KV-Cache über einen bytestabilen Präfix warm (System-Prompt-Tier + Datums-
statt Minuten-Timestamp + Frozen-Memory). On-device besitzt AIpaca den KV-Cache selbst — Phase 1+2
setzen genau dieses Prinzip um, nur *stärker*: nicht nur „Präfix stabil halten und hoffen, dass der
Provider cached", sondern den State direkt behalten, trimmen und persistieren. Der Agent-Loop (#43) ist
das On-Device-Pendant zu Hermes' Turn-Schleife — und dort zahlt sich Warm-KV am unmittelbarsten aus.

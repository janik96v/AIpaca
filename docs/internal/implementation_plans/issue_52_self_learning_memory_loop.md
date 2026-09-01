# Issue #52 — Self-Learning Loop on Memory, `user.md` and `soul.md`

> Status: **planning**. Baseline for this plan is branch `feature/agent-memory-skills`
> (commit `8a0d4b7`), **not** `main` — the whole memory subsystem lives there and is
> not yet merged / not yet in a PR.

## 1. What the issue asks for

1. An **automated updating loop** for `user.md` — the LLM collects new information about
   the user from past conversations, periodically. "Periodically" is explicitly left open.
2. A **`soul.md`** — the pendant to `user.md`, but about the *agent*: who it is, and what
   the user expects it to be and to do.
3. A **`memory.md`** — a rolling index of past conversations (one short description per
   session), so the agent does not have to read all past conversations. Explicitly
   modelled after **progressive disclosure of skills**: load the index first, load the
   full item only when relevant.

---

## 2. What already exists in the code

Everything below is on `feature/agent-memory-skills`. Increments 1–4 of
[`agent_memory_skills.md`](agent_memory_skills.md) are implemented.

| Component | File | What it does today |
|---|---|---|
| Memory files | `agent/memory/MemoryStore.kt` | `agent_memory.md` (2200 chars) + `agent_user.md` (1375 chars) in `filesDir/agent_memory/`. Entries separated by `§`. `add` / `replace` / `remove`. FIFO trim when over limit. |
| Memory tool | `agent/memory/MemoryTool.kt` | Exposes `memory(action, file, text, replacement)` to the model. Runs the anti-poisoning filter on `add`/`replace`. |
| Poison filter | `agent/memory/AntiPoisoning.kt` | 6 regexes rejecting transient errors / negative claims before any write. |
| Prompt assembly | `agent/AgentConfig.kt` | `renderSystemPrompt()` builds `persona` + `systemPrompt` + `## About the User` + `## Remembered Context` + `## Your Learned Skills`. **Frozen snapshot**: read once at session start, writes only visible next session (KV-cache friendly). |
| Skills | `agent/memory/Skill.kt`, `SkillStore.kt`, `SkillTools.kt` | `agent_skills/<name>.md` with YAML frontmatter. Index (name + ≤60-char description) in the prompt, body loaded on demand via `skill_view`. Max 50 skills. **This is exactly the progressive-disclosure mechanism the issue wants for `memory.md`.** |
| Learn pass | `agent/memory/LearnPass.kt` | Counter-triggered review pass after a turn. `ReviewType.MEMORY / SKILL / BOTH`, restricted tool registry (only `memory` + `skill_manage`), max 4 tool rounds, digest = last 8 messages, `maxTokens = 512`, thinking off. |
| Counters | `data/AgentPrefs.kt` | `iters_since_skill`, `turns_since_memory` persisted in `EncryptedSharedPreferences`. Thresholds both 10. |
| Trigger wiring | `ui/chat/ChatScreen.kt:492-534` | After the agent turn: counters += , threshold check, `LearnPass.run(...)` on `Dispatchers.Default` in `viewModelScope`. |
| Raw archive | `data/MessageDatabase.kt` | Room + FTS5 virtual table with sync triggers, BM25 ranking. |
| Recall tool | `agent/memory/SessionSearchTool.kt` | `session_search(query)` → bookend rendering (first 5 msgs + match windows + last 5 msgs) per session, capped at 6000 chars. Zero LLM calls. |

**So roughly 60 % of issue #52 is already built.** What is missing is the *loop* part
(it barely ever fires), `soul.md`, and `memory.md`.

### 2.1 Gaps and defects found while reading

These are concrete, verifiable problems — they should be fixed as part of this issue,
otherwise the "self-learning loop" is dead code in practice.

| # | Problem | Location | Impact |
|---|---|---|---|
| G1 | The learn pass only runs on the **agent path**, and that path `return@launch`s early when `TavilyMcp.buildClient()` returns `null` (no API key / agent not enabled). | `ChatScreen.kt:385-391, 492` | Without a Tavily key the agent **never learns anything**. Normal chat — the majority of usage — is invisible to memory. |
| G2 | `LearnPass.run(engine: LlamaCppEngine)` is hardcoded to the local engine; `ollamaEngine` is never passed. | `LearnPass.kt:73-141` | With the Ollama backend active and no local GGUF loaded, the review pass fails (`isModelLoaded` false) or forces an unwanted on-device load. |
| G3 | FTS indexing (`indexCurrentConversationToFts()`) only happens in the agent path's `finally`. | `ChatScreen.kt:538` | `session_search` is blind to every normal chat after the one-time initial migration. |
| G4 | Both counters are reset to 0 even when only one review type ran. | `ChatScreen.kt:526-527` | A `MEMORY`-only pass resets the skill counter → skill reviews get starved. |
| G5 | The pass runs in `viewModelScope` on the chat screen. | `ChatScreen.kt:512` | Leaving the screen / app kill mid-pass cancels it; counters were already read but not yet reset → lost work or double work. |
| G6 | `MemoryStore.trimToLimit()` drops the **oldest** entries silently, FIFO. No dedup, no merge, no timestamps, no importance. | `MemoryStore.kt:88-96` | "My name is Janik" (learned first) is the first thing evicted. No contradiction handling ("moved from Zürich to Bern" → both entries coexist). |
| G7 | `AntiPoisoning` patterns are English-only and very broad (`(broken\|unavailable\|missing\|not installed\|not found)`, `(failed to\|unable to\|...)`). | `AntiPoisoning.kt` | German user input is not filtered at all; legitimate user facts containing those words are rejected. |
| G8 | Memory files are plaintext in `filesDir`, while conversations use `EncryptedSharedPreferences` (AES256-GCM). | `MemoryStore.kt:32` | Inconsistent with the project's own privacy stance; memory is the most personal data in the app. |
| G9 | No UI at all for memory. | — | The user cannot see, edit, correct or delete what the agent believes about them. |
| G10 | No `soul.md`; `persona` is a hardcoded default string in `AgentConfig`. | `AgentConfig.kt:22` | Issue requirement 2 unfulfilled. |
| G11 | No `memory.md` / session index. | — | Issue requirement 3 unfulfilled. `session_search` is *retrieval*, not an *index* — the agent must already know what to search for. |

---

## 3. State of the art (2025 → 2026)

| Pattern | Who | Takeaway for AIpaca |
|---|---|---|
| **Sleep-time compute** — move memory work off the user-facing critical path into idle time; ~5× less test-time compute for equal accuracy, ~2.5× lower cost per query when amortized. | [Letta](https://www.letta.com/blog/sleep-time-compute/), [Agent Memory](https://www.letta.com/blog/agent-memory/) | This *is* issue #52. On a phone the argument is even stronger: NPU/GPU is single-tenant, thermals and battery matter → do it while charging and idle. |
| **Dreaming** — asynchronous, between-session memory curation: take the existing memory store + up to 100 prior session transcripts, produce a *reorganized* store where duplicates are merged, stale/contradicted entries replaced, patterns surfaced; the result is proposed for **approve / reject / modify** before deployment. | Anthropic Claude Managed Agents, May 2026 ([The New Stack](https://thenewstack.io/anthropic-agent-memory-dreaming/), [MindStudio](https://www.mindstudio.ai/blog/what-is-claude-dreaming-anthropic-managed-agents)) | The closest blueprint. Two things to copy verbatim: **(a) consolidation is a separate pass over the whole store, not an append-only tool call**, and **(b) human-in-the-loop review of the diff.** |
| **Extract → decide `ADD` / `UPDATE` / `DELETE` / `NOOP`** against existing memories instead of blind append. | [Mem0](https://mem0.ai/blog/state-of-ai-agent-memory-2026) (91.6 LoCoMo, 94.8 LongMemEval) | Directly fixes G6. The current `memory` tool has `add/replace/remove` — the *tool* is fine; the *prompt* never asks the model to decide between them against the current file. |
| **Bi-temporal knowledge graph** — facts get validity intervals and are *invalidated*, not deleted. | [Zep / Graphiti](https://blog.devgenius.io/ai-agent-memory-systems-in-2026-mem0-zep-hindsight-memvid-and-everything-in-between-compared-96e35b818da8) | Full graph is overkill on-device. Cheap 80 % version: a timestamp per entry + "supersedes" semantics in the consolidation prompt. |
| **Reflection / decay** — Generative Agents' periodic reflection; MemoryBank's Ebbinghaus-style forgetting curve. | classic | Replace pure FIFO eviction with recency × access-count scoring. |
| **Progressive disclosure** — index in the prompt, body on demand (Agent Skills). | Anthropic Agent Skills, already used in `SkillStore` | Reuse the exact mechanism for `memory.md`. The issue author already identified this. |
| **Benchmarks** | LoCoMo (~300 turns, 35 sessions), LongMemEval, BEAM | Useful vocabulary, but not runnable on-device. Use a small hand-written scenario suite instead (§6). |

**What is *not* worth copying on-device (yet):** vector/embedding retrieval (no local
embedding provider yet — it's on the roadmap under "Long-Term"), and LLM-judge triggers
(a counter costs zero inference; a judge costs a full forward pass every turn).

---

## 4. What was built

### 4.1 Four memory layers

```
┌─ Prompt-resident (frozen at session start) ──────────────────────────┐
│  soul.md      who the agent is, what the user expects   ~1375 chars  │  NEW
│  user.md      facts and preferences about the user      ~1375 chars  │  extended
│  memory.md    one line per past conversation            ~1500 chars  │  NEW
│  skills/      one line per learned procedure                         │  unchanged
└──────────────────────────────────────────────────────────────────────┘
        ↓ on demand (tool call)
┌─ Load-on-demand ─────────────────────────────────────────────────────┐
│  session_view(id)        full transcript of one indexed session       │  NEW
│  skill_view(name)        full procedure                               │  unchanged
│  session_search(query)   FTS5 BM25 over every message                 │  unchanged
└──────────────────────────────────────────────────────────────────────┘
```

Entries are now date-stamped (`§ 2026-09-01 | text`, see `MemoryEntry.kt`). That single
change is what makes contradiction handling decidable without a temporal knowledge
graph — the consolidation prompt can say "keep the newer one" and the model can act
on it. Legacy undated entries parse as oldest, so no migration is needed.

`soul.md` is seeded on first launch and is **not** writable from the hot path. A 3B
model that can rewrite its own persona mid-conversation will do so, and the change is
global and invisible; soul edits come only from the consolidation pass and always land
as a proposal the user approves.

### 4.2 Three loops

| Loop | Trigger | Cost | Writes |
|---|---|---|---|
| **L1** hot path | model calls the `memory` tool during a turn | 0 extra | `user.md`, `memory.md` facts |
| **L1.5** extraction | 10 user turns (`LearnPass`) | ~256 tok | `user.md`, `memory.md` |
| **L1.5b** skill review | 10 tool iterations, DEEP tier only (`SkillReviewPass`) | ~512 tok | `skills/` |
| **L2** session note | conversation left, ≥ 4 messages, at most once/day (`SessionSummarizer`) | ~96 tok | `memory.md` index |
| **L3** consolidation | every 24 h **and** ≥ 5 new entries **and** charging + idle + battery ok + not thermally throttled (`MemoryMaintenanceWorker`) | ~512 tok × 3 | rewrites all three files |
| **L3** manual | "Update memory now" on the Memory screen | same | same |

An interval alone either burns battery re-reading an unchanged file or lets a heavily
used one rot for weeks, so L3 needs both a schedule and a change threshold — the same
shape Letta's sleep-time agents and Anthropic's dreaming use.

### 4.3 The passes do not use tool calling

`LearnPass`, `SessionSummarizer` and `ConsolidationPass` ask for line-structured text
and parse it deterministically (`MemoryExtraction`, `MemoryConsolidation`) instead of
driving the tool-calling orchestrator. Three reasons:

1. They then work on models with **no tool-calling template at all**, which is most of
   what the in-app HuggingFace browser can download.
2. Small models are markedly more reliable at emitting four constrained lines than at
   emitting well-formed tool calls — the failure mode disappears rather than being
   handled.
3. They run through plain `generateChat`, which both the local and the Ollama backend
   implement, so **G2 is fixed structurally**: the pass no longer cares which engine is
   active. `MemoryEngine` is the one-method abstraction that makes this injectable and
   testable with a fake.

The `memory` tool stays for L1 hot-path writes, and the skill review stays on the
tool-calling path (a skill body is multi-paragraph markdown, exactly the shape that
line-structured output handles badly) — gated on the DEEP tier so it only runs where
tool calls actually work.

### 4.4 Consolidation semantics

`MemoryConsolidation` implements Mem0's ADD/UPDATE/DELETE/NOOP decision as four commands
(`ADD`, `EDIT`, `DROP`, `MERGE`) over a numbered list. Two properties matter more than
the command set:

- **Entries the model does not mention survive untouched.** There is no `KEEP`
  requirement, so a truncated or lazy answer can never wipe a file.
- **A pass that would remove more than 30 % of a file is staged, not applied**, and so
  is every soul edit. Staged versions surface on the Memory screen with Approve /
  Reject, mirroring the approve/reject/modify step in Anthropic's dreaming. Every run
  snapshots first, and the Memory screen has an "Undo last change".

---

## 5. No agent button: tiered execution

The `agentMode` toggle is gone. It never meant "agentic" anyway — it required a Tavily
key and silently did nothing without one, so it really meant "web search on/off". What
is left in the Modes menu is exactly that one honest decision: whether queries may leave
the device.

What replaces it is `TierPolicy` (`agent/AgentTier.kt`), which derives the two numbers
that actually decide whether a small model succeeds:

| Tier | Tools in prompt | Max rounds | Chosen when |
|---|---|---|---|
| **PLAIN** | none | 1 | image attached, no tool template, or context < 4096 |
| **ASSISTED** | ≤ 3 (`memory`, `session_search`, web search if configured) | 2 | context 4096–8191 |
| **DEEP** | ≤ 6 (adds `session_view`, `skill_view`, `skill_manage`) | 6 | context ≥ 8192, or the Ollama backend |

Supporting changes:

- **`nativeProbeToolSupport`** (`llama_jni.cpp`) applies the model's Jinja template once
  with a throwaway tool and reports whether the tool's name survives into the rendered
  prompt. Checking the rendered text rather than the chat-format enum keeps the probe
  independent of llama.cpp's enum naming across upstream revisions — and every identifier
  it uses already appears elsewhere in the same file, which matters because the C++ could
  not be compiled here. Cheap (template application only, no tokenization, no decode) and
  cached per loaded model. Without it an
  agent-only app fails *silently* on every GGUF whose template ignores tools. On any
  native failure the Kotlin side reports `true`, i.e. the pre-probe behaviour, so a
  missing symbol degrades to "attempt tools" rather than "lose tools".
- **Graceful degradation** in `AgentOrchestrator`: after two malformed tool calls in one
  turn the tools are dropped and the model is asked to answer plainly. A plain answer
  beats the "Agent error" the user used to get.
- **Web search is additive.** If it is not configured, or the MCP server cannot be
  reached, the turn continues with the local tools. Tying the loop to a Tavily key is
  what made memory unreachable in the first place (**G1**).
- `generateChat` is untouched: the OpenAI-compatible server (`ApiServer.kt:276-278`) and
  the vision path both depend on it, and architecture constraint #4 keeps `tools` out of
  the public endpoint.

---

## 6. File map

| File | Action | Purpose |
|---|---|---|
| `agent/AgentTier.kt` | **new** | tier policy, round and tool budgets |
| `agent/memory/MemoryEntry.kt` | **new** | dated `§` entry format, parse/render/trim |
| `agent/memory/MemoryEngine.kt` | **new** | backend-agnostic one-shot completion |
| `agent/memory/MemoryExtraction.kt` | **new** | L1.5 prompt + parser + duplicate filter |
| `agent/memory/MemoryConsolidation.kt` | **new** | L3 prompt, command parser, applier |
| `agent/memory/SessionIndexStore.kt` | **new** | `memory.md` session index + eviction |
| `agent/memory/SessionViewTool.kt` | **new** | `session_view` progressive disclosure |
| `agent/memory/SessionSummarizer.kt` | **new** | L2 one-line summary |
| `agent/memory/ConsolidationPass.kt` | **new** | L3 orchestration, staging, snapshots |
| `agent/memory/SkillReviewPass.kt` | **new** | skill review, split out of the old LearnPass |
| `work/MemoryMaintenanceWorker.kt` | **new** | idle/charging/thermal-gated L3 |
| `work/MemoryMaintenance.kt` | **new** | scheduling + manual trigger |
| `ui/memory/MemoryViewModel.kt` | **new** | Memory screen state, approvals, undo |
| `ui/memory/MemoryScreen.kt` | **new** | four tabs, editor, pending diff, run-now |
| `ui/chat/ChatViewModel.kt` | **new** | extracted from `ChatScreen.kt`, rewired |
| `agent/memory/MemoryStore.kt` | rewrite | soul file, dates, backups, pending changes |
| `agent/memory/LearnPass.kt` | rewrite | text-mode, backend-agnostic, memory only |
| `agent/memory/AntiPoisoning.kt` | rewrite | hard/soft tiers, German patterns |
| `agent/memory/MemoryTool.kt` | modify | soul gating via `allowedFiles` |
| `agent/AgentConfig.kt` | modify | soul + session-index prompt layers |
| `agent/AgentOrchestrator.kt` | modify | degrade to a tool-free answer |
| `engine/LlamaCppEngine.kt`, `cpp/llama_jni.cpp` | modify | tool-support probe |
| `EngineState.kt` | modify | capability flag, shared stores, memory engine |
| `data/AgentPrefs.kt` | modify | loop switch, consolidation counters |
| `data/MessageDatabase.kt` | modify | `sessionIds()`, `deleteSession()` |
| `ui/chat/ChatScreen.kt` | modify | no agent toggle; web-search entry only |
| `ui/components/AlpacaBottomNav.kt`, `ui/MainActivity.kt` | modify | Memory tab |

---

## 7. Verification

### What was checked

- **112 JVM unit tests, all passing**, covering the pure logic: entry format and
  round-tripping, store writes/eviction/backups/pending changes, session index encoding
  and score-based eviction, the anti-poisoning tiers (including the German cases and the
  false positives the old regexes produced), extraction parsing and duplicate filtering,
  every consolidation command plus the "unmentioned entries survive" and approval-
  threshold rules, `session_view` rendering, and the whole tier policy.
- **Type-checking of every non-Compose source** against the real Kotlin 2.0.21 compiler.

### What could not be checked here, and why

Google's Maven repository is unreachable from this environment, so the Android Gradle
Plugin cannot be resolved and **no real `./gradlew assembleDebug` was run**. The
llama.cpp submodule is not checked out either, so the C++ was not compiled. What was
done instead: a standalone JVM Gradle project type-checks every source outside
`ui/**`'s Compose files and `server/**` against hand-written Android stubs, and runs the
unit tests. To make the highest-risk file checkable at all, `ChatViewModel` was
extracted out of `ChatScreen.kt` (which is also a worthwhile split on its own — that
file was 1700 lines).

**Consequently these need a real build and a device run:**

1. `llama_jni.cpp` — `nativeProbeToolSupport` compiles and links. If the symbol is
   missing at runtime the Kotlin side catches the `UnsatisfiedLinkError` and assumes
   tool support, so the app still works — but the tier decision silently loses its
   input. Check logcat for `nativeProbeToolSupport:` and `tool calling supported:` on
   model load.
2. `MemoryScreen.kt` and the `ChatScreen.kt` edits — Compose could not be compiled here.
3. The WorkManager wiring — API usage was written against stubs mirroring the 2.9.1
   surface.

### Suggested manual run

1. Load a Qwen2.5-3B GGUF. Logcat should show `tool calling supported: true` and
   `turn tier=DEEP`.
2. Say "My name is Janik and I prefer Kotlin". Chat for ten turns, then check
   Memory → User.
3. Memory → "Update memory now", then watch `MemoryMaintenance` in logcat.
4. Add a contradicting fact ("I moved to Bern"), run the update again, confirm the older
   entry is dropped rather than kept alongside.
5. Memory → Soul: any proposed change must appear as a pending version, never applied.
6. Load a model without a tool template (e.g. Gemma-2-2B): tier should fall to
   `PLAIN`, chat should behave exactly as before, and extraction should still run.

---

## 8. Deliberately not done

- **Encrypting the memory files (G8).** Memory is the most personal data in the app and
  it still sits in plaintext under `filesDir` while conversations use
  `EncryptedSharedPreferences`. `EncryptedFile` has awkward overwrite semantics and a
  real risk of a crash loop, and none of it could be exercised here — shipping untested
  crypto plus a one-time migration blind was the wrong trade. It should be its own
  change, with the migration tested on a device.
- **Automatic skill extraction on non-tool-capable models.** Skill bodies don't fit the
  line-structured format the other passes use, so on PLAIN/ASSISTED tiers skills are
  only created when the model calls `skill_manage` itself.
- **A foreground notification for the consolidation worker.** It would need
  `POST_NOTIFICATIONS` handling and a foreground service type; the pass runs while the
  device is idle and charging, where a notification adds little.

---

## 9. References

- [Letta — Sleep-time compute](https://www.letta.com/blog/sleep-time-compute/) ·
  [Letta — Agent memory](https://www.letta.com/blog/agent-memory/)
- [The New Stack — Anthropic agent memory "dreaming"](https://thenewstack.io/anthropic-agent-memory-dreaming/) ·
  [MindStudio — What is Claude Dreaming](https://www.mindstudio.ai/blog/what-is-claude-dreaming-anthropic-managed-agents)
- [Mem0 — State of AI agent memory 2026](https://mem0.ai/blog/state-of-ai-agent-memory-2026)
- [Berkeley Function Calling Leaderboard](https://openreview.net/pdf?id=2GmDdhBdDk)
- Internal: [`agent_memory_skills.md`](agent_memory_skills.md) ·
  [`hermes_erklaert.md`](../reference/hermes_erklaert.md) ·
  [`20_kurzbericht_edge_kontext.md`](../research/20_kurzbericht_edge_kontext.md) ·
  [`roadmap.md`](../../roadmap.md)

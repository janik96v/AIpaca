# Agent Memory & Skills System for AIpaca

> **Status: IMPLEMENTED** (`6a0c2f8`, `06b4976`); superseded by [docs/capabilities.md](../../capabilities.md) §5. Kept for history.

## Context

Hermes Agent feels "human" because it writes itself notes (MEMORY.md, USER.md) and reusable procedures (Skills) that persist across sessions. No training, no RL — pure filesystem + prompt engineering. This plan ports that pattern to on-device Android. Limits match Hermes defaults — even edge models increasingly support 32k-128k contexts, and Hermes's limits are already conservative by design (curated summaries, not logs).

The system has four increments, each independently useful:
1. **File-Memory with frozen snapshot** — the agent remembers facts about the user and environment
2. **File-Skills with progressive disclosure** — the agent learns reusable procedures
3. **Counter-triggered sequential learn pass** — automatic skill/memory extraction after complex tasks
4. **FTS5 cross-session recall** — search past conversations without LLM calls

### Research & References

- Hermes Agent analysis: [docs/internal/reference/hermes_erklaert.md](../../internal/reference/hermes_erklaert.md)
- Hermes source: [github.com/nousresearch/hermes-agent](https://github.com/nousresearch/hermes-agent)
- Key Hermes files: `tools/memory_tool.py`, `agent/background_review.py`, `agent/learn_prompt.py`

### Design Principles (from Hermes, adapted for edge)

- **Frozen snapshot**: Memory is loaded once at session start into the system prompt. Writes persist to disk immediately but only appear next session. This preserves the KV cache across turns.
- **Progressive disclosure**: Only a compact skill index (~60 chars per skill) lives in the prompt. Full skill bodies are loaded on demand via tool call, and pruned under context pressure.
- **Counter-based triggers**: No LLM judge decides "when to learn." Simple integer counters fire after N tool iterations — zero inference cost for the meta-decision.
- **Anti-poisoning**: Never persist transient errors, negative tool claims ("X is broken"), or one-off task narratives. Critical for small models that would blindly parrot bad memories.

---

## Increment 1: File-Memory with Frozen Snapshot

### What it does

Two bounded plaintext files stored in app-internal storage:
- `agent_memory.md` — environment facts, project conventions, corrections (max 2,200 chars / ~800 tokens)
- `agent_user.md` — user preferences, communication style, name (max 1,375 chars / ~500 tokens)

The agent reads these at session start (frozen into system prompt) and writes to them via a `memory` tool. Writes go to disk immediately but do NOT update the running session's prompt — next session sees the update.

### Step 1.1: Create MemoryStore

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/MemoryStore.kt`

```kotlin
class MemoryStore(private val context: Context) {

    companion object {
        const val MEMORY_FILE = "agent_memory.md"
        const val USER_FILE = "agent_user.md"
        const val MAX_MEMORY_CHARS = 2200   // Hermes default: ~800 tokens
        const val MAX_USER_CHARS = 1375    // Hermes default: ~500 tokens
        private const val SEPARATOR = "\n§ "
    }

    /** Read the full content of a memory file. Returns "" if not yet created. */
    fun read(file: String): String

    /** Append an entry. Trims oldest entries (FIFO) if over char limit. */
    fun add(file: String, entry: String)

    /** Replace first occurrence of [old] with [new] (substring match). */
    fun replace(file: String, old: String, new: String)

    /** Remove first occurrence of [text] (substring match). */
    fun remove(file: String, text: String)
}
```

Storage: plain files in `context.filesDir / "agent_memory/"`. Not encrypted (memory content is agent-generated, not user secrets). Entries separated by `§` (same as Hermes).

---

### Step 1.2: Create MemoryTool

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/MemoryTool.kt`

Exposes a local tool `memory` to the agent with this input schema:

```json
{
  "type": "object",
  "properties": {
    "action": { "enum": ["add", "replace", "remove"], "description": "Operation" },
    "file": { "enum": ["memory", "user"], "description": "Which file to modify" },
    "text": { "type": "string", "description": "Text to add, or text to find (for replace/remove)" },
    "replacement": { "type": "string", "description": "Replacement text (only for replace)" }
  },
  "required": ["action", "file", "text"]
}
```

Static helper object with:
- `NAME = "memory"`
- `INPUT_SCHEMA` — the JSON schema above
- `DESCRIPTION` — "Store or update persistent notes about the user and environment."
- `fun run(args: JsonObject, store: MemoryStore): ToolResult`

---

### Step 1.3: Inject frozen snapshot into system prompt

**Modify:** `AgentConfig.kt` — add optional `memorySnapshot` and `userSnapshot` parameters:

```kotlin
data class AgentConfig(
    val persona: String = "...",
    val systemPrompt: String = "...",
    val memorySnapshot: String = "",   // NEW: frozen at session start
    val userSnapshot: String = "",     // NEW: frozen at session start
    val maxToolRounds: Int = 4,
    val generateParams: GenerateParams = GenerateParams(maxTokens = 768)
)
```

**Modify:** `AgentConfig.renderSystemPrompt()`:

```kotlin
fun AgentConfig.renderSystemPrompt(tools: List<ToolSpec>): String {
    return buildString {
        append(persona)
        append("\n\n")
        append(systemPrompt)
        if (userSnapshot.isNotBlank()) {
            append("\n\n## About the User\n")
            append(userSnapshot)
        }
        if (memorySnapshot.isNotBlank()) {
            append("\n\n## Remembered Context\n")
            append(memorySnapshot)
        }
    }
}
```

The memory/user sections are appended **after** the stable prefix (persona + systemPrompt), so the stable prefix still hits the KV cache.

---

### Step 1.4: Wire memory tool in ChatScreen

**Modify:** `ChatScreen.kt` (ChatViewModel) — in the agent path (~line 370):

1. Create `MemoryStore` once in the ViewModel (lazy):
   ```kotlin
   private val memoryStore by lazy { MemoryStore(getApplication()) }
   ```

2. Before creating `AgentConfig`, read frozen snapshots:
   ```kotlin
   val memorySnap = memoryStore.read(MemoryStore.MEMORY_FILE)
   val userSnap = memoryStore.read(MemoryStore.USER_FILE)
   val agentConfig = AgentConfig(
       memorySnapshot = memorySnap,
       userSnapshot = userSnap,
       generateParams = GenerateParams(...)
   )
   ```

3. Register the memory tool as a local tool on `ToolRegistry` (requires `registerLocal` from PTC plan Step 2, or a simpler version — see note below):
   ```kotlin
   registry.registerLocal(
       ToolSpec(name = MemoryTool.NAME, description = MemoryTool.DESCRIPTION, inputSchema = MemoryTool.INPUT_SCHEMA)
   ) { args -> MemoryTool.run(args, memoryStore) }
   ```

**Note on `registerLocal`:** This uses the same `registerLocal` method planned in the PTC implementation. If PTC isn't built yet, add a minimal version of `registerLocal` now (just `LocalTool` data class + lookup in `callTool`/`manifest`/`hasTool`). The PTC plan will reuse it.

---

### Step 1.5: Add memory guidance to system prompt

**Modify:** `AgentConfig.kt` — append to `systemPrompt`:

```
You have a persistent memory tool. Use it proactively to remember:
- User preferences and corrections ("I prefer Kotlin over Java")
- Environment facts ("project uses Gradle 8.x")
- Important context the user shares about themselves
Store in 'user' file for personal preferences, 'memory' file for technical facts.
Keep entries concise (one sentence each). Do not store transient errors or one-off task details.
```

---

## Increment 2: File-Skills with Progressive Disclosure

### What it does

Skills are reusable procedures the agent has learned. Each skill is a directory under `agent_skills/` containing a `SKILL.md` with YAML frontmatter. The system prompt contains only a compact index (name + 60-char description per skill). The full body is loaded on demand via a `skill_view` tool.

### Step 2.1: Define Skill data model

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/Skill.kt`

```kotlin
@Serializable
data class Skill(
    val name: String,           // lowercase-hyphenated, max 64 chars
    val description: String,    // max 60 chars — this is what goes in the index
    val category: String = "",  // optional grouping
    val body: String = ""       // full procedure (loaded on demand, not in index)
)
```

---

### Step 2.2: Create SkillStore

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/SkillStore.kt`

```kotlin
class SkillStore(private val context: Context) {

    companion object {
        const val MAX_SKILLS = 50           // Hermes has no hard cap; 50 is practical for index size
        const val MAX_DESCRIPTION_CHARS = 60
        const val MAX_BODY_CHARS = 4000    // Hermes skills can be substantial; match their scale
    }

    /** List all skills as (name, description) pairs — the compact index. */
    fun index(): List<Pair<String, String>>

    /** Load the full body of a skill by name. Returns null if not found. */
    fun view(name: String): Skill?

    /** Create or overwrite a skill. Truncates description to 60 chars. */
    fun save(skill: Skill)

    /** Update an existing skill's body (patch). */
    fun patch(name: String, newBody: String)

    /** Delete a skill. */
    fun delete(name: String)
}
```

Storage: each skill is a file `context.filesDir/agent_skills/<name>.md` with YAML frontmatter (name, description, category) and markdown body. Parsed with a simple regex splitter, no YAML library needed:

```
---
name: kotlin-coroutines
description: How to use structured concurrency in this project
category: kotlin
---
## When to Use
...
```

---

### Step 2.3: Create SkillTools

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/SkillTools.kt`

Two local tools:

**`skill_view`** — loads a skill body on demand:
```json
{ "name": { "type": "string", "description": "Skill name from the index" } }
```
Returns the full SKILL.md body. If not found, returns error.

**`skill_manage`** — create/update/delete skills:
```json
{
  "action": { "enum": ["create", "patch", "delete"] },
  "name": { "type": "string" },
  "description": { "type": "string", "description": "Max 60 chars (for create)" },
  "body": { "type": "string", "description": "Skill procedure (for create/patch)" }
}
```

Returns confirmation or error.

---

### Step 2.4: Inject skill index into system prompt

**Modify:** `AgentConfig.kt` — add `skillIndex` parameter:

```kotlin
data class AgentConfig(
    // ... existing fields ...
    val skillIndex: String = ""     // NEW: compact skill index
)
```

**Modify:** `renderSystemPrompt()` — append after memory sections:

```kotlin
if (skillIndex.isNotBlank()) {
    append("\n\n## Your Learned Skills\n")
    append(skillIndex)
    append("\nUse skill_view(name) to load a skill's full procedure before applying it.")
}
```

---

### Step 2.5: Wire skill tools in ChatScreen

**Modify:** `ChatScreen.kt` — in the agent path, after memory tool registration:

```kotlin
private val skillStore by lazy { SkillStore(getApplication()) }
```

Build the index string and pass to AgentConfig:
```kotlin
val skillIdx = skillStore.index().joinToString("\n") { (name, desc) -> "- $name: $desc" }
val agentConfig = AgentConfig(
    memorySnapshot = memorySnap,
    userSnapshot = userSnap,
    skillIndex = skillIdx,
    generateParams = GenerateParams(...)
)
```

Register skill tools:
```kotlin
registry.registerLocal(SkillTools.viewSpec()) { args -> SkillTools.view(args, skillStore) }
registry.registerLocal(SkillTools.manageSpec()) { args -> SkillTools.manage(args, skillStore) }
```

---

### Step 2.6: Add skill guidance to system prompt

**Modify:** `AgentConfig.kt` — append to `systemPrompt`:

```
When you solve a non-trivial multi-step problem, consider saving the procedure as a skill
using skill_manage(action="create"). Before starting a task, check if a relevant skill exists
in your skill index and load it with skill_view.
Prefer: patch existing skill > create new skill. Keep descriptions under 60 characters.
```

---

## Increment 3: Counter-Triggered Sequential Learn Pass

### What it does

After a complex agent turn (measured by tool-call count, not LLM judgment), a sequential "review pass" runs that examines the conversation and decides whether to create/update skills or memory entries. Runs **after** the user gets their answer (no latency impact), **sequentially** (not parallel — single NPU), and only when the device has capacity.

### Step 3.1: Add turn counters to AgentOrchestrator

**Modify:** `AgentOrchestrator.kt`

Add two counters as constructor parameters with defaults:

```kotlin
class AgentOrchestrator(
    // ... existing params ...
    private val itersSinceSkillReview: Int = 0,   // NEW
    private val turnsSinceMemoryReview: Int = 0    // NEW
) {
    // Track tool iterations within this run
    private var toolIterationsThisRun = 0
```

In the tool-call execution section (~line 139), increment:
```kotlin
toolIterationsThisRun++
```

After `run()` completes, expose the final counts via a result:
```kotlin
data class AgentRunResult(
    val toolIterations: Int,
    val shouldReviewSkills: Boolean,   // itersSinceSkillReview + toolIterations >= threshold
    val shouldReviewMemory: Boolean    // turnsSinceMemoryReview + 1 >= threshold
)
```

Change `run()` return type to `Flow<AgentStep>` but add a new step type:

```kotlin
data class RunComplete(
    val toolIterations: Int,
    val shouldReviewSkills: Boolean,
    val shouldReviewMemory: Boolean
) : AgentStep
```

Emit `RunComplete` as the last step before the flow ends.

---

### Step 3.2: Create LearnPass

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/LearnPass.kt`

```kotlin
object LearnPass {

    const val SKILL_REVIEW_THRESHOLD = 10   // Hermes default: 10 tool iterations
    const val MEMORY_REVIEW_THRESHOLD = 10  // Hermes default: 10 user turns

    /**
     * Runs a sequential review pass after a complex agent turn.
     * Uses the same model but with a restricted tool set (only memory + skill_manage).
     *
     * @param digest Last N messages from the conversation (not full replay — saves context)
     * @param memoryStore For memory tool access
     * @param skillStore For skill tool access
     * @param engine For inference
     * @param generateMutex Shared lock
     */
    suspend fun run(
        digest: List<AgentMessage>,
        reviewType: ReviewType,
        memoryStore: MemoryStore,
        skillStore: SkillStore,
        engine: LlamaCppEngine,
        generateMutex: Mutex
    )
}

enum class ReviewType { MEMORY, SKILL, BOTH }
```

The review prompt is the key piece. Adapted from Hermes `background_review.py`:

**Memory review prompt:**
```
Review this conversation. What did the user reveal about themselves or their
environment that's worth remembering? Use the memory tool to store useful facts.
Do NOT store: transient errors, one-off task details, or negative claims like
"tool X doesn't work."
```

**Skill review prompt:**
```
Review this conversation. Was a non-trivial reusable procedure demonstrated?
If so, save it as a skill. Prefer: patch existing skill > extend existing >
create new. Do NOT capture: environment-specific errors, dead-end attempts,
or one-off narratives.
```

The learn pass creates a mini `AgentOrchestrator` with:
- `maxToolRounds = 4` (capped — review shouldn't be expensive)
- Tool manifest: only `memory` + `skill_manage` (hard whitelist)
- No recursive review (counters set to MAX_VALUE)
- Digest input: last 6 messages from the conversation (not full history)

---

### Step 3.3: Persist counters across sessions

**Modify:** `AgentPrefs.kt` — add:

```kotlin
private const val KEY_ITERS_SINCE_SKILL = "iters_since_skill"
private const val KEY_TURNS_SINCE_MEMORY = "turns_since_memory"

fun getItersSinceSkill(): Int
fun setItersSinceSkill(count: Int)
fun getTurnsSinceMemory(): Int
fun setTurnsSinceMemory(count: Int)
```

---

### Step 3.4: Wire learn pass in ChatScreen

**Modify:** `ChatScreen.kt` — after the orchestrator flow completes (in `finally` block, ~line 465):

```kotlin
// Check if review should fire
val itersSinceSkill = agentPrefs.getItersSinceSkill() + toolIterationsThisRun
val turnsSinceMemory = agentPrefs.getTurnsSinceMemory() + 1

val shouldReviewSkills = itersSinceSkill >= LearnPass.SKILL_REVIEW_THRESHOLD
val shouldReviewMemory = turnsSinceMemory >= LearnPass.MEMORY_REVIEW_THRESHOLD

if (shouldReviewSkills || shouldReviewMemory) {
    // Run sequentially after user gets their answer, on background dispatcher
    viewModelScope.launch(Dispatchers.Default) {
        val digest = messages.takeLast(6).map { /* convert to AgentMessage */ }
        val reviewType = when {
            shouldReviewSkills && shouldReviewMemory -> ReviewType.BOTH
            shouldReviewSkills -> ReviewType.SKILL
            else -> ReviewType.MEMORY
        }
        LearnPass.run(digest, reviewType, memoryStore, skillStore, EngineState.engine, EngineState.generateMutex)
        // Reset counters
        agentPrefs.setItersSinceSkill(0)
        agentPrefs.setTurnsSinceMemory(0)
    }
} else {
    agentPrefs.setItersSinceSkill(itersSinceSkill)
    agentPrefs.setTurnsSinceMemory(turnsSinceMemory)
}
```

---

### Step 3.5: Anti-poisoning guard

**Modify:** `MemoryTool.run()` and `SkillTools.manage()` — before writing, run a deterministic check:

```kotlin
private val POISON_PATTERNS = listOf(
    Regex("(?i)(doesn't|does not|can't|cannot|isn't|is not)\\s+(work|function|support)"),
    Regex("(?i)(broken|unavailable|missing|not installed)"),
    Regex("(?i)error:"),
    Regex("(?i)(failed to|unable to|could not)")
)

fun isPoisoned(text: String): Boolean = POISON_PATTERNS.any { it.containsMatchIn(text) }
```

If poisoned, return `ToolResult("Rejected: do not persist transient errors or negative claims.", isError = true)`.

This is a **deterministic regex pre-filter** — doesn't rely on the small model following prompt instructions perfectly. Hermes uses prompt-only rules, but for 3B-8B models we need the extra guardrail.

---

## Increment 4: FTS5 Cross-Session Recall

### What it does

SQLite FTS5 full-text search over past conversation messages. Zero LLM calls, deterministic, runs on Android natively. The agent gets a `session_search` tool that returns "bookend" snippets (first 5 + last 5 messages + match window) from matching past sessions.

### Step 4.1: Add Room database for message history

**New file:** `app/src/main/kotlin/com/aipaca/app/data/MessageDatabase.kt`

```kotlin
@Database(entities = [MessageEntity::class], version = 1)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,           // "user", "assistant", "system"
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "session_title") val sessionTitle: String = ""
)
```

Add FTS5 virtual table via a migration or `@Fts4` annotation (Room supports FTS4; for FTS5 use raw SQL in a callback):

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    content, session_title,
    content='messages',
    content_rowid='rowid'
);

-- Triggers to keep FTS in sync
CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content, session_title) VALUES (new.rowid, new.content, new.session_title);
END;
```

---

### Step 4.2: Create SessionSearchTool

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/memory/SessionSearchTool.kt`

```kotlin
object SessionSearchTool {
    const val NAME = "session_search"
    val INPUT_SCHEMA = /* { "query": { "type": "string" } } */

    suspend fun run(args: JsonObject, dao: MessageDao): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content ?: return ToolResult("Missing query", isError = true)

        // FTS5 search with BM25 ranking
        val matches = dao.searchFts(query, limit = 5)

        // For each matching session, build "bookends":
        // first 5 messages + match window (1 before, match, 1 after) + last 5 messages
        val result = buildBookends(matches, dao)

        return ToolResult(text = result.take(6000)) // cap output
    }
}
```

**Bookends pattern** (from Hermes): Instead of summarizing old sessions (expensive LLM call), return structural snippets that let the model reconstruct context:
- First 5 messages → what was the goal?
- Match window → what's relevant?
- Last 5 messages → how did it resolve?

---

### Step 4.3: Index existing conversations on first launch

**Modify:** `ChatScreen.kt` (or `Application` class) — on first launch after upgrade, backfill the FTS index from `ChatConversationStore`:

```kotlin
// One-time migration: index existing conversations into Room + FTS5
if (!agentPrefs.hasMigratedToFts()) {
    val conversations = conversationStore.loadConversations()
    for (conv in conversations) {
        for (msg in conv.messages) {
            messageDao.insert(MessageEntity(
                id = msg.id, sessionId = conv.id, role = msg.role.name,
                content = msg.content, timestamp = msg.timestamp,
                sessionTitle = conv.title
            ))
        }
    }
    agentPrefs.setMigratedToFts(true)
}
```

Going forward, `persistCurrentConversation()` also writes to Room.

---

### Step 4.4: Wire session_search tool

**Modify:** `ChatScreen.kt` — register alongside memory/skill tools:

```kotlin
registry.registerLocal(
    ToolSpec(name = SessionSearchTool.NAME, description = "Search past conversations for relevant context.", inputSchema = SessionSearchTool.INPUT_SCHEMA)
) { args -> SessionSearchTool.run(args, messageDao) }
```

---

### Step 4.5: Add search guidance to system prompt

**Modify:** `AgentConfig.kt` — append:

```
You can search past conversations using session_search when the user references
something discussed before or when you need context from a previous session.
```

---

## Prerequisite: registerLocal on ToolRegistry

Increments 1-4 all need `ToolRegistry.registerLocal()`. This is the same addition planned in PTC Step 2. A minimal version:

**Modify:** `ToolRegistry.kt`

```kotlin
data class LocalTool(
    val spec: ToolSpec,
    val handler: suspend (JsonObject) -> ToolResult
)

class ToolRegistry {
    private val clients = mutableListOf<McpClient>()
    private var tools: List<RegisteredTool> = emptyList()
    private val localTools = mutableListOf<LocalTool>()       // NEW

    fun registerLocal(spec: ToolSpec, handler: suspend (JsonObject) -> ToolResult) {  // NEW
        localTools += LocalTool(spec, handler)
    }

    fun manifest(): List<ToolSpec> =
        tools.map { it.spec } + localTools.map { it.spec }    // MODIFIED

    fun hasTool(name: String): Boolean =
        tools.any { it.spec.name == name } ||
        localTools.any { it.spec.name == name }               // MODIFIED

    suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
        // Check local tools first
        localTools.firstOrNull { it.spec.name == name }?.let {       // NEW
            return try { it.handler(arguments) }
            catch (e: Exception) { ToolResult("Tool failed: ${e.message}", isError = true) }
        }
        // Then MCP tools (existing code)
        val registered = tools.firstOrNull { it.spec.name == name }
            ?: return ToolResult(text = "Unknown tool: $name", isError = true)
        // ...existing code...
    }

    fun closeAll() {
        clients.forEach { it.close() }
        clients.clear()
        tools = emptyList()
        localTools.clear()                                    // NEW
    }
}
```

---

## Files Summary

| File | Action | Increment | Purpose |
|------|--------|-----------|---------|
| `app/.../agent/tool/ToolRegistry.kt` | Modify | Prereq | Add `registerLocal()` for non-MCP tools |
| `app/.../agent/memory/MemoryStore.kt` | **Create** | 1 | Read/write MEMORY.md and USER.md files |
| `app/.../agent/memory/MemoryTool.kt` | **Create** | 1 | `memory` tool spec + handler |
| `app/.../agent/AgentConfig.kt` | Modify | 1+2 | Frozen snapshot + skill index in system prompt |
| `app/.../ui/chat/ChatScreen.kt` | Modify | 1+2+3+4 | Wire all tools, learn pass, FTS migration |
| `app/.../agent/memory/Skill.kt` | **Create** | 2 | Skill data model |
| `app/.../agent/memory/SkillStore.kt` | **Create** | 2 | Filesystem-backed skill CRUD |
| `app/.../agent/memory/SkillTools.kt` | **Create** | 2 | `skill_view` + `skill_manage` tool specs |
| `app/.../agent/memory/LearnPass.kt` | **Create** | 3 | Counter-triggered review logic |
| `app/.../data/AgentPrefs.kt` | Modify | 3 | Persist review counters |
| `app/.../data/MessageDatabase.kt` | **Create** | 4 | Room DB + FTS5 for message search |
| `app/.../agent/memory/SessionSearchTool.kt` | **Create** | 4 | `session_search` tool with bookends |

---

## Verification

### Increment 1 (Memory)
1. Build compiles
2. Load model, enable agent mode, tell it "My name is Janik, I prefer Kotlin"
3. Check `agent_user.md` was written to disk
4. Start new session — agent should greet by name without being told again

### Increment 2 (Skills)
1. Build compiles
2. Have a multi-step conversation where the agent solves something
3. Manually create a skill via the tool or let the agent do it
4. In a new session, ask the agent to do the same thing — it should reference the skill index and load it via `skill_view`

### Increment 3 (Learn Pass)
1. Build compiles
2. Have a conversation with 10+ tool iterations
3. After the final answer, observe logs that LearnPass fires
4. Check that a new skill or memory entry was created
5. Verify anti-poisoning: force an error scenario and confirm nothing is persisted

### Increment 4 (FTS5 Recall)
1. Build compiles
2. Have multiple conversations on different topics
3. In a new session, ask "What did we discuss about X?" — agent should use `session_search` and find relevant past messages
4. Verify bookend format (first 5 + match + last 5)

### Integration Test
1. Full flow: agent uses tools → learn pass fires → skill created → next session loads skill index → agent uses `skill_view` → applies learned procedure → searches past sessions for context
2. Context budget check: with 50 skills + memory + user, measure total system prompt tokens. Should stay under ~2,500 tokens for the meta-sections (Hermes stays under ~1,300 tokens for memory/user alone; skill index scales with count)

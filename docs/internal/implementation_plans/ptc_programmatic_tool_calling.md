# Programmatic Tool Calling (PTC) for AIpaca

## Context

On-device models (3B-8B) have tiny context windows. When the agent makes N tool calls, each intermediate result bloats the context. PTC solves this: the model writes **one JavaScript script** that calls tools in a loop, filters/aggregates, and `console.log()`s only the condensed result. Only that output re-enters the context.

Hermes Agent does this via Python subprocess + Unix socket — impossible on Android (phantom process killer + SELinux). We use **QuickJS embedded in-process** via `dokar3/quickjs-kt` (~525 KB) instead.

`execute_code` is always available when agent mode is active (no opt-in toggle).

### Research & References

- Hermes Agent analysis: [docs/hermes_agent/hermes_erklaert.md](../../hermes_agent/hermes_erklaert.md)
- PTC deep-dive: [docs/hermes_agent/progammatic_tool_calling_explained.html](../../hermes_agent/progammatic_tool_calling_explained.html)
- CodeAct paper (ICML 2024): [arXiv:2402.01030](https://arxiv.org/abs/2402.01030) — LLMs are ~20% better at writing orchestration code than emitting JSON tool schemas
- QuickJS-kt library: [dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt) (Maven: `io.github.dokar3:quickjs-kt:1.0.8`)

### Why Not Subprocess (Android Constraints)

1. **Android 12+ Phantom Process Killer** — forked child processes killed aggressively (system-wide cap of 32)
2. **SELinux `untrusted_app` domain** — `execve()` to new binaries blocked by `neverallow` policy
3. **No Termux-style workaround** — Termux works because it controls its own UID

### Why QuickJS

| Option | Size | Async | Android Ready | Verdict |
|--------|------|-------|---------------|---------|
| **QuickJS** (dokar3/quickjs-kt) | ~525 KB | ES2020 async/await | Maven Central, Kotlin coroutines | **Winner** |
| Lua 5.4 | ~300 KB | No native async | Needs custom JNI bridge | Viable fallback |
| Rhino (Mozilla) | ~1.5 MB | Partial ES6 | Pure Java, no NDK | Slow |
| Chaquopy (Python) | +30-60 MB | Yes | Gradle plugin | Too heavy |
| V8 | 10-30 MB | Full | Impractical size | No |

---

## Implementation Steps

### Step 1: Add quickjs-kt dependency

**`gradle/libs.versions.toml`** — add:
```toml
quickjsKt = "1.0.8"
# ...
quickjs-kt = { group = "io.github.dokar3", name = "quickjs-kt", version.ref = "quickjsKt" }
```

**`app/build.gradle.kts`** — add:
```kotlin
implementation(libs.quickjs.kt)
```

---

### Step 2: Add local tool support to ToolRegistry

**Modify:** `app/src/main/kotlin/com/aipaca/app/agent/tool/ToolRegistry.kt`

Add a `LocalTool` data class and `registerLocal(spec, handler)` method. Existing MCP path unchanged.

```kotlin
data class LocalTool(
    val spec: ToolSpec,
    val handler: suspend (JsonObject) -> ToolResult
)
```

- `registerLocal(spec, handler)` — adds to a `localTools` list (no connect/network)
- `manifest()` — returns MCP tools + local tools
- `hasTool()` — checks both lists
- `callTool()` — checks local tools first, then MCP
- `closeAll()` — also clears local tools

---

### Step 3: Create ScriptSandbox

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/tool/ScriptSandbox.kt`

Creates a fresh QuickJS instance per execution. Binds:
- `console.log(...)` → captures to StringBuilder
- `tools.<name>({...})` → async functions calling back into ToolRegistry

Limits:
- 30s timeout (`evaluationTimeoutMillis`)
- 16 MB memory limit
- 50 tool calls max (AtomicInteger counter in async bindings)
- 50 KB output cap

Returns captured `console.log` output as `ToolResult`.

Constructor takes:
- `toolCaller: suspend (name: String, arguments: JsonObject) -> ToolResult` (injected, avoids circular dep)
- `availableTools: List<ToolSpec>` (for binding function names)

---

### Step 4: Create ExecuteCodeTool

**New file:** `app/src/main/kotlin/com/aipaca/app/agent/tool/ExecuteCodeTool.kt`

Static helper object with:
- `NAME = "execute_code"`
- `INPUT_SCHEMA` — JSON Schema with `script` (string, required)
- `description(otherTools)` — generates dynamic description including stub docs for all available JS functions (so the model knows what `tools.*` functions exist and their parameters)
- `run(args, registry)` — validates script, creates ScriptSandbox, returns result

Prevents recursion: filters `execute_code` out of the tool list passed to ScriptSandbox.

Max script length: 4000 chars.

---

### Step 5: Wire execute_code in ChatScreen

**Modify:** `app/src/main/kotlin/com/aipaca/app/ui/chat/ChatScreen.kt` (~line 390, after MCP registration)

After `registry.register(client)`, register the local tool:
```kotlin
val otherTools = registry.manifest()  // MCP tools registered so far
val executeCodeSpec = ToolSpec(
    name = ExecuteCodeTool.NAME,
    description = ExecuteCodeTool.description(otherTools),
    inputSchema = ExecuteCodeTool.INPUT_SCHEMA
)
registry.registerLocal(executeCodeSpec) { args ->
    ExecuteCodeTool.run(args, registry)
}
```

Also update the `AgentStep.ToolCall` display to show a different label for `execute_code` ("Running script..." instead of "Searching...").

---

### Step 6: Update system prompt

**Modify:** `app/src/main/kotlin/com/aipaca/app/agent/AgentConfig.kt`

Add one sentence to `systemPrompt`:
```
When you need to make multiple tool calls, or need to filter/process tool results,
prefer using execute_code to write a short JavaScript script that calls tools in a
loop and console.log()s only the condensed result. For single simple lookups, call
the tool directly.
```

---

### Step 7: Tests

**New:** `app/src/test/kotlin/com/aipaca/app/agent/tool/ToolRegistryLocalTest.kt`
- `registerLocal` adds to manifest and hasTool
- `callTool` routes to local handler
- `closeAll` clears local tools

**New:** `app/src/test/kotlin/com/aipaca/app/agent/tool/ExecuteCodeToolTest.kt`
- `description()` includes stub docs for available tools
- `run()` rejects missing script argument
- `run()` rejects script exceeding max length

ScriptSandbox needs Android/Robolectric for QuickJS native lib — defer to manual on-device testing initially.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `gradle/libs.versions.toml` | Modify | Add quickjs-kt version + library |
| `app/build.gradle.kts` | Modify | Add quickjs-kt dependency |
| `app/.../agent/tool/ToolRegistry.kt` | Modify | Add `registerLocal()` for non-MCP tools |
| `app/.../agent/tool/ScriptSandbox.kt` | **Create** | QuickJS sandbox with tool bindings + output capture |
| `app/.../agent/tool/ExecuteCodeTool.kt` | **Create** | execute_code ToolSpec, description, run() |
| `app/.../ui/chat/ChatScreen.kt` | Modify | Wire execute_code registration + UI label |
| `app/.../agent/AgentConfig.kt` | Modify | System prompt PTC guidance |
| `app/.../test/.../ToolRegistryLocalTest.kt` | **Create** | Tests for registerLocal |
| `app/.../test/.../ExecuteCodeToolTest.kt` | **Create** | Tests for ExecuteCodeTool |

---

## Verification

1. **Build compiles** after Step 1
2. **Unit tests pass** after Steps 2, 4, 7
3. **On-device test**: Load a model (e.g. Qwen 3 4B), enable agent mode, ask "Search for the 3 latest news about AI and summarize them" — model should either use execute_code with a JS script or fall back to individual tool calls. Both paths must work.
4. **PTC-specific test**: Ask "Search for news about Apple, Google, and Microsoft and tell me which company had the most news today" — this naturally requires batch searching, so the model should prefer execute_code.
5. **Error handling**: Manually test with a bad script (syntax error, infinite loop, too many tool calls) — should return error ToolResult, not crash.

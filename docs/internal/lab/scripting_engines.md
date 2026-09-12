# Lab Journal — Scripting Engines & Sandbox Architecture

Extracted from the AIpaca lab journal. Research into embedded scripting engines for programmatic tool calling on Android.

---

## 2026-07-30 - Programmatic Tool Calling on Android: Embedded Scripting Engines & Sandbox Architecture

**Context**: Research into how to implement "programmatic tool calling" for the on-device LLM agent on Android. The goal is for the LLM to write a script that executes multiple tool calls in a sandboxed interpreter; only the final printed output re-enters the model's context window — collapsing N tool-call turns into one. This is inspired by the CodeAct paradigm (ICML 2024) and Hermes Agent's `execute_code` tool.

---

### 1. The Programmatic Tool Calling Paradigm

**Background paper**: [CodeAct: Executable Code Actions Elicit Better LLM Agents](https://arxiv.org/abs/2402.01030) (Wang et al., ICML 2024). Key finding: across 17 models on API-Bank and M3ToolEval benchmarks, code-based tool orchestration beats JSON tool-calling by **up to 20% success rate** and reduces required turns. LLMs are simply better at writing code than at formatting JSON tool-call schemas.

**Anthropic's production implementation** (November 2025): the model writes Python running in a managed container. Only `print()` output from the script enters the next context. Reported **37% average token reduction**, up to 98% for workflows with large intermediate results.

**Hermes Agent implementation** (`NousResearch/hermes-agent`):
- LLM generates a Python script that `import hermes_tools` and calls functions from it.
- Parent process generates `hermes_tools.py` stub (contains only the tools the script is allowed to call).
- Parent opens a **Unix domain socket** (AF_UNIX), starts an RPC listener thread.
- Child process runs the script via `subprocess`; tool calls in the script travel over the socket back to the parent.
- Only `stdout` of the child process is returned to the LLM. Intermediate tool results never enter the context.
- Protocol: **newline-delimited JSON** `{"tool": "<name>", "args": {...}, "token": "<hmac-token>"}\n`
- Child stdout is captured and truncated at 50 KB using a 40/60 head/tail split.
- Default timeout: 300 seconds; child process group killed on timeout.

**Key insight for Android**: the two core mechanisms are (a) an in-process interpreter or child process that executes the LLM's script, and (b) a bidirectional channel to dispatch tool calls back to the host app. On desktop/server, these are a subprocess + Unix socket. On Android, subprocess spawning is heavily restricted (see Section 3), so an **in-process scripting engine** is the practical equivalent.

---

### 2. Embedded Scripting Engines for Android

#### 2a. JavaScript — QuickJS (RECOMMENDED)

QuickJS is a small, embeddable C JavaScript engine by Fabrice Bellard. It is the **best practical choice for Android** given size, performance, active Android bindings, and ES2020 compliance.

**Size**: ~525 KiB stripped on arm64. Entire engine fits in ~200 KB of source.

**ES2020 compliance**: full support including async/await, modules, BigInt — needed for async tool dispatch.

**Android bindings (Kotlin-first)**:

| Library | Status | Artifact |
|---|---|---|
| [dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt) | Active, 2024, Apache 2.0 | `io.github.dokar3:quickjs-kt-android` on Maven Central |
| [OpenQuickJS/quickjs-android](https://github.com/OpenQuickJS/quickjs-android) | Active | GitHub AAR |
| [HarlonWang/quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper) | Active | GitHub |
| [quickjs-zh/quickjs-android](https://github.com/quickjs-zh/quickjs-android) | Older (2019 base) | GitHub |

**quickjs-kt API (idiomatic Kotlin, recommended)**:

```kotlin
// Gradle: implementation("io.github.dokar3:quickjs-kt-android:<VERSION>")

val qjs = QuickJs.create(Dispatchers.Default)

// Define a host function callable from JS (synchronous)
qjs.function("readFile") { args ->
    val path = args[0] as String
    File(path).readText()   // runs on Kotlin side, result returned to JS
}

// Define an async/suspend host function
qjs.asyncFunction("httpGet") { args ->
    val url = args[0] as String
    withContext(Dispatchers.IO) { /* ... suspend network call ... */ }
}

// Define a nested object (e.g. "tools" namespace)
qjs.define("tools") {
    function("readFile") { args -> /* ... */ }
    asyncFunction("search") { args -> /* ... */ }
}

// Evaluate a script; only its return value / console.log enters the result
val output = qjs.evaluate<String>("""
    const data = tools.readFile('/tmp/notes.txt');
    tools.search(data);
""")

qjs.close()
```

**Evaluation timeout**: built-in `evaluateTimeout` parameter prevents infinite loops.

**Limitations**:
- Single-threaded runtime (JS event loop); async functions require coroutine integration.
- No native `long` type — must use Number or BigInt.
- ProGuard rules needed for reflection-based bindings.
- No shared memory with C++ without additional JNI bridging.

**NDK-direct path**: QuickJS C source can be compiled directly into the existing NDK project via `CMakeLists.txt` (`add_subdirectory(quickjs)`), avoiding any Java/Kotlin overhead. Tool callbacks would go through a JNI callback registered before execution. This is the lowest-latency path.

#### 2b. JavaScript — Other Engines

| Engine | Android viability | Notes |
|---|---|---|
| **Duktape** | Good (C, ~640 KB) | ES5 only, no async/await natively. Cash App's Zipline used it, then migrated to QuickJS. Older, less maintained. |
| **Mozilla Rhino** | Good (pure Java) | ES6 partial. Runs on JVM, no NDK needed. Slower than QuickJS, larger JAR (~1.5 MB). [brionmario/rhino-library-android](https://github.com/brionmario/rhino-library-android). |
| **V8** | Impractical | ~10-30 MB binary. Overkill for Android. Used internally by React Native but not embeddable for this purpose. |
| **Hermes (Meta)** | Not embeddable | Designed for React Native; not a standalone embeddable C library like QuickJS. |

**Verdict**: QuickJS dominates the embedded-JS space for Android. Duktape is acceptable for ES5-only scripts.

#### 2c. Lua — Lua 5.4 / LuaJIT

Lua is the traditional embedded scripting choice for performance-sensitive hosts (game engines, etc.).

**Plain Lua 5.4**:
- CMake-buildable for Android NDK: [NLua/lua](https://github.com/NLua/lua) provides a CMake build with Android NDK instructions.
- Also: [walterschell/Lua](https://github.com/walterschell/Lua) — CMake build of Lua 5.4.6.
- Binary size: ~300 KB stripped.
- C API for host functions is straightforward (`lua_register`, `lua_pushcfunction`).
- No async/await; coroutines exist but require manual scheduler.

**LuaJIT**:
- JIT support is available only on arm32 (armeabi-v7a). On arm64 (the primary Android ABI), LuaJIT runs in **interpreter mode only** — no JIT. So there is no real performance advantage over plain Lua 5.4 on modern Android.
- Build is complex. [mobilelinux/Android-luajit](https://github.com/mobilelinux/Android-luajit) provides a wrapper but is poorly maintained.
- Open issue [LuaJIT#477](https://github.com/LuaJIT/LuaJIT/issues/477): "Android build no longer works."

**Java/Kotlin bridge options**:
- [gudzpoz/luajava](https://github.com/gudzpoz/luajava): supports Lua 5.1–5.5, LuaJIT, LuaJ. `java.import()`, `java.new()`, `java.proxy()`. Android arm and x86 prebuilt.
- [lua-for-android](https://github.com/qtiuto/lua-for-android): high-performance bridge, auto method deduction, multi-threading, exception passing. Practical for tool callbacks.

**Verdict**: Plain Lua 5.4 compiled via NDK is viable and small. However, Lua lacks native async support and the tooling ecosystem is smaller than QuickJS's. LuaJIT's arm64 JIT advantage does not materialize on modern Android. **Prefer QuickJS for new work** unless the team has existing Lua expertise or the LLM is explicitly trained to emit Lua.

#### 2d. Python — Chaquopy / CPython

[Chaquopy](https://chaquo.com/chaquopy/) embeds CPython (3.10–3.14 as of 2025) in Android apps via a Gradle plugin.

**Pros**:
- Most powerful scripting environment; all Python stdlib + pip packages.
- Bidirectional Java/Kotlin ↔ Python calls via JNI (Chaquopy handles marshalling).
- This is the closest functional equivalent to the Hermes server-side sandbox.

**Cons**:
- **APK size**: CPython shared library + stdlib adds 30–60 MB to the APK (before ABI splits). Enormous for a mobile app.
- **Startup time**: CPython interpreter start is slow (100–500 ms) vs. QuickJS (<10 ms).
- **Chaquopy license**: Apache 2.0 (open source), but Gradle plugin approach ties to Android Studio build system.
- Pip packages must be pre-bundled at build time (no dynamic pip install at runtime on unrooted Android).

**Verdict**: Chaquopy is a legitimate option if Python is required (e.g., for scientific packages), but the APK size overhead makes it impractical as a lightweight tool sandbox. Not recommended for AIpaca unless there is a specific need.

#### 2e. WebAssembly — WAMR

[WAMR (WebAssembly Micro Runtime)](https://github.com/bytecodealliance/wasm-micro-runtime) by Bytecode Alliance:
- Supports Android (documented in its `README`).
- Runs Wasm modules in interpreter mode, AoT, or JIT.
- Very small footprint (~100 KB for interpreter-only mode).
- Tool callbacks would be implemented as WASM host imports (C functions registered before execution).
- **Major downside**: LLMs do not write Wasm/WAT natively. The scripting language for the LLM would need to be compiled to Wasm ahead of time. Not suitable for dynamic LLM-generated scripts.

**Verdict**: WAMR is architecturally interesting but not practical for this use case (LLM generates code at runtime).

---

### 3. Android Process & Subprocess Constraints

#### Can Android apps spawn child processes?

Yes, `Runtime.exec()` and `ProcessBuilder` work on Android at the OS level (it is Linux). However, several layers of restriction apply:

**Android 12+ Phantom Process Killer**:
- Introduced in Android 12 (API 31).
- Any process forked from an app process is a "phantom process."
- The system kills the phantom process with the worst OOM adj score if there are more than **32 phantom processes** across the system, OR if they use excessive CPU while the parent app is backgrounded.
- This is a hard OS-level enforcement — no workaround without ADB developer options or root.
- Workaround (developer mode only): `adb shell device_config put activity_manager max_phantom_processes 2147483647`
- On Android 14+: Settings > Developer Options > "Disable child process restrictions" toggle.
- **For a production app, subprocess spawning cannot be relied upon.**

**SELinux**:
- App processes run in the `untrusted_app` SELinux domain.
- `execve()` to arbitrary binaries is blocked by SELinux policy (`neverallow untrusted_app exec`).
- Apps can `fork()` (same binary), but running a new executable via exec is disallowed.
- Shipping a standalone Python/script interpreter binary and exec-ing it at runtime is **not allowed** in production apps.

**Termux-style approach**: Termux works because it is a terminal emulator that runs scripts within the Termux process space (shared UID), not by exec-ing arbitrary system binaries from an untrusted_app context. An in-app approach would need to embed the interpreter as a shared library, not a standalone executable.

**Conclusion**: Subprocess-based execution (the Hermes server approach) is **not viable in production Android apps** without either root/ADB developer flags or the App's process being a system service. The correct Android approach is an **in-process embedded interpreter**.

---

### 4. Unix Domain Sockets on Android

**Are AF_UNIX sockets available?** Yes, fully supported.

**Android SDK**: `android.net.LocalSocket` / `android.net.LocalServerSocket` provide Java-level wrappers.

**Abstract namespace**: Android's LocalSocket uses the Linux **abstract namespace** (prepended `\0`) rather than the filesystem. This means:
- No file permissions to manage.
- Socket is automatically cleaned up when the last reference closes.
- The name is only accessible to processes with the same UID (or root) by default.

**NDK-level**: Standard `socket(AF_UNIX, SOCK_STREAM, 0)` + `bind()` + `connect()` works directly in C/C++ code.

**Security**:
- A [CCS 2016 paper](http://www.cs.ucr.edu/~zhiyunq/pub/ccs16_local_socket.pdf) documented misuse of Android Unix domain sockets in system daemons. For inter-thread or intra-process communication, this is a non-issue (same UID).
- For sandboxing within a single app, token-based authentication (HMAC, as Hermes does) is the recommended mitigation if the socket is in a shared namespace.

**Relevance for AIpaca**: If a multi-process approach were ever used (e.g., an isolated service process), AF_UNIX sockets would be the right transport. For the single-process embedded interpreter approach, sockets are not needed — direct function pointers or JNI callbacks suffice.

---

### 5. Recommended Architecture for Android Programmatic Tool Calling

Given the constraints above, the recommended architecture is a **single-process, in-process QuickJS sandbox**:

```
LLM generates JS script
        |
        v
  QuickJS runtime (in-process, single-threaded)
  - has "tools" JS object with host functions registered
  - tools.readFile(), tools.httpGet(), tools.runShellCmd(), etc.
        |
        | JS calls tools.xxx()
        v
  Kotlin host function (quickjs-kt asyncFunction)
  - suspends on IO
  - executes real tool logic (file access, HTTP, etc.)
  - returns result to JS as string/object
        |
        | JS continues execution
        v
  console.log() / return value captured
        |
        v
  Captured stdout re-entered into LLM context (single turn)
```

**Implementation approach**:
1. Use `quickjs-kt` (`io.github.dokar3:quickjs-kt-android`) for idiomatic Kotlin integration.
2. Register all agent tools as `asyncFunction` bindings on a `tools` JS namespace.
3. The LLM is prompted to write short JavaScript scripts using `await tools.xxx()` calls.
4. Use `qjs.evaluate<String>(script, timeout = 30_000)` to run the script with a hard timeout.
5. Capture `console.log` output by registering a custom `console` binding that appends to a `StringBuilder`.
6. Return the captured stdout string to the LLM as the tool call result.

**Alternative (NDK path)**: Compile QuickJS C sources directly into `llama_jni.cpp`'s NDK project via `add_subdirectory(quickjs)`. Register tool callbacks as C function pointers. This eliminates JNI overhead for the scripting layer — relevant only if JS execution speed becomes a bottleneck.

**Lua alternative**: If the LLM is fine-tuned / prompted to emit Lua, plain Lua 5.4 built via NDK is a viable lower-overhead option. Same architecture applies; callbacks go through `lua_register` C API.

---

### 6. Key Libraries and References

| Resource | URL |
|---|---|
| CodeAct paper (ICML 2024) | https://arxiv.org/abs/2402.01030 |
| CodeAct GitHub | https://github.com/xingyaoww/code-act |
| Hermes Agent | https://github.com/NousResearch/hermes-agent |
| Hermes code_execution_tool.py | https://github.com/NousResearch/hermes-agent/blob/main/tools/code_execution_tool.py |
| quickjs-kt (Kotlin bindings) | https://github.com/dokar3/quickjs-kt |
| quickjs-kt Maven Central | https://central.sonatype.com/artifact/io.github.dokar3/quickjs-kt-android |
| OpenQuickJS/quickjs-android | https://github.com/OpenQuickJS/quickjs-android |
| HarlonWang/quickjs-wrapper | https://github.com/HarlonWang/quickjs-wrapper |
| QuickJS official (Bellard) | https://bellard.org/quickjs/ |
| gudzpoz/luajava (Lua↔Java) | https://github.com/gudzpoz/luajava |
| NLua/lua (CMake NDK build) | https://github.com/NLua/lua |
| lua-for-android | https://github.com/qtiuto/lua-for-android |
| Chaquopy (CPython Android) | https://chaquo.com/chaquopy/ |
| WAMR | https://github.com/bytecodealliance/wasm-micro-runtime |
| Android Phantom Process Killer | https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md |
| Android SELinux | https://source.android.com/docs/security/features/selinux |
| LocalServerSocket API | https://developer.android.com/reference/android/net/LocalServerSocket |
| UDS security on Android (CCS 2016) | http://www.cs.ucr.edu/~zhiyunq/pub/ccs16_local_socket.pdf |

**Tags**: #agent #tool-calling #quickjs #lua #javascript #sandbox #android #programmatic-tool-calling #codeact #hermes #in-process #scripting

---

## 2026-07-30 - dokar3/quickjs-kt v1.0.8 — Verified API Reference

**Context**: Concrete implementation plan for the JS sandbox required exact API verification directly from source. This entry supersedes the sketch in the entry above and provides verified, copy-paste-ready API facts sourced from GitHub `main` at commit corresponding to v1.0.8 (released 2026-07-28).

**Sources**:
- https://github.com/dokar3/quickjs-kt (README + full source)
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/QuickJs.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/QuickJsException.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/binding/Bindings.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/binding/DslObjectBinding.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/binding/Bindings.dsl.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/binding/Bindings.dsl.typedArg.kt
- https://github.com/dokar3/quickjs-kt/blob/main/quickjs/src/commonMain/kotlin/com/dokar/quickjs/alias/dslAlias.kt
- https://central.sonatype.com/artifact/io.github.dokar3/quickjs-kt

---

### 1. Maven Coordinate and Version

**Latest stable release**: `1.0.8` (published 2026-07-28; synced to latest upstream QuickJS commit, AGP updated to v9.3.1)

```toml
# libs.versions.toml
[versions]
quickjs-kt = "1.0.8"

[libraries]
quickjs-kt = { module = "io.github.dokar3:quickjs-kt", version.ref = "quickjs-kt" }
```

```kotlin
// build.gradle.kts (app module)
implementation("io.github.dokar3:quickjs-kt:1.0.8")
```

**IMPORTANT — Artifact ID clarification**: The README's Maven coordinate is `io.github.dokar3:quickjs-kt` (NOT `quickjs-kt-android`). The library is Kotlin Multiplatform and publishes a single artifact that includes Android support. Earlier notes in this journal referenced `quickjs-kt-android` — this appears to have been an older artifact ID or a misread. Verify on Maven Central at https://central.sonatype.com/artifact/io.github.dokar3/quickjs-kt before implementation.

The library module in the repo is named `quickjs` (not `quickjs-kt`), but the published artifact name is `quickjs-kt`.

---

### 2. Creating a QuickJS Runtime

**Option A — DSL (short-lived, auto-closes):**

```kotlin
// Requires a coroutine context with a CoroutineDispatcher
coroutineScope.launch {
    val result = quickJs {            // uses current coroutine's dispatcher
        evaluate<Int>("1 + 2")
    }
    // instance is closed automatically after the block
}

// Or pass dispatcher explicitly (works outside a coroutine):
val result = quickJs(Dispatchers.Default) {
    evaluate<Int>("1 + 2")
}
```

The `quickJs { }` DSL is defined as:

```kotlin
suspend inline fun <T : Any?> quickJs(block: QuickJs.() -> T): T
inline fun <T : Any?> quickJs(jobDispatcher: CoroutineDispatcher, block: QuickJs.() -> T): T
```

**Option B — Long-lived instance (explicit lifecycle):**

```kotlin
val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)

// ... use it across multiple coroutines ...

quickJs.close()  // must be called manually
```

`QuickJs.create()` signature:

```kotlin
companion object {
    @Throws(QuickJsException::class)
    fun create(jobDispatcher: CoroutineDispatcher): QuickJs
}
```

**Thread safety**: The `jobDispatcher` parameter determines which thread the JS event loop runs on. The JS runtime itself is single-threaded (one event loop per instance). All `evaluate` calls are `suspend` functions — they must be called from a coroutine. Multiple callers can `evaluate` concurrently on the same instance, but only one evaluation runs at a time (they are serialized by the dispatcher). For AIpaca: use `Dispatchers.Default` and ensure all calls are inside `launch` or `withContext`.

---

### 3. Defining Host Functions (Kotlin functions callable from JS)

#### 3a. Synchronous function — raw args array

```kotlin
// Attach directly to globalThis
quickJs.function("greet") { args: Array<Any?> ->
    val name = args[0] as String
    "Hello, $name!"
}
// JS: greet("Jack")  →  "Hello, Jack!"
```

#### 3b. Synchronous function — single typed arg (type-safe overload)

```kotlin
// Single typed argument, automatic conversion
quickJs.function<String, String>("greet") { name: String ->
    "Hello, $name!"
}
// JS: greet("Jack")  →  "Hello, Jack!"
```

Both overloads are defined in `Bindings.dsl.kt` / `Bindings.dsl.typedArg.kt`:

```kotlin
// Raw args:
inline fun <R : Any?> QuickJs.function(
    name: String,
    crossinline block: (args: Array<Any?>) -> R
)

// Single typed arg:
inline fun <reified T : Any?, R : Any?> QuickJs.function(
    name: String,
    crossinline block: (T) -> R
)
```

#### 3c. Async function (suspend — for IO, network, etc.)

```kotlin
// Raw args
quickJs.asyncFunction("httpGet") { args: Array<Any?> ->
    val url = args[0] as String
    // Can call any suspend function here, including withContext(Dispatchers.IO)
    httpClient.get(url).bodyAsText()
}

// Single typed arg
quickJs.asyncFunction<String, String>("httpGet") { url: String ->
    httpClient.get(url).bodyAsText()
}
// JS: const body = await httpGet("https://example.com");
```

`asyncFunction` signatures:

```kotlin
inline fun <R> QuickJs.asyncFunction(
    name: String,
    crossinline block: suspend (args: Array<Any?>) -> R
)

inline fun <reified T : Any?, reified R : Any?> QuickJs.asyncFunction(
    name: String,
    crossinline block: suspend (T) -> R
)
```

In JavaScript, `asyncFunction` bindings are exposed as functions that return a `Promise`. The caller must `await` them.

#### 3d. Defining a namespace object (`define`)

```kotlin
quickJs.define("console") {
    // Inside the ObjectBindingScope:
    function("log") { args: Array<Any?> ->
        println(args.joinToString(" "))
        null
    }
    function("error") { args: Array<Any?> ->
        System.err.println(args.joinToString(" "))
        null
    }
}

// Nested objects:
quickJs.define("tools") {
    function("readFile") { args ->
        File(args[0] as String).readText()
    }
    asyncFunction("search") { args ->
        mySearchClient.query(args[0] as String)
    }
    define("fs") {           // nested sub-object
        function("exists") { args ->
            File(args[0] as String).exists()
        }
    }
}
// JS: tools.readFile("/path");  await tools.search("query");  tools.fs.exists("/path");
```

`define` DSL signature (on `QuickJs`):

```kotlin
fun QuickJs.define(name: String, block: ObjectBindingScope.() -> Unit)
```

`ObjectBindingScope` interface (from `DslObjectBinding.kt`):

```kotlin
interface ObjectBindingScope {
    fun define(name: String, block: ObjectBindingScope.() -> Unit)        // nested object
    fun <T> property(name: String, block: PropertyScope<T>.() -> Unit)    // JS property
    fun <R> function(name: String, block: FunctionBinding<R>)             // sync function
    fun <R> asyncFunction(name: String, block: AsyncFunctionBinding<R>)   // async function
}
```

`FunctionBinding<R>` and `AsyncFunctionBinding<R>` are SAM interfaces:

```kotlin
fun interface FunctionBinding<R> : Binding {
    fun invoke(args: Array<Any?>): R
}

fun interface AsyncFunctionBinding<R> : Binding {
    suspend fun invoke(args: Array<Any?>): R
}
```

#### 3e. Properties on objects

```kotlin
var counter = 0
quickJs.define("state") {
    property<Int>("counter") {
        getter { counter }
        setter { value -> counter = value }
        // optional: configurable = true (default), writable = true (default if setter present)
        // enumerable = true (default)
    }
}
// JS: state.counter;        // getter called
// JS: state.counter = 42;   // setter called
```

---

### 4. Evaluating a Script

```kotlin
// Basic: returns typed result of last expression
val result: Int = quickJs.evaluate<Int>("1 + 2")           // → 3

// With explicit filename (improves stack traces in exceptions)
val result: String = quickJs.evaluate<String>(
    code = "\"hello \" + \"world\"",
    filename = "script.js",
)

// As ES Module (enables import/export syntax)
val result: Any? = quickJs.evaluate<Any?>(
    code = """
        import * as tools from "myModule";
        tools.greet("World")
    """.trimIndent(),
    asModule = true,
)

// From pre-compiled bytecode
val bytecode: ByteArray = quickJs.compile("1 + 2", filename = "main.js")
val result: Int = quickJs.evaluate<Int>(bytecode)
```

`evaluate` signatures (from `QuickJs.kt`):

```kotlin
@Throws(QuickJsException::class, CancellationException::class)
suspend inline fun <reified T> evaluate(bytecode: ByteArray): T

@Throws(QuickJsException::class, CancellationException::class)
suspend inline fun <reified T> evaluate(
    code: String,
    filename: String = "main.js",
    asModule: Boolean = false,
): T
```

`evaluate` is a `suspend` function — it MUST be called from within a coroutine.

**Getting return values**: The return type is determined by the reified type parameter `T`. The last evaluated expression in the script becomes the return value. If the script has no explicit return (or the module form is used), use `evaluate<Unit>` or `evaluate<Any?>`.

---

### 5. Setting a Timeout

```kotlin
// Property on the QuickJs instance — applies to every subsequent evaluate() call
quickJs.evaluationTimeoutMillis = 5_000L  // 5 seconds; 0 or negative = disabled (default)

// When timeout is exceeded, throws QuickJsInterruptedException (subclass of QuickJsException)
```

```kotlin
// Alternative: use Kotlin's withTimeout — bounds TOTAL wall-clock time including
// time spent suspended in asyncFunction bindings
withTimeout(5_000L) {
    quickJs.evaluate<Unit>("while(true){}")
}
// Throws kotlinx.coroutines.TimeoutCancellationException
```

**Key distinction**: `evaluationTimeoutMillis` only counts time actively executing JavaScript (not time awaiting Kotlin suspensions in `asyncFunction` callbacks). `withTimeout {}` counts total wall-clock time and is the right choice for bounding the full script execution duration end-to-end.

Manual interrupt from any thread:

```kotlin
quickJs.interruptEvaluation()
// throws QuickJsInterruptedException in the evaluation coroutine
```

---

### 6. Closing / Disposing the Runtime

```kotlin
quickJs.close()         // frees the native JS runtime and context
quickJs.isClosed        // Boolean property — true after close()
```

The DSL form (`quickJs { }`) closes automatically in the `finally` block. For long-lived instances created with `QuickJs.create()`, `close()` must be called explicitly. QuickJs does NOT implement `Closeable`/`AutoCloseable` from the Kotlin/JVM stdlib, so `use { }` is not available — manual `try/finally` is required:

```kotlin
val qjs = QuickJs.create(Dispatchers.Default)
try {
    qjs.evaluate<Unit>(script)
} finally {
    qjs.close()
}
```

---

### 7. console.log Capture

QuickJS does NOT include a built-in `console` object. To capture `console.log` output from scripts, define it manually as a host binding:

```kotlin
val logOutput = StringBuilder()

quickJs.define("console") {
    function("log") { args: Array<Any?> ->
        logOutput.append(args.joinToString(" ")).append('\n')
        null
    }
    function("error") { args: Array<Any?> ->
        logOutput.append("[error] ").append(args.joinToString(" ")).append('\n')
        null
    }
    function("warn") { args: Array<Any?> ->
        logOutput.append("[warn] ").append(args.joinToString(" ")).append('\n')
        null
    }
}

quickJs.evaluate<Unit>("""
    console.log("Hello from JS");
    console.log("Tool result:", 42);
""")

val captured = logOutput.toString()
// → "Hello from JS\nTool result: 42\n"
```

This is intentional by design — the library does not auto-inject `console`, giving the host full control over what gets captured vs. printed to logcat.

---

### 8. Error Handling

All errors from evaluate/compile throw `QuickJsException` or its subclass `QuickJsInterruptedException`:

```kotlin
open class QuickJsException(
    override val message: String?,
    val stack: String? = null,    // full JS stack trace string
) : Exception() {
    val fileName: String?         // parsed from stack; null if unknown
    val lineNumber: Int?          // parsed from stack; null if unknown
    val columnNumber: Int?        // parsed from stack; null if unknown
}

class QuickJsInterruptedException(message: String?) : QuickJsException(message)
```

Usage:

```kotlin
try {
    quickJs.evaluate<Unit>("""
        function badFunc() { throw new Error("something went wrong"); }
        badFunc();
    """, filename = "agent_script.js")
} catch (e: QuickJsInterruptedException) {
    // Timeout or interruptEvaluation() was called
    Log.w(TAG, "Script timed out: ${e.message}")
} catch (e: QuickJsException) {
    // JavaScript error
    Log.e(TAG, "Script error at ${e.fileName}:${e.lineNumber}:${e.columnNumber}")
    Log.e(TAG, e.stack ?: e.message ?: "unknown error")
} catch (e: CancellationException) {
    // Coroutine was cancelled
    throw e   // always rethrow CancellationException
}
```

Stack trace parsing is built into `QuickJsException`: the constructor automatically parses the first stack frame to populate `fileName`, `lineNumber`, `columnNumber`. Frames look like `" at fn (file.js:12:5)"` or `" at file.js:12:5"` for parse errors.

---

### 9. Module Support (ES Modules)

```kotlin
// Add a named JS module before evaluate()
quickJs.addModule(
    name = "myModule",
    code = """
        export function greet(name) {
            return "Hello, " + name + "!";
        }
        export const PI = 3.14159;
    """.trimIndent(),
)

// Evaluate a script that imports the module
val result = quickJs.evaluate<String>(
    code = """
        import { greet, PI } from "myModule";
        greet("World") + " PI=" + PI;
    """.trimIndent(),
    asModule = true,   // REQUIRED for import/export syntax
)
// → "Hello, World! PI=3.14159"

// Add a pre-compiled module (bytecode)
val bytecode = quickJs.compile(moduleCode, asModule = true)
quickJs.addModule(bytecode)
```

`addModule` signatures:

```kotlin
@Throws(QuickJsException::class)
fun addModule(name: String, code: String)

fun addModule(bytecode: ByteArray)
```

**Important**: `asModule = true` must be passed to `evaluate()` when the script uses `import`/`export` syntax. Without it, `import` statements throw a syntax error.

---

### 10. Kotlin / JS Type Mappings

Automatic bidirectional conversions (no custom converter needed):

| JavaScript type | Kotlin type | Notes |
|---|---|---|
| `null` | `null` | |
| `undefined` | `null` | |
| `boolean` | `Boolean` | |
| `number` (integer) | `Long`, `Int`, `Short`, `Byte` | specify with reified T |
| `number` (float) | `Double`, `Float` | specify with reified T |
| `string` | `String` | |
| `Array` | `List<Any?>` | elements recursively converted |
| `object` | `JsObject` (extends `Map<String, Any?>`) | |
| `Int8Array`, `UInt8Array`, etc. | typed array variants | |
| `Error` | `QuickJsException` | thrown, not returned |

`JsObject` usage (reading JS objects returned to Kotlin):

```kotlin
val obj = quickJs.evaluate<JsObject>("""
    ({ name: "Alice", age: 30, scores: [95, 87, 92] })
""")
val name = obj["name"] as String        // → "Alice"
val age = obj["age"] as Long            // → 30L  (numbers are Long by default)
val scores = obj["scores"] as List<*>   // → listOf(95L, 87L, 92L)
```

`JsObject` to create objects passed from Kotlin to JS:

```kotlin
// Return a JsObject from a host function to pass a JS object back
quickJs.function("getConfig") { _: Array<Any?> ->
    mapOf(
        "model" to "llama3",
        "maxTokens" to 2048L,
        "temperature" to 0.7,
    ).toJsObject()  // extension fun: Map<String, Any?>.toJsObject(): JsObject
}
// JS: const cfg = getConfig();  cfg.model;  // → "llama3"
```

**Custom type converters** (for domain objects):

```kotlin
// Implement TypeConverter or JsObjectConverter
object FetchParamsConverter : JsObjectConverter<FetchParams> {
    override fun convertToTarget(value: JsObject): FetchParams =
        FetchParams(url = value["url"] as String, method = value["method"] as String)

    override fun convertToSource(value: FetchParams): JsObject =
        mapOf("url" to value.url, "method" to value.method).toJsObject()
}

quickJs.addTypeConverters(FetchParamsConverter)
// Now asyncFunction<FetchParams, String> works transparently
```

Pre-built converters are available as separate artifacts:
- `io.github.dokar3:quickjs-converter-ktxserialization:1.0.8` — kotlinx.serialization
- `io.github.dokar3:quickjs-converter-moshi:1.0.8` — Moshi

---

### 11. Memory Management

```kotlin
// Memory limit (bytes) — throws if JS heap exceeds this
quickJs.memoryLimit = 64 * 1024 * 1024L   // 64 MB

// Stack size — defaults to 256 KB
quickJs.maxStackSize = 512 * 1024L         // 512 KB

// Current usage snapshot
val usage: MemoryUsage = quickJs.memoryUsage

// Manual GC trigger (usually not needed)
quickJs.gc()
```

Auto-cleanup: The native QuickJS runtime is freed when `close()` is called. There is no finalizer — if `close()` is never called, the native memory leaks. Always use `try/finally` or the DSL form.

---

### 12. Short Aliases (Experimental)

The `alias` package provides shorter names, all annotated `@ExperimentalQuickJsApi`:

```kotlin
import com.dokar.quickjs.alias.*

quickJs.def("console") {        // alias for define()
    func("log") { args -> ... } // alias for function()
}
quickJs.func("greet") { args -> ... }           // alias for function()
quickJs.asyncFunc("fetch") { args -> ... }      // alias for asyncFunction()
val result = quickJs.eval<Int>("1 + 2")         // alias for evaluate()

// Inside ObjectBindingScope:
def("nested") { ... }
prop<Int>("value") { getter { 42 } }
func("doThing") { args -> ... }
asyncFunc("doAsync") { args -> ... }
```

These require `@OptIn(ExperimentalQuickJsApi::class)` or a build-level opt-in.

---

### 13. Recommended Pattern for AIpaca Tool Sandbox

Combining all of the above into the concrete pattern for the agent:

```kotlin
class JsSandbox(private val scope: CoroutineScope) {

    private val quickJs = QuickJs.create(Dispatchers.Default)
    private val outputBuffer = StringBuilder()

    init {
        // console capture
        quickJs.define("console") {
            function("log") { args ->
                outputBuffer.append(args.joinToString(" ")).append('\n')
                null
            }
            function("error") { args ->
                outputBuffer.append("[error] ").append(args.joinToString(" ")).append('\n')
                null
            }
        }

        // Tool namespace
        quickJs.define("tools") {
            asyncFunction("readFile") { args ->
                val path = args[0] as String
                withContext(Dispatchers.IO) { File(path).readText() }
            }
            asyncFunction("httpGet") { args ->
                val url = args[0] as String
                httpClient.get(url).bodyAsText()
            }
            // ... additional tools ...
        }

        quickJs.evaluationTimeoutMillis = 30_000L  // 30 s hard JS timeout
    }

    suspend fun execute(script: String): SandboxResult {
        outputBuffer.clear()
        return try {
            val returnValue = withTimeout(35_000L) {  // 35 s wall-clock bound
                quickJs.evaluate<Any?>(script, filename = "agent_script.js")
            }
            SandboxResult.Success(
                output = outputBuffer.toString(),
                returnValue = returnValue,
            )
        } catch (e: QuickJsInterruptedException) {
            SandboxResult.Timeout(e.message)
        } catch (e: QuickJsException) {
            SandboxResult.Error(
                message = e.message,
                fileName = e.fileName,
                lineNumber = e.lineNumber,
                stack = e.stack,
            )
        } catch (e: CancellationException) {
            throw e
        }
    }

    fun close() = quickJs.close()
}
```

---

### 14. Key Limitations and Gotchas

1. **No built-in `console`** — must be defined manually (see Section 7).
2. **No `fetch` / `XMLHttpRequest`** — no Web APIs; all IO must go through host bindings.
3. **No `setTimeout` / `setInterval`** — QuickJS has no event loop scheduler by default. If scripts rely on these, they must also be implemented as host bindings.
4. **Numbers are Long by default** — JS integers come back as `Long`, not `Int`. Cast accordingly.
5. **`evaluate` is suspend** — cannot be called from non-coroutine contexts. Wrap in `runBlocking` only for tests.
6. **`evaluationTimeoutMillis` does not count suspension time** — use `withTimeout {}` to bound total wall time.
7. **Single runtime per `QuickJs` instance** — state (globals, modules) persists across multiple `evaluate` calls on the same instance. For stateless execution, create a new instance per script.
8. **Alias functions require `@OptIn(ExperimentalQuickJsApi::class)`** — don't use in production without opting in.
9. **ProGuard** — reflection-based bindings (JVM target) need ProGuard rules. The AAR ships `consumer-rules.pro`.
10. **Module artifact name**: verify `io.github.dokar3:quickjs-kt` (not `quickjs-kt-android`) on Maven Central before adding to Gradle.

**Tags**: #quickjs #quickjs-kt #android #javascript #api-reference #sandbox #agent #tool-calling #kotlin-coroutines #type-mapping #modules #console-capture #error-handling

---


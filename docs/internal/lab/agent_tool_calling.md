# Lab Journal — Agent & Tool Calling Research

Extracted from the AIpaca lab journal. Research into tool-calling formats, chat templates, and agent orchestration.

---

## 2026-07-29 - Hermes Agent: Tool-Call Round-Trip, Tool Role, and ChatML Format

**Context**: Investigating how NousResearch Hermes Agent handles the full tool-calling round-trip — from model generating a tool call, through tool execution, to feeding the result back — with specific interest in how the `tool` role is used, what the ChatML chat template looks like, and how to handle models whose chat templates do not natively support a `tool` role. This research directly informs AIpaca's `AgentOrchestrator` implementation.

---

### 1. Two Distinct Hermes Projects

There are two separate NousResearch repositories that are commonly conflated:

| Repository | Purpose |
|---|---|
| [`NousResearch/Hermes-Function-Calling`](https://github.com/NousResearch/Hermes-Function-Calling) | Python reference implementation showing how to use Hermes models for function calling via HuggingFace Transformers |
| [`NousResearch/hermes-agent`](https://github.com/NousResearch/hermes-agent) | Full autonomous agent platform (19 000+ commits), multi-platform, OpenAI-wire-compatible, ~40 built-in tools, skill system, memory |

The AIpaca agent work is most directly informed by `Hermes-Function-Calling` (the reference prompt/template format) and `hermes-agent` (the production implementation of the round-trip loop).

---

### 2. Hermes ChatML Format for Tool Calling

Hermes uses **ChatML** as its base format. ChatML wraps every turn with:
```
<|im_start|>{role}
{content}<|im_end|>
```

For tool calling, Hermes extends ChatML with a dedicated `tool` role and special XML-style tags that are trained as **single tokens** in Hermes 2 Pro and later:

| Tag | Meaning |
|---|---|
| `<tools>` / `</tools>` | Wraps the function schema list in the system prompt |
| `<tool_call>` / `</tool_call>` | Wraps a JSON function invocation in the assistant turn |
| `<tool_response>` / `</tool_response>` | Wraps the execution result in the tool turn |

#### System prompt structure
```
<|im_start|>system
You are a function calling AI model. You are provided with function signatures within <tools></tools> XML tags.

<tools>
{"type": "function", "function": {"name": "get_current_temperature", "description": "...", "parameters": {...}}}
</tools>
<|im_end|>
```

#### Model generates a tool call (assistant turn)
```
<|im_start|>assistant
<tool_call>
{"name": "get_current_temperature", "arguments": {"location": "Paris, France", "unit": "celsius"}}
</tool_call>
<|im_end|>
```

Multiple parallel tool calls are supported by emitting multiple `<tool_call>` blocks in a single assistant turn.

#### Tool result fed back (tool turn)
```
<|im_start|>tool
<tool_response>
22.0
</tool_response>
<|im_end|>
```

When multiple tool results are returned in the same turn (from parallel calls), they are grouped under a **single** `<|im_start|>tool` / `<|im_end|>` block, each wrapped in its own `<tool_response>` pair. The Jinja2 template handles this by checking `loop.previtem.role != "tool"` to decide whether to open a new `<|im_start|>tool` header or continue adding `<tool_response>` blocks inside the current one.

---

### 3. The Chat Template (Jinja2) — Key Logic

The `tool_use` chat template in `tokenizer_config.json` (applied via `tokenizer.apply_chat_template(tools=..., chat_template="tool_use")`) contains:

```jinja2
{%- elif message.role == "assistant" %}
    {{- '<|im_start|>' + message.role }}
    {%- for tool_call in message.tool_calls %}
        {{- '\n<tool_call>\n' }}
        {{- '{"name": "' + tool_call.name + '", "arguments": ' + tool_call.arguments|tojson + '}' }}
        {{- '\n</tool_call>' }}
    {%- endfor %}
    {{- '<|im_end|>\n' }}

{%- elif message.role == "tool" %}
    {%- if loop.previtem and loop.previtem.role != "tool" %}
        {{- '<|im_start|>tool\n' }}
    {%- endif %}
    {{- '<tool_response>\n' + message.content }}
    {%- if not loop.last %}
        {{- '\n</tool_response>\n' }}
    {%- else %}
        {{- '\n</tool_response>' }}
    {%- endif %}
    {{- '<|im_end|>' }}
```

Key design decisions:
- Assistant messages that contain tool calls use `message.tool_calls` (a list), not `message.content`, for the tool call bodies
- The `tool` role message uses `message.content` for the raw result text
- `| tojson` is used for argument serialization to guarantee consistent quoting across Python, Rust, and JavaScript

The **generic chatml.j2** template (also in the repo) does NOT have special tool handling — it processes roles verbatim. Tool role handling is only present in the `tool_use` variant stored in `tokenizer_config.json`.

---

### 4. The Round-Trip in `Hermes-Function-Calling` (Reference Implementation)

The `functioncall.py` reference implements a `recursive_loop` nested function inside `generate_function_call`:

```python
# Append assistant response (with tool_calls list)
prompt.append({"role": "assistant", "content": assistant_message})

# Execute the tool
function_response = execute_function_call(tool_call)

# Wrap result in <tool_response> XML
tool_message = f"<tool_response>\n{function_response}\n</tool_response>\n"

# Feed result back as "tool" role message
prompt.append({"role": "tool", "content": tool_message})

# Re-run inference with augmented history
completion = run_inference(prompt)
```

On error (validation failure or execution exception), the error text is wrapped in the same `<tool_response>` tags and the model is prompted to retry with corrected arguments. The loop terminates when either:
- The model generates no further tool calls (natural termination → model produces final answer)
- `max_depth` is reached (default: 5 iterations)

---

### 5. The Round-Trip in `hermes-agent` (Production Implementation)

The production agent (`agent/tool_dispatch_helpers.py` + `agent/conversation_loop.py`) follows the OpenAI wire format internally. The `make_tool_result_message` function builds:

```python
{
    "role": "tool",
    "name": tool_name,
    "tool_name": tool_name,           # internal DB persistence field
    "content": wrapped_result,
    "tool_call_id": tool_call_id,     # links back to the assistant's tool_calls entry
    "_tool_output_risk": risk_level,  # optional security metadata
}
```

This is the standard OpenAI-format tool result. The `tool_call_id` is essential for the model to correlate which tool call produced which result.

#### Security wrapping for untrusted tool outputs
Results from high-risk tools (`web_extract`, `web_search`, `browser_*`, `mcp_*`) are automatically wrapped:
```
<untrusted_tool_result>
{actual result content}
</untrusted_tool_result>
```
Short results (< 32 chars) bypass wrapping. This is a prompt injection defense: the model sees the content as data, not instructions.

---

### 6. Handling Models Without Native Tool Role Support

This is the critical question for AIpaca, where we run models via llama.cpp on-device and cannot assume the model's chat template has a `tool` role.

#### How `hermes-agent` handles this at the adapter layer

The `anthropic_adapter.py` shows how the adapter layer translates the internal `"role": "tool"` representation for providers with different APIs:

```python
# Internally: role="tool" (OpenAI format)
# For Anthropic Messages API: must convert to role="user" with content_type=tool_result

def _convert_tool_message_to_result(msg):
    return {
        "role": "user",   # Anthropic requires "user" for tool results
        "content": [ToolResultBlockParam(
            tool_use_id=msg["tool_call_id"],
            content=msg["content"],
            is_error=False,
        )]
    }
```

The agent keeps the internal representation as `role="tool"` everywhere. Provider-specific adapters translate to whatever the target API needs.

#### For llama.cpp / on-device models without a tool role in the chat template

When the model's chat template (e.g. a generic ChatML or Llama template) does not define a `tool` role, the practical approach is:

**Option A — Inject tool results as `user` role with XML framing (Hermes reference approach)**
```
<|im_start|>user
<tool_response>
{result}
</tool_response>
<|im_end|>
```
The model has been trained on this framing so it recognizes `<tool_response>` tags regardless of which ChatML role carries them. This is what `Hermes-Function-Calling` actually does when using the generic `chatml.j2` template (which has no tool-role handling).

**Option B — Use the tool role anyway if the template passes it through verbatim**
The generic ChatML template (`chatml.j2`) outputs `<|im_start|>{role}` verbatim. If `role = "tool"` is passed, it produces `<|im_start|>tool`. The model may still respond correctly if it was trained with this token, even if the template has no special handler for it.

**Option C — Use the `tool_use` chat template variant**
Models that ship with a `tool_use` template in `tokenizer_config.json` (Hermes 2 Pro, Hermes 3, Hermes 4 via Hugging Face) have full native support. llama.cpp's `--jinja` flag + `--chat-template-file` allows overriding the template at inference time.

#### The `sys_prompt.yml` key constraint
The Hermes system prompt YAML includes this directive:
> "Don't make assumptions about tool results if `<tool_response>` XML tags are not present."

This means the model is trained to look for `<tool_response>` tags as the definitive signal that a tool result has arrived, regardless of which conversation role carries the content.

---

### 7. Summary: What AIpaca's AgentOrchestrator Should Do

| Decision | Recommendation |
|---|---|
| Internal message format | Use `role="tool"` with `tool_call_id` (OpenAI wire format) |
| Content format for tool results | Always wrap in `<tool_response>...</tool_response>` |
| For on-device llama.cpp models | If chat template has no `tool` role handler, fall back to `role="user"` with `<tool_response>` wrapping — the model recognizes the XML tags |
| System prompt | Inject tool schemas in `<tools>...</tools>` XML block inside the system message |
| Tool call parsing | Parse `<tool_call>{"name": ..., "arguments": ...}</tool_call>` from the raw assistant output string |
| Loop termination | No `<tool_call>` tag in assistant response → model is giving final answer |
| Max iterations | 5-10 (Hermes uses 10 in sys_prompt.yml, reference implementation uses 5) |
| Parallel calls | Support multiple `<tool_call>` blocks per assistant turn; group all results in one `<tool_response>` batch before re-inferring |

**Confidence**: High — findings are cross-validated against the reference implementation (`Hermes-Function-Calling`), the production agent (`hermes-agent`), the Hugging Face model card, and the Jinja2 chat template.

**Sources**:
- [NousResearch/Hermes-Function-Calling — GitHub](https://github.com/NousResearch/Hermes-Function-Calling)
- [NousResearch/hermes-agent — GitHub](https://github.com/NousResearch/hermes-agent)
- [Hermes-2-Pro-Llama-3-8B — Hugging Face Model Card](https://huggingface.co/NousResearch/Hermes-2-Pro-Llama-3-8B)
- [Add tool use template — HF Discussion #13](https://huggingface.co/NousResearch/Hermes-2-Pro-Llama-3-8B/discussions/13)
- [llama.cpp function-calling docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)

**Tags**: #agent #tool-calling #hermes #chatml #chat-template #tool-role #llama-cpp #on-device #android #round-trip #openai-format #prompt-injection

---

## 2026-07-29 - Gemma 4 Tool Calling Format and llama.cpp Integration

**Context**: The AIpaca `AgentOrchestrator` is hitting an unparsed tool-call response from `gemma-4-1b-it`. The model outputs a raw token string like `<|tool_call>call:tavily_search{query:<|"|>Gewinner Fußball-WM 2026<|"|>}<tool_call|>` in the assistant `content` field instead of a structured `tool_calls` list. Research objective: understand the format, confirm whether llama.cpp's `common_chat_parse` supports it natively, and establish the correct parsing strategy for the `AgentOrchestrator`.

---

### 1. Gemma 4 Tool Calling Format — Confirmed Official Specification

Gemma 4 uses a **custom non-JSON serialization format** for tool calls. It is NOT the same as Hermes ChatML (`<tool_call>{...}</tool_call>`) or Llama 3.x (`<|python_tag|>`). This is a fundamentally different format that uses native special tokens.

#### Special tokens relevant to tool calling

| Token | Direction | Meaning |
|---|---|---|
| `<|tool_call>` | model output | Opens a tool call block |
| `<tool_call|>` | model output | Closes a tool call block |
| `<|tool_response>` | input to model | Opens a tool result block |
| `<tool_response|>` | input to model | Closes a tool result block |
| `<|tool>` / `<tool|>` | system prompt | Wraps tool schema definitions |
| `<|"|>` | model output | **String value delimiter** — replaces `"` inside call arguments |
| `<|think|>` | model output | Activates thinking mode |
| `<|channel>` / `<channel|>` | model output | Internal reasoning channel markers |

Source: [Google AI for Developers — Gemma 4 Prompt Formatting](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)

#### Exact tool call syntax (output side)

```
<|tool_call>call:FUNCTION_NAME{KEY:<|"|>STRING_VALUE<|"|>,NUM_KEY:42,BOOL_KEY:true}<tool_call|>
```

Key properties:
- Prefix: `call:` (literal, before the function name, no space)
- Arguments: wrapped in `{}`, key-value pairs separated by `,`
- Keys: **unquoted** identifiers (not `"key"` but `key`)
- String values: delimited by `<|"|>` instead of `"` — this is a **single token** (token ID 52), not three characters
- Numeric values: bare digits, no delimiters
- Multiple parallel tool calls: emitted as **concatenated** `<|tool_call>...<tool_call|>` blocks with no separator

Example from `gemma-4-1b-it` (the exact output you are seeing):
```
<|tool_call>call:tavily_search{query:<|"|>Gewinner Fußball-WM 2026<|"|>}<tool_call|>
```
This is **100% correct and expected** Gemma 4 tool call output. The model is behaving correctly. The problem is on the parsing side.

Source: [Google AI — Function Calling with Gemma 4](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)

#### Tool result format (input side)

```
<|tool_response>response:FUNCTION_NAME{key:value}<tool_response|>
```

Or, in practice with text results, the content can be a plain text block inside the token pair.

---

### 2. llama.cpp Chat Format Enum for Gemma 4

llama.cpp introduced a dedicated Parsing Expression Grammar (PEG) parser for Gemma 4 tool calls. The log line `Chat format: peg-gemma4` confirms it is active.

#### Enum value

The format is identified in `common/chat.cpp` as:

```cpp
case COMMON_CHAT_FORMAT_PEG_GEMMA4:
    return "peg-gemma4";
```

This was added in **PR #21326** (merged April 2026). This is distinct from the older `COMMON_CHAT_FORMAT_HERMES_2_PRO`, `COMMON_CHAT_FORMAT_LLAMA_3_X`, etc. — the full enum at the time of research (late chat.hpp snapshot) was:

```
COMMON_CHAT_FORMAT_CONTENT_ONLY
COMMON_CHAT_FORMAT_GENERIC
COMMON_CHAT_FORMAT_MISTRAL_NEMO
COMMON_CHAT_FORMAT_LLAMA_3_X
COMMON_CHAT_FORMAT_LLAMA_3_X_WITH_BUILTIN_TOOLS
COMMON_CHAT_FORMAT_DEEPSEEK_R1
COMMON_CHAT_FORMAT_FIREFUNCTION_V2
COMMON_CHAT_FORMAT_FUNCTIONARY_V3_2
COMMON_CHAT_FORMAT_FUNCTIONARY_V3_1_LLAMA_3_1
COMMON_CHAT_FORMAT_HERMES_2_PRO
COMMON_CHAT_FORMAT_COMMAND_R7B
COMMON_CHAT_FORMAT_PEG_GEMMA4       ← added in PR #21326
COMMON_CHAT_FORMAT_COUNT
```

Note: an older `chat.hpp` snapshot on Hugging Face Spaces does NOT contain `PEG_GEMMA4` — this enum was added after that snapshot was made. If your llama.cpp fork (`janik96v/llama.cpp`, branch `aipaca/android-vulkan-build`) was branched before April 2026, `COMMON_CHAT_FORMAT_PEG_GEMMA4` will not exist in it.

#### How `common_chat_parse` handles peg-gemma4

The `peg-gemma4` parser:
1. Reads the raw model output string
2. Applies a PEG grammar that matches `<|tool_call>call:NAME{...}<tool_call|>` blocks
3. Converts each matched block into a standard `common_chat_tool_call` struct with `name` (string) and `arguments` (JSON object)
4. Converts the `<|"|>` string delimiters back to proper JSON string quoting
5. Returns the result as a `common_chat_msg` with `tool_calls` populated and `content` empty (or containing any text before/after the tool call)

The C++ server (`llama-server`) uses this when tools are provided via the `/v1/chat/completions` endpoint. When `peg-gemma4` is active and working, the client receives a proper OpenAI-format `tool_calls` array, not raw content.

Source: [llama.cpp issue #21384 (confirms "Chat format: peg-gemma4")](https://github.com/ggml-org/llama.cpp/issues/21384)

---

### 3. Why You Are Seeing Raw Tokens in `content` — Root Cause

There are **three distinct failure modes** that produce the `<|tool_call>...<tool_call|>` string appearing in `content` instead of `tool_calls`:

#### Mode A — llama.cpp fork is too old (most likely for AIpaca)

If the `janik96v/llama.cpp` fork predates PR #21326 (merged April 2026), `COMMON_CHAT_FORMAT_PEG_GEMMA4` does not exist. The library falls back to `COMMON_CHAT_FORMAT_CONTENT_ONLY` or `COMMON_CHAT_FORMAT_GENERIC`, which treat all model output as text. The Gemma 4 tokens get detokenized to their string representations and stuffed into `content`.

**Check**: Look for `peg-gemma4` in `common/chat.cpp` of your fork. If absent, this is Mode A.

#### Mode B — Jinja template not enabled

PR #21326 requires the `--jinja` flag (or equivalent `use_jinja = true` in the context params) to activate Jinja2 template parsing, which is what auto-detects `peg-gemma4` from the GGUF's embedded `chat_template`. Without it, the library cannot detect the Gemma 4 format.

#### Mode C — JNI bypass of the chat layer (most relevant for AIpaca)

AIpaca's `LlamaCppEngine.kt` / `llama_jni.cpp` call `llama_decode()` directly and handle tokenization and prompt formatting in Kotlin/C++ manually. If `common_chat_params_init()` is not called, or if the output is read directly from the token stream without passing through `common_chat_parse()`, the raw Gemma 4 tokens will appear verbatim in whatever string is returned.

Source: [llama-cpp-python issue #2227](https://github.com/abetlen/llama-cpp-python/issues/2227)

---

### 4. Known Active Bugs in peg-gemma4 (as of July 2026)

Even if your llama.cpp version is current, these bugs exist in the peg-gemma4 parser:

| Issue | Symptom | Status |
|---|---|---|
| [#21375](https://github.com/ggml-org/llama.cpp/issues/21375) | Infinite repetition loop — model keeps generating the same tool call indefinitely | Fixed in PR #21418 |
| [#21384](https://github.com/ggml-org/llama.cpp/issues/21384) | Array parameters containing `{` or `}` serialized as JSON-encoded string instead of array | Partially fixed; edge cases remain |
| [#22786](https://github.com/ggml-org/llama.cpp/issues/22786) | Tool call returned as `content` when SWA/prompt cache invalidation occurs | Under investigation |
| [#25072](https://github.com/ggml-org/llama.cpp/issues/25072) | "does not match expected peg-gemma4 format" error after tool response when `<|channel>` marker appears without tool structure | Fixed in PR #25100 |

Additionally: Gemma 4 has a documented **ecosystem-wide agentic bug** under long context + reasoning + tool use: the model can malform its own tool calls and then loop on the broken output. This is model-level behavior, not a parser bug.

---

### 5. Correct Approach for AIpaca's AgentOrchestrator

Since AIpaca uses a custom JNI bridge that bypasses the llama.cpp chat abstraction layer, the cleanest approach is **manual parsing in the AgentOrchestrator**.

#### Regex/string parser for Gemma 4 tool calls (Kotlin)

```kotlin
private val GEMMA4_TOOL_CALL_REGEX = Regex(
    """<\|tool_call>call:(\w+)\{(.*?)}<tool_call\|>""",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

private val GEMMA4_STRING_DELIMITER = "<|\"|>"  // token 52, rendered as this string

fun parseGemma4ToolCalls(rawContent: String): List<ToolCall> {
    return GEMMA4_TOOL_CALL_REGEX.findAll(rawContent).map { match ->
        val functionName = match.groupValues[1]
        val argsRaw = match.groupValues[2]
        val arguments = parseGemma4Args(functionName, argsRaw)
        ToolCall(name = functionName, arguments = arguments)
    }.toList()
}

private fun parseGemma4Args(functionName: String, argsRaw: String): Map<String, Any> {
    // Replace <|"|> delimiters with standard JSON quotes and parse key:value pairs
    // NOTE: <|"|> may appear as the literal 5-char string after detokenization
    val normalized = argsRaw.replace("<|\"|>", "\"")
    // Parse: key:"value", key:42, key:true
    // Simple approach: convert to JSON object and parse with kotlinx.serialization or org.json
    val json = "{${normalized.replace(Regex("""(\w+):"""), "\"$1\":")}}"
    return org.json.JSONObject(json).toMap()
}
```

**Important caveat**: The regex key:value-to-JSON conversion above is a simplified example. Production code must handle:
- Nested `{}` in string values (the #21384 bug)
- The `<|"|>` token appearing as a 5-character ASCII sequence (`<`, `|`, `"`, `|`, `>`) after detokenization in some llama.cpp versions, vs. a single character or escape sequence in others

Verify what `LlamaCppEngine` actually produces from token ID 52 by logging the raw string before parsing.

#### How to feed results back to Gemma 4

```kotlin
// Tool result message for Gemma 4
// Format: <|tool_response>response:FUNCTION_NAME{result:<|"|>RESULT_TEXT<|"|>}<tool_response|>
// Or simplified plain text variant:
val toolResult = "<|tool_response>$resultText<tool_response|>"
```

Append this to the conversation before the next model turn.

#### Enabling Jinja + peg-gemma4 via llama-server (alternative path)

If AIpaca eventually routes through `llama-server` locally (e.g., via MCP or HTTP), the server must be started with:
```
--jinja                        # required: enables Jinja2 template parsing
--chat-template-file PATH      # optional: override the embedded template
```
With these flags and a current llama.cpp, the server will auto-detect `peg-gemma4` from the GGUF and return proper `tool_calls` in the response. The PR #21326 + #21343 combination is needed.

Source: [Running OpenCode with Gemma 4 — llama.cpp tool calling gist](https://gist.github.com/daniel-farina/87dc1c394b94e45bb700d27e9ea03193)

---

### 6. Alternative: Switch to JSON-Based Tool Calling via Custom Template

Google maintains a custom Jinja template for Gemma 4 that produces **standard JSON tool calls** (compatible with OpenAI format) instead of the native `call:function_name{}` format. The template converts the model's native tokens into JSON during detokenization.

A community template ([asf0/gemma4_jinja](https://github.com/asf0/gemma4_jinja)) also addresses the thinking-channel (`<|channel>thought`) leakage issue in llama.cpp + OpenWebUI setups, while preserving tool-calling behavior.

Trade-off: custom templates require passing `--chat-template-file` to llama-server or equivalent in the JNI layer, and they must be kept in sync with model updates.

---

### 7. Summary

| Question | Answer |
|---|---|
| Is the output format correct? | Yes — `<\|tool_call>call:func{key:<\|"\|>val<\|"\|>}<tool_call\|>` is exactly what Gemma 4 produces |
| What is the llama.cpp format enum? | `COMMON_CHAT_FORMAT_PEG_GEMMA4` (log: `"Chat format: peg-gemma4"`) |
| Does common_chat_parse support it? | Yes, if PR #21326 is included and `--jinja` is enabled; likely missing from AIpaca's fork |
| What is `call:func{}` syntax? | Custom non-JSON format: unquoted keys, `<\|"\|>` string delimiters, `call:` prefix |
| Is JSON used for arguments? | No — Gemma 4 uses its own dict format, not JSON |
| Root cause in AIpaca? | JNI layer bypasses common_chat_parse; raw tokens reach Kotlin as content string |
| Fix strategy? | Manual regex parser in AgentOrchestrator; OR upgrade fork to include PR #21326 + enable Jinja |
| Known bugs? | Infinite loop (#21375, fixed), array serialization (#21384, partial), SWA cache invalidation (#22786, open) |

**Confidence**: High — format confirmed from official Google docs + multiple llama.cpp issues + vLLM source.

**Sources**:
- [Google AI — Gemma 4 Prompt Formatting](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)
- [Google AI — Function Calling with Gemma 4](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)
- [llama.cpp issue #21384 — Array parameter serialized as string](https://github.com/ggml-org/llama.cpp/issues/21384)
- [llama.cpp issue #21375 — Infinite repetition loop peg-gemma4](https://github.com/ggml-org/llama.cpp/issues/21375)
- [llama.cpp issue #22786 — Tool call returned as content](https://github.com/ggml-org/llama.cpp/issues/22786)
- [llama.cpp issue #25072 — peg-gemma4 format mismatch error](https://github.com/ggml-org/llama.cpp/issues/25072)
- [llama-cpp-python issue #2227 — Raw tokens in content instead of tool_calls](https://github.com/abetlen/llama-cpp-python/issues/2227)
- [vLLM — gemma4 tool parser documentation](https://docs.vllm.ai/en/latest/api/vllm/tool_parsers/gemma4_tool_parser/)
- [Gemma 4 Jinja template — asf0/gemma4_jinja](https://github.com/asf0/gemma4_jinja)
- [llama.cpp tool calling docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)
- [Running OpenCode with Gemma 4 on llama.cpp — gist](https://gist.github.com/daniel-farina/87dc1c394b94e45bb700d27e9ea03193)

**Tags**: #gemma4 #tool-calling #llama-cpp #agent #peg-gemma4 #chat-template #on-device #android #jinja #common_chat_parse #AgentOrchestrator

---

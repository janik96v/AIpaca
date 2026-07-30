# AIpaca Lab Journal

A centralized knowledge repository for the AIpaca development team. Each entry documents research findings, implementation notes, and actionable insights.

---

## 2026-06-05 - Whisper GPU Acceleration via OpenCL (SUCCESS)

**Device**: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750)
**Objective**: Enable GPU-accelerated whisper.cpp transcription using the existing OpenCL/Adreno backend.

### Root Cause of Previous Failures

The earlier assumption that "OpenCL lacks CONV_1D/IM2COL" was **incorrect**. Investigation revealed:

1. **There is no `GGML_OP_CONV_1D` opcode** in ggml. The `ggml_conv_1d_ph()` function decomposes into `ggml_im2col()` + `ggml_mul_mat()` + reshapes at the graph level.
2. **`GGML_OP_IM2COL` returns `true`** unconditionally in the OpenCL backend's `supports_op()`.
3. **All whisper encoder ops are supported**: IM2COL, MUL_MAT, GELU, GROUP_NORM, SOFT_MAX, NORM, ADD, MUL, SCALE, ROPE, DIAG_MASK_INF, CONCAT, PAD, REPEAT, RESHAPE, VIEW, PERMUTE, CPY, CONT, DUP.
4. The `clGetKernelSubGroupInfo` OpenCL 2.1 crash (whisper.cpp issue #3015) was already fixed — commented out with hardcoded subgroup sizes for Adreno (64) and Intel (32).

The **actual culprit was `flash_attn = true`**. The `FLASH_ATTN_EXT` OpenCL kernel produces incorrect results for whisper's specific tensor shapes/head dimensions on Adreno GPUs. Setting `flash_attn = false` while keeping `use_gpu = true` resolves the issue completely.

### Implementation

- `whisper_jni.cpp`: `cparams.use_gpu = true`, `cparams.flash_attn = false`
- GPU probe mechanism added (same SIGSEGV/SIGBUS signal handler pattern as `llama_jni.cpp`)
- Automatic fallback to CPU-only if GPU probe fails
- `WhisperEngine.kt`: loads with GPU → probes → falls back if needed; exposes `isGpuActive`

### Results

- GPU probe: ~730 ms (one-time, at model load)
- Transcription: working correctly with GPU acceleration
- Adreno kernel compilation visible in logcat during first inference

### Key Takeaway

For whisper.cpp on Adreno OpenCL: **always disable `flash_attn`**. The standard attention path (MUL_MAT + SOFT_MAX) works correctly. This is likely an Adreno-specific issue with the FLASH_ATTN_EXT kernel for whisper's encoder attention dimensions.

---

## 2026-06-05 - Vulkan Backend Experiment: llama.cpp + whisper.cpp on Adreno 750

**Device**: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750)
**Objective**: Replace OpenCL with Vulkan for both llama.cpp and whisper.cpp to enable GPU-accelerated Whisper transcription (OpenCL lacks the CONV_1D/IM2COL ops required by the Whisper encoder — see 2026-05-22 entry).
**Outcome**: Both engines fell back to CPU. Reverted all changes.

---

### What Was Done

The build was migrated from OpenCL to Vulkan across 4 files:

- `CMakeLists.txt` — removed OpenCL FetchContent/stub setup, added Vulkan with NDK-bundled `glslc`, swapped `OpenCL::OpenCL` → `Vulkan::Vulkan`
- `build.gradle.kts` — replaced `-DGGML_OPENCL=ON` / `-DGGML_OPENCL_USE_ADRENO_KERNELS=ON` with `-DGGML_VULKAN=ON`
- `AndroidManifest.xml` — swapped `libOpenCL.so` → `libvulkan.so`
- `whisper_jni.cpp` — set `cparams.use_gpu = true`

Two additional headers had to be fetched via FetchContent because the NDK does not bundle them:
- `vulkan.hpp` (Vulkan C++ bindings) — KhronosGroup/Vulkan-Hpp v1.3.275
- `SPIRV-Headers` — KhronosGroup/SPIRV-Headers vulkan-sdk-1.4.304.1

The build succeeded after resolving both missing header errors.

---

### Results

**LLM (llama.cpp)**:
```
LlamaCppEngine  Probing GPU backend...
LlamaCppEngine  GPU probe FAILED — backend crash detected. Reloading in CPU-only mode.
LlamaCppEngine  Model loaded in CPU-only fallback mode
LlamaCppEngine  activeGpuLayers=0
```
The Vulkan backend initializes (Adreno driver loads, `AdrenoVK-0` appears in logcat) but the GPU probe crashes during model load. llama.cpp's built-in GPU probe catches the crash and falls back to CPU. The LLM was faster on OpenCL than on Vulkan CPU fallback.

**Whisper**:
```
AIpacaWhisper  transcribing 27200 samples (1.7 s)
AIpacaWhisper  transcription done (7 chars): najwię
```
1.7 seconds of audio took 10.4 seconds to transcribe — consistent with CPU speed, not GPU. Output was garbled (`"Who are you?"` → `"najwię"`), indicating the same numerical precision corruption seen with OpenCL, just less severe (partial output instead of empty). GPU was not actually used despite `use_gpu = true`.

---

### Root Cause Analysis

The Vulkan backend initializes at the driver level but the GGML compute graph scheduler fails to dispatch whisper's encoder graph to the GPU. This is the same fundamental problem as OpenCL: the GGML GPU backends were designed and optimized for LLM decoder workloads (GEMM-heavy). The Whisper encoder's graph (1D convolutions, specific attention patterns) does not map cleanly to the Vulkan shader pipeline as implemented in this version of ggml.

The GPU probe crash for llama.cpp is a separate issue — likely a shader compilation failure or a Vulkan extension mismatch at model-load time on the Adreno 750 driver version in use (`0762.39`, build date 06/20/25).

---

### Conclusion

| Engine | Backend | Result |
|--------|---------|--------|
| llama.cpp | Vulkan | GPU probe crash → CPU fallback. **Worse than OpenCL.** |
| whisper | Vulkan (`use_gpu=true`) | CPU fallback, garbled output. **Worse than CPU-only.** |
| llama.cpp | OpenCL (reverted to) | GPU working. **Best available.** |
| whisper | CPU-only (reverted to) | Correct output. **Only reliable path.** |

**Decision**: Reverted all changes with `git checkout -- .`. Back to OpenCL for LLM + CPU-only for whisper.

**Future**: The Vulkan GPU probe crash for llama.cpp warrants a separate investigation when time allows. It may be a fixable driver/extension issue. Monitor llama.cpp Vulkan backend progress for Adreno — the backend is more actively maintained than OpenCL and may become viable in a future release.

**Tags**: #vulkan #opencl #android #adreno #llama-cpp #whisper-cpp #gpu #snapdragon #experiment #failed

---

## 2026-05-22 - whisper.cpp OpenCL GPU Backend: Empty Output on Android Adreno (Snapdragon 8 Gen 3)

**Context**: Investigating why `whisper_full()` returns success (exit code 0) but produces 0 segments and 0 characters of text output when `use_gpu=true` with the OpenCL backend on a Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750). This followed a previous attempt with the Vulkan backend that crashed entirely. The goal is to determine whether GPU acceleration is viable at all for whisper.cpp on Android Adreno, and what the recommended path forward is.

---

### 1. Root Cause: OpenCL Backend Does Not Support Whisper's Encoder Operations

**This is the most critical finding.** whisper.cpp's OpenCL/CLBlast backend is built on top of the ggml OpenCL backend, which is designed exclusively as a **matrix multiplication (GEMM) accelerator** — not a full compute backend. It offloads matrix multiplications (the dominant cost in the transformer decoder) but silently falls back to CPU for any operation it does not support.

The Whisper model's **encoder** requires:
1. `GGML_OP_CONV_1D` — two 1D convolution layers (the audio feature extraction stem)
2. `GGML_OP_IM2COL` — used internally to implement convolution as GEMM
3. Various normalization and reshape ops

**None of these convolution operations are implemented in the OpenCL/CLBlast backend.** When these ops encounter an unsupported backend, the behaviour depends on the ggml scheduler version:
- With the legacy `ggml_gallocr_alloc_graph` path: the graph fails silently, producing zero-valued encoder outputs
- With `ggml_backend_sched_alloc_graph`: the scheduler may split the graph, but the corrupt GPU-CPU boundary between encoder (CPU) and decoder (GPU) produces zeroed hidden states

In both cases, the decoder then autoregressively samples from a zeroed context, producing zero tokens — hence `whisper_full()` returns 0 with no error, but 0 segments and 0 characters. The function succeeds in the sense that no exception is thrown, but the inference is producing garbage that the VAD/token filter discards as empty.

**Source**: ggml GitHub issue [#13621 — OpenCL: Add CPU fallback for unsupported operations](https://github.com/ggml-org/llama.cpp/issues/13621) documents the exact mechanism. The issue lists `IM2COL`, `CONV_1D`, `GROUP_NORM`, `NORM`, and 17 other operations as missing from the OpenCL backend.

---

### 2. OpenCL Backend Support Status on Android Adreno

The ggml OpenCL backend (`GGML_OPENCL_USE_ADRENO_KERNELS`) was introduced via [llama.cpp PR #10693](https://github.com/ggml-org/llama.cpp/pull/10693) and is officially supported by Qualcomm.

**Supported operations (LLM inference focus)**:
- GEMM for quantized tensors: Q4_0 (optimized), Q6_K, Q8_0, F16, F32
- Full and partial GPU layer offload via `-ngl`

**NOT supported** (as of May 2026):
- Flash attention
- Q4_K and other K-quant / I-quant types
- Convolution operations (IM2COL, CONV_1D, CONV_2D)
- Mel spectrogram operations
- Many normalization and reshape ops needed by non-LLM models

**Verified supported Adreno GPUs**: Adreno 750 (Snapdragon 8 Gen 3), Adreno 830 (Snapdragon 8 Elite), Adreno X85 (Snapdragon X Elite).

**Known driver issue**: Qualcomm Adreno reports OpenCL 2.0 support but lacks the `clGetKernelSubGroupInfo` symbol, which belongs to OpenCL 2.1. This causes `libggml-opencl.so` to fail to load on some devices with a linker error. See [whisper.cpp issue #3015](https://github.com/ggml-org/whisper.cpp/issues/3015).

**Older Adreno GPUs in phones** (A6x series): Not supported due to outdated drivers and compilers, even though IoT platforms with the same silicon and newer drivers do work.

**Bottom line**: The OpenCL backend in ggml/whisper.cpp is designed for **LLM text generation** (decoder-heavy GEMM), not for Whisper's encoder pipeline. Using it with whisper.cpp will always produce empty output because the encoder convolutions cannot run on the GPU and the graph scheduler silently produces zero outputs.

**Sources**:
- [Qualcomm Developer Blog — Introducing OpenCL GPU Backend in llama.cpp for Adreno](https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu)
- [llama.cpp OpenCL backend documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md)
- [llama.cpp PR #10693 — Experimental OpenCL backend for Adreno](https://github.com/ggml-org/llama.cpp/pull/10693)
- [whisper.cpp issue #1140 — OpenCL Android support](https://github.com/ggml-org/whisper.cpp/issues/1140) (contributor states: "OpenCL is kinda broken on android this isn't a problem with whisper.cpp it's an OpenCL problem")

---

### 3. Vulkan Backend on Android Adreno: Known Crash Issues

The Vulkan backend was attempted before OpenCL and crashed. Research confirmed this is a well-documented issue:

**Root cause**: The ggml Vulkan backend requires `VK_KHR_16bit_storage` (fp16 support in shader storage buffers). Many Adreno GPUs — including Adreno 610, 630, and 640 — report `fp16: 0` in their Vulkan capabilities. The ggml Vulkan initializer performs a hard capability check and throws `std::runtime_error("Unsupported device")` rather than gracefully degrading.

**Error message**:
```
ggml_vulkan: device Vulkan0 does not support 16-bit storage.
libc++abi: terminating due to uncaught exception of type std::runtime_error: Unsupported device
```

**Status of fix**: A PR ([#3719 — Fix/vulkan adreno crashes](https://github.com/ggml-org/whisper.cpp/issues/3035)) has been opened but was not yet merged as of the time of research. The workaround confirmed to work is disabling GPU entirely and using CPU-only inference.

**Adreno 750 (Snapdragon 8 Gen 3)**: This GPU is newer and does support fp16 storage, so the 16-bit storage crash should NOT occur on a Samsung Galaxy S24 Ultra. However, there are separate Adreno shader compilation bugs in the Vulkan backend. A related llama.cpp issue ([#5186 — Subtle Vulkan shader compilation bug on Adreno Samsung Galaxy S23 Ultra](https://github.com/ggml-org/llama.cpp/issues/5186)) documents shader compilation failures on S23 Ultra (Adreno 740), which is the prior generation to the S24 Ultra (Adreno 750).

**Additional Vulkan issues**:
- [whisper.cpp issue #3455 — Vulkan support broken at v1.8.0](https://github.com/ggml-org/whisper.cpp/issues/3455)
- [whisper.cpp issue #3168 — Vulkan backend crashes with DeviceLost](https://github.com/ggml-org/whisper.cpp/issues/3168)
- [whisper.cpp issue #3611 — Vulkan crash on AMD RDNA1 during buffer initialization](https://github.com/ggml-org/whisper.cpp/issues/3611)

**Sources**:
- [whisper.cpp issue #3035 — ggml_vulkan: device Vulkan0 does not support 16-bit storage](https://github.com/ggml-org/whisper.cpp/issues/3035)
- [whisper.cpp issue #2765 — Unsupported device error with Vulkan on Android](https://github.com/ggml-org/whisper.cpp/issues/2765)
- [llama.cpp issue #5186 — Subtle Vulkan shader compilation bug on Adreno S23 Ultra](https://github.com/ggml-org/llama.cpp/issues/5186)

---

### 4. Android GPU Acceleration Alternatives

#### NNAPI (Neural Networks API)
**Status: Deprecated as of Android 15.** Google deprecated NNAPI in Android 15 (2024) and is migrating to TensorFlow Lite in Play Services and AICore. For new development, NNAPI should not be targeted.
- [Android NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)

#### Hexagon DSP (Qualcomm)
- Qualcomm's Hexagon DSP can accelerate quantized models significantly (MobileNet under 25ms vs. 60-65ms on CPU).
- Access to Hexagon from native code requires Qualcomm's QNN SDK or via NNAPI/TFLite Hexagon delegate.
- whisper.cpp has **no native Hexagon DSP integration**. This would require porting to QNN or a TFLite-converted model.

#### LiteRT / TFLite GPU Delegate
- A GitHub issue ([whisper.cpp #2413](https://github.com/ggml-org/whisper.cpp/issues/2413)) requests LiteRT support for Android GPU acceleration. The issue was opened September 2024 with no resolution as of research date.
- This would require converting the Whisper model to TFLite format, which is non-trivial.

#### OpenVINO (Intel only)
- OpenVINO encoder acceleration is documented in whisper.cpp but is Intel-exclusive (x86 CPUs, Intel integrated/discrete GPUs). Not applicable to Qualcomm Adreno.

#### Core ML / Metal (Apple only)
- Apple platform only. Not applicable to Android.

#### Practical recommendation for Adreno 750 / Snapdragon 8 Gen 3
There is no production-ready, stable GPU acceleration path for whisper.cpp on Android Adreno as of May 2026. The recommended approach is **CPU-only inference with quantized models**:
- Quantized Whisper models (Q4_0, Q8_0 GGUF) run at practical speeds on Snapdragon 8 Gen 3 ARM cores
- The `tiny` and `base` quantized models finish in 1-3x real-time on flagship Snapdragon devices
- `small` quantized models are feasible for non-real-time use cases

**Sources**:
- [whisper.cpp issue #2413 — LiteRT Android GPU support request](https://github.com/ggml-org/whisper.cpp/issues/2413)
- [TensorFlow issue #48349 — NNAPI vs GPU vs Hexagon delegates](https://github.com/tensorflow/tensorflow/issues/48349)
- [NNAPI Explained 2025 Guide](https://medium.com/softaai-blogs/nnapi-explained-the-ultimate-2025-guide-to-androids-ai-acceleration-33c0087f2ddf)

---

### 5. Practical Fix for AIpaca

**Immediate fix**: Set `use_gpu = false` unconditionally in the Android JNI layer. This bypasses both the OpenCL silent-zero-output problem and the Vulkan crash. CPU-only inference on Snapdragon 8 Gen 3 with a quantized tiny or base model is fast enough for real-time voice input.

**Code change** (JNI layer / whisper_wrapper):
```cpp
// Force CPU-only on Android — OpenCL produces empty output,
// Vulkan crashes on many Adreno devices. See docs/lab_journal.md.
params.use_gpu = false;
```

**Model size recommendations for Android**:
| Model | Quantization | GGUF size | Expected speed (Snapdragon 8 Gen 3) |
|---|---|---|---|
| tiny | Q8_0 | ~42 MB | ~0.3x real-time (very fast) |
| base | Q8_0 | ~78 MB | ~0.6x real-time (fast) |
| small | Q5_0 | ~180 MB | ~1.5x real-time (usable) |
| medium | Q4_0 | ~470 MB | ~4x real-time (slow, not for STT) |

**Future GPU path (monitor)**: Watch for a stable Vulkan backend release that handles Adreno fp16 storage gracefully and includes the Adreno shader compilation fixes. The llama.cpp Vulkan backend has more active maintenance than the OpenCL backend and may become viable for whisper.cpp in H2 2025.

---

### Summary

| Question | Answer |
|---|---|
| Why does OpenCL return success but 0 segments? | OpenCL backend lacks CONV_1D / IM2COL ops; encoder runs with zero outputs silently |
| Is OpenCL encoder supported on Adreno? | No — only GEMM (decoder) ops are supported |
| Does Vulkan work on Adreno 750? | Crashes on older Adreno; fp16 storage crash may not hit Adreno 750 but shader bugs persist |
| Is NNAPI an option? | Deprecated in Android 15; no whisper.cpp integration |
| Is Hexagon DSP an option? | No native whisper.cpp support; requires QNN/TFLite port |
| Best current approach? | CPU-only with quantized model (tiny/base Q8_0) |

**Tags**: #whisper-cpp #android #opencl #vulkan #adreno #gpu #snapdragon #speech-to-text #on-device #ggml #bug-investigation

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

## 2026-05-12 - Tencent HY-MT1.5-1.8B On-Device Translation Model

**Context**: Evaluating Tencent's HY-MT1.5-1.8B as a candidate for on-device machine translation within the AIpaca Android app. Need to understand format, language coverage, hardware feasibility, quantization options, licensing, and integration path.

---

### 1. Model Architecture

- **model_type**: `hunyuan_v1_dense`
- **Architecture class**: `HunYuanDenseV1ForCausalLM` (Tencent Hunyuan proprietary dense decoder)
- **Base model**: `HY-1.8B-Base` — Tencent's internal pretrained base, then CPT (continuous pretraining) + SFT + on-policy distillation + RL for translation
- **Parameters**: 1.8 billion
- **Layers**: 32 transformer decoder layers
- **Hidden size**: 2,048
- **FFN intermediate size**: 6,144
- **Attention heads**: 16 (4 key-value heads — grouped query attention / GQA)
- **Head dim**: 128
- **Vocab size**: 120,818 tokens
- **Context window**: 262,144 tokens (256K)
- **Positional encoding**: Rotary (RoPE) with dynamic scaling (alpha=1000)
- **Normalization**: RMSNorm (epsilon 1e-5)
- **Activation**: SiLU (Swish)
- **Data type**: bfloat16
- **Special features**: QK normalization enabled, attention bias disabled

### 2. Model Format and Size

- **Primary weights file**: `model.safetensors` — **4.08 GB** (BF16)
- **Framework**: HuggingFace Transformers (`AutoModelForCausalLM`)
- **Recommended Transformers version**: 4.56.0
- **Tokenizer files**: `tokenizer.json` (9.53 MB), `tokenizer_config.json` (166 kB), `chat_template.jinja`, `special_tokens_map.json`

**Repository file listing (HuggingFace)**:
```
.gitattributes       1.52 kB
License.txt         16.3 kB
README.md            9.0 kB
chat_template.jinja    654 B
config.json          1.34 kB
generation_config.json  221 B
model.safetensors    4.08 GB   <-- primary weights
special_tokens_map.json 488 B
tokenizer.json       9.53 MB
tokenizer_config.json 166 kB
```

---

### 2. Supported Languages

**36 languages + dialects (1,056 translation directions)**:

| Group | Languages |
|---|---|
| East Asian | Chinese (Simplified), Chinese (Traditional), Japanese, Korean, Cantonese, Tibetan, Mongolian, Uyghur |
| Southeast Asian | Vietnamese, Thai, Malay, Indonesian, Filipino, Khmer, Burmese |
| South Asian | Hindi, Bengali, Telugu, Marathi, Gujarati, Urdu, Tamil |
| European | English, French, Portuguese, Spanish, Italian, German, Polish, Czech, Dutch, Russian, Ukrainian |
| Middle Eastern / Central Asian | Arabic, Persian, Hebrew, Kazakh, Turkish |

---

### 3. On-Device / Android Feasibility

**Short answer: Yes, with the 1.25-bit or GGUF Q4_K_M quantized variant.**

Tencent explicitly designed the 1.8B model for edge/on-device deployment after quantization. There is an official Android demo APK available.

**Official Android Demo APK**:
- Download: https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT-demo.apk
- Features: offline translation, background word-picking mode (works across any app without switching), no internet required, no data collection.

**Demo hardware tested on**:
- Snapdragon 888, 865, 7+ Gen 2
- 8–16 GB RAM devices

**Performance (1.25-bit variant on Snapdragon 888)**:
- Average response time: ~0.18 seconds for ~50-token Chinese inputs
- 8x speedup vs FP16 on Snapdragon 888

---

### 4. Quantized Versions Available

Multiple quantization tiers are officially released by Tencent:

| Variant | Format | Size | Use Case |
|---|---|---|---|
| `HY-MT1.5-1.8B` | SafeTensors (BF16/FP16) | 4.08 GB | Server/Cloud inference |
| `HY-MT1.5-1.8B-FP8` | FP8 (compressed-tensors) | ~2 GB est. | GPU inference, reduced VRAM |
| `HY-MT1.5-1.8B-GPTQ-Int4` | GPTQ Int4 | ~1 GB est. | GPU/CPU inference, mobile-adjacent |
| `HY-MT1.5-1.8B-GGUF` | GGUF (Q4_K_M / Q6_K / Q8_0) | 1.13–1.91 GB | llama.cpp, Ollama, LM Studio |
| `Hy-MT1.5-1.8B-2bit` | 2-bit weights | ~574 MB | Ultra-compact on-device |
| `Hy-MT1.5-1.8B-1.25bit` | 1.25-bit (Sherry/STQ) | **440 MB** | Mobile/Android deployment |

**HuggingFace links**:
- Base: https://huggingface.co/tencent/HY-MT1.5-1.8B
- GGUF: https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF
- FP8: https://huggingface.co/tencent/HY-MT1.5-1.8B-FP8
- GPTQ-Int4: https://huggingface.co/tencent/HY-MT1.5-1.8B-GPTQ-Int4
- 2-bit: https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit
- 1.25-bit weights: https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit
- 1.25-bit GGUF: https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF

**FP8 known issue**: Rename `"ignored_layers"` to `"ignore"` in `config.json` and upgrade to `compressed-tensors==0.11.0`.

---

### 5. Hardware Requirements

| Variant | Estimated RAM Required | Notes |
|---|---|---|
| FP16 base (4.08 GB) | 6–8 GB RAM | Server/desktop only |
| GPTQ-Int4 / GGUF Q4_K_M (1.1 GB) | ~2–3 GB RAM | Capable desktop / high-end phone |
| 2-bit (574 MB) | ~1–2 GB RAM | Mid-range Android |
| 1.25-bit (440 MB) | ~1 GB RAM | Target for most modern Android phones |

**Tencent's stated requirement for the 1.25-bit variant**: Deployable on ordinary phones with limited memory. The STQ kernel uses custom SIMD instructions for mobile CPUs.

---

### 6. License

**License**: Tencent HY Community License Agreement (Version HY-MT1.5, released December 30, 2025)

**Full license text**: https://huggingface.co/tencent/HY-MT1.5-1.8B/resolve/main/License.txt

**Key points**:
- Non-exclusive, non-transferable, royalty-free for permitted uses
- **Commercial use is allowed** below 100M monthly active users
- If your product exceeds **100 million MAU**, you must obtain a separate commercial license from Tencent
- **Geographic restriction**: Applies worldwide EXCEPT the European Union, United Kingdom, and South Korea — usage in those territories is NOT permitted under this license
- Must include license copy when redistributing; must note modifications
- Governed by laws of Hong Kong SAR
- Prohibitions include: military use, high-stakes automated decisions (medicine, law, credit), generating undisclosed AI content

**For AIpaca**: The EU/UK/South Korea restriction is a significant concern if the app targets those markets. Legal review recommended before shipping.

---

### 7. Input/Output Format

#### Tokenizer
- Type: `AutoTokenizer` with `apply_chat_template()`
- Chat template is defined in `chat_template.jinja`
- No system prompt is used (the model card explicitly states: "this model does not have a default system prompt")

#### Prompt Formats for Translation

**Chinese <-> Other Language** (use Chinese instruction):
```
将以下文本翻译为{target_language}，注意只需要输出翻译后的结果，不要额外解释：

{source_text}
```

**Non-Chinese <-> Non-Chinese**:
```
Translate the following segment into {target_language}, without additional explanation.

{source_text}
```

**With Terminology Intervention** (glossary enforcement):
```
参考下面的翻译：
{source_term} 翻译成 {target_term}

将以下文本翻译为{target_language}，注意只需要输出翻译后的结果，不要额外解释：
{source_text}
```

**With Context** (document-level continuity):
```
{context}
参考上面的信息，把下面的文本翻译成{target_language}，注意不需要翻译上文，也不要额外解释：
{source_text}
```

**Formatted Translation** (with inline markup tags preserved):
```
将以下<source></source>之间的文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释，原文中的<sn></sn>标签表示标签内文本包含格式信息，需要在译文中相应的位置尽量保留该标签。输出格式为：<target>str</target>

<source>{src_text_with_format}</source>
```

#### Full Python Usage Example
```python
from transformers import AutoModelForCausalLM, AutoTokenizer

model_name = "tencent/HY-MT1.5-1.8B"
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForCausalLM.from_pretrained(model_name, device_map="auto")

messages = [
    {
        "role": "user",
        "content": "Translate the following segment into Chinese, without additional explanation.\n\nIt's on the house."
    },
]

tokenized_chat = tokenizer.apply_chat_template(
    messages,
    tokenize=True,
    add_generation_prompt=False,
    return_tensors="pt"
)

outputs = model.generate(
    tokenized_chat.to(model.device),
    max_new_tokens=2048,
    top_k=20,
    top_p=0.6,
    repetition_penalty=1.05,
    temperature=0.7,
)
output_text = tokenizer.decode(outputs[0])
```

#### Recommended Inference Parameters
```json
{
  "top_k": 20,
  "top_p": 0.6,
  "repetition_penalty": 1.05,
  "temperature": 0.7,
  "max_new_tokens": 2048
}
```

---

### 8. Android/Mobile Deployment Examples

#### Option A: 1.25-bit STQ via llama.cpp (lowest memory, best for Android)

Uses **Sherry**, a ternary quantization framework (accepted at ACL 2026). The technique uses 3:4 fine-grained sparsity: for every 4 weights, 3 are stored in 1-bit {-1, +1} and 1 is zeroed. This gives effective 1.25-bit width and packs 4 weights into 5 bits.

```bash
# Use the STQ-enabled llama.cpp fork
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
git fetch origin pull/22836/head:pr-22836-stq_0
git checkout pr-22836-stq_0

# Build
cmake -B build && cmake --build build --config Release

# Quantize from BF16 GGUF
./build/bin/llama-quantize model.bf16.gguf model.STQ1_0.gguf STQ1_0

# Run inference (CPU-only, -ngl 0)
./build/bin/llama-completion \
  --model model.STQ1_0.gguf \
  -p "Translate the following segment into Chinese, without additional explanation.\n\nHello world." \
  -ngl 0 \
  -n 64
```

**Note**: The STQ_0 kernel is a pull request (PR #22836), not yet merged into llama.cpp main as of May 2026. Monitor for merge status before shipping.

#### Option B: GGUF Q4_K_M via standard llama.cpp (more portable)

```bash
# Install llama.cpp
brew install llama.cpp

# Run directly from HuggingFace
llama-server -hf tencent/HY-MT1.5-1.8B-GGUF:Q4_K_M
llama-cli -hf tencent/HY-MT1.5-1.8B-GGUF:Q4_K_M
```

GGUF Q4_K_M is 1.13 GB and can be embedded in an Android app using:
- llama.cpp Android bindings (via JNI)
- MLC-LLM (MLC Chat Android SDK)
- MediaPipe LLM Inference API (if model is converted to TFLite)

#### Option C: Pre-built Android APK (for reference/testing)
- APK: https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT-demo.apk
- Demonstrates background word-picking mode and offline capability

---

### Summary Table

| Dimension | Details |
|---|---|
| Architecture | Causal LM (decoder-only transformer) |
| Base size | 4.08 GB (BF16/FP16 SafeTensors) |
| Best mobile size | 440 MB (1.25-bit STQ) / 1.13 GB (GGUF Q4_K_M) |
| Languages | 36 languages, 1,056 direction pairs |
| Android demo | Yes (official APK available) |
| License | Tencent HY Community License — free under 100M MAU; EU/UK/South Korea excluded |
| Inference framework | HuggingFace Transformers, llama.cpp, Ollama, LM Studio |
| Prompt format | Chat template via `apply_chat_template()`; no system prompt |
| Special tokens | Standard chat tokens; see `chat_template.jinja` |
| Key risk | EU/UK/South Korea geographic restriction in license |

---

### Sources

- [HuggingFace: tencent/HY-MT1.5-1.8B](https://huggingface.co/tencent/HY-MT1.5-1.8B)
- [HuggingFace: tencent/HY-MT1.5-1.8B-GGUF](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF)
- [HuggingFace: tencent/Hy-MT1.5-1.8B-1.25bit](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit)
- [HuggingFace: AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF (Android APK)](https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF)
- [HuggingFace: tencent/HY-MT1.5-1.8B-GPTQ-Int4](https://huggingface.co/tencent/HY-MT1.5-1.8B-GPTQ-Int4)
- [HuggingFace: tencent/Hy-MT1.5-1.8B-2bit-GGUF](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF)
- [GitHub: Tencent-Hunyuan/HY-MT](https://github.com/Tencent-Hunyuan/HY-MT)
- [License.txt (Tencent HY Community License)](https://huggingface.co/tencent/HY-MT1.5-1.8B/resolve/main/License.txt)
- [arXiv: HY-MT1.5 Technical Report (2512.24092)](https://arxiv.org/abs/2512.24092)
- [MarkTechPost: Tencent HY-MT1.5 Release](https://www.marktechpost.com/2026/01/04/tencent-researchers-release-tencent-hy-mt1-5-a-new-translation-models-featuring-1-8b-and-7b-models-designed-for-seamless-on-device-and-cloud-deployment/)
- [Tool Navs: 1.25-bit mobile analysis](https://toolnavs.com/en/article/1746-tencent-hy-mt15-18b-125bit-open-source-440mb-mobile-phone-offline-translation-mo)

**Tags**: #translation #on-device #android #llama-cpp #gguf #quantization #tencent #mobile-ml #nlp #multilingual

---

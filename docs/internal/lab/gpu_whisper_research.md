# Lab Journal — GPU & Whisper Research

Extracted from the AIpaca lab journal. GPU acceleration experiments for whisper.cpp and llama.cpp on Adreno.

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

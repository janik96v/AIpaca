# LamaPhone Lab Journal

A living knowledge repository for the LamaPhone project — tracking research findings, implementation notes, and architectural decisions.

---

## 2026-05-02 - Gemma-4 4B GGUF Models and Vulkan Quantization Compatibility

**Context**: The app currently uses `gemma-4-E4B-it-UD-IQ2_M.gguf` (from unsloth). IQ2_M quantization causes a CPU fallback on Android because the llama.cpp Vulkan backend has no `get_rows` shader registered for `GGML_TYPE_IQ2_M`. The goal is to identify a replacement GGUF file that is fully Vulkan-GPU-accelerated on Qualcomm Adreno hardware.

---

### 1. Vulkan get_rows Shader Support — Definitive Source Code Analysis

Verified directly from `ggml/src/ggml-vulkan/ggml-vulkan.cpp` (master branch, May 2026).

The `pipeline_get_rows` array is populated via `ggml_vk_create_pipeline` calls. Only types with an explicit registration have a working Vulkan shader. Types absent from this list fall back silently to CPU.

#### Types WITH Vulkan get_rows shader (GPU-accelerated):

| Quantization | Notes |
|---|---|
| F32, F16, BF16 | Full precision floats |
| Q1_0 | 1-bit legacy |
| Q4_0, Q4_1 | 4-bit legacy |
| Q5_0, Q5_1 | 5-bit legacy |
| Q8_0 | 8-bit legacy, excellent quality |
| Q2_K | 2-bit k-quant |
| Q3_K | 3-bit k-quant |
| Q4_K | 4-bit k-quant (Q4_K_S and Q4_K_M use this type) |
| Q5_K | 5-bit k-quant (Q5_K_S and Q5_K_M use this type) |
| Q6_K | 6-bit k-quant |
| IQ1_S, IQ1_M | 1-bit importance quants |
| IQ2_XXS, IQ2_XS, IQ2_S | 2-bit importance quants (but NOT IQ2_M) |
| IQ3_XXS, IQ3_S | 3-bit importance quants (but NOT IQ3_M) |
| IQ4_XS, IQ4_NL | 4-bit importance quants |
| MXFP4, NVFP4, I32 | Special types |

#### Types WITHOUT Vulkan get_rows shader (CPU fallback):

| Quantization | Status |
|---|---|
| **IQ2_M** | **MISSING — CPU fallback. This is the current model's type.** |
| IQ3_M | MISSING — CPU fallback |
| Q2_K_L, Q4_K_L, Q5_K_L, Q6_K_L | "_L" suffix variants — check status, likely use base K type |

**Source**: `ggml/src/ggml-vulkan/ggml-vulkan.cpp`, lines 4389–4415, `ggml_vk_create_pipeline` registrations for `pipeline_get_rows[]`.

---

### 2. Available Gemma-4 E4B GGUF Models on HuggingFace

#### bartowski/google_gemma-4-E4B-it-GGUF
URL: https://huggingface.co/bartowski/google_gemma-4-E4B-it-GGUF

Complete file list (verified from model card):

| Filename | Quant Type | File Size | Vulkan get_rows |
|---|---|---|---|
| gemma-4-E4B-it-bf16.gguf | BF16 | 15.05 GB | YES |
| gemma-4-E4B-it-Q8_0.gguf | Q8_0 | 8.03 GB | YES |
| gemma-4-E4B-it-Q6_K_L.gguf | Q6_K_L | 7.18 GB | likely YES (Q6_K base) |
| gemma-4-E4B-it-Q5_K_L.gguf | Q5_K_L | 6.67 GB | likely YES (Q5_K base) |
| gemma-4-E4B-it-Q6_K.gguf | Q6_K | 6.33 GB | YES |
| gemma-4-E4B-it-Q4_K_L.gguf | Q4_K_L | 6.25 GB | likely YES (Q4_K base) |
| gemma-4-E4B-it-Q3_K_XL.gguf | Q3_K_XL | 5.88 GB | likely YES (Q3_K base) |
| gemma-4-E4B-it-Q5_K_M.gguf | Q5_K_M | 5.82 GB | YES |
| gemma-4-E4B-it-Q5_K_S.gguf | Q5_K_S | 5.70 GB | YES |
| gemma-4-E4B-it-Q4_1.gguf | Q4_1 | 5.46 GB | YES |
| gemma-4-E4B-it-Q4_K_M.gguf | Q4_K_M | 5.41 GB | YES |
| gemma-4-E4B-it-Q2_K_L.gguf | Q2_K_L | 5.30 GB | likely YES (Q2_K base) |
| gemma-4-E4B-it-Q4_K_S.gguf | Q4_K_S | 5.24 GB | YES |
| gemma-4-E4B-it-IQ4_NL.gguf | IQ4_NL | 5.23 GB | YES |
| gemma-4-E4B-it-Q4_0.gguf | Q4_0 | 5.22 GB | YES |
| gemma-4-E4B-it-IQ4_XS.gguf | IQ4_XS | 5.11 GB | YES |
| gemma-4-E4B-it-Q3_K_L.gguf | Q3_K_L | 5.03 GB | YES (Q3_K base) |
| gemma-4-E4B-it-Q3_K_M.gguf | Q3_K_M | 4.90 GB | YES (Q3_K base) |
| gemma-4-E4B-it-IQ3_M.gguf | IQ3_M | 4.77 GB | NO — CPU fallback |
| gemma-4-E4B-it-Q3_K_S.gguf | Q3_K_S | 4.70 GB | YES (Q3_K base) |
| gemma-4-E4B-it-IQ3_XS.gguf | IQ3_XS | 4.63 GB | likely NO (IQ3_XXS is supported, not IQ3_XS) |
| gemma-4-E4B-it-Q2_K.gguf | Q2_K | 4.46 GB | YES |
| gemma-4-E4B-it-IQ2_M.gguf | IQ2_M | 3.96 GB | NO — CPU fallback (current model) |

#### unsloth/gemma-4-E4B-it-GGUF
URL: https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF

Uses "UD-" prefix (Unsloth Dynamic) variants. Key files:

| Filename | Quant Type | File Size | Vulkan get_rows |
|---|---|---|---|
| gemma-4-E4B-it-UD-IQ2_M.gguf | IQ2_M | 3.53 GB | NO — CPU fallback (current model) |
| gemma-4-E4B-it-UD-Q2_K_XL.gguf | Q2_K (XL embed) | 3.74 GB | YES |
| gemma-4-E4B-it-UD-IQ3_XXS.gguf | IQ3_XXS | 3.7 GB | YES |
| gemma-4-E4B-it-Q3_K_S.gguf | Q3_K_S | 3.86 GB | YES |
| gemma-4-E4B-it-Q3_K_M.gguf | Q3_K_M | 4.06 GB | YES |
| gemma-4-E4B-it-UD-Q3_K_XL.gguf | Q3_K (XL embed) | 4.56 GB | YES |
| gemma-4-E4B-it-IQ4_XS.gguf | IQ4_XS | 4.72 GB | YES |
| gemma-4-E4B-it-Q4_K_S.gguf | Q4_K_S | 4.84 GB | YES |
| gemma-4-E4B-it-IQ4_NL.gguf | IQ4_NL | 4.84 GB | YES |
| gemma-4-E4B-it-Q4_0.gguf | Q4_0 | 4.84 GB | YES |
| gemma-4-E4B-it-Q4_K_M.gguf | Q4_K_M | 4.98 GB | YES |
| gemma-4-E4B-it-UD-Q4_K_XL.gguf | Q4_K (XL embed) | 5.1 GB | YES |
| gemma-4-E4B-it-Q5_K_S.gguf | Q5_K_S | 5.4 GB | YES |
| gemma-4-E4B-it-Q5_K_M.gguf | Q5_K_M | 5.48 GB | YES |
| gemma-4-E4B-it-Q6_K.gguf | Q6_K | 7.07 GB | YES |
| gemma-4-E4B-it-Q8_0.gguf | Q8_0 | 8.19 GB | YES |

---

### 3. Recommended Models for Android Vulkan / Adreno Inference

Priority criteria: (1) Full Vulkan get_rows support, (2) Good quality-to-size ratio, (3) Fits in device RAM (typically 6–12 GB on flagship Android).

#### Top Recommendations

| Rank | File | Source | Size | Quant | Reason |
|---|---|---|---|---|---|
| 1 | `gemma-4-E4B-it-Q4_K_M.gguf` | bartowski | 5.41 GB | Q4_K_M | Best all-around balance; Q4_K is the most-tested Vulkan quant; widely recommended default |
| 2 | `gemma-4-E4B-it-Q4_K_S.gguf` | bartowski | 5.24 GB | Q4_K_S | Slightly smaller than Q4_K_M with minimal quality loss; good for tighter RAM budgets |
| 3 | `gemma-4-E4B-it-Q4_K_M.gguf` | unsloth | 4.98 GB | Q4_K_M | Unsloth's slightly smaller version of the same quant type |
| 4 | `gemma-4-E4B-it-IQ4_XS.gguf` | bartowski | 5.11 GB | IQ4_XS | Smaller than Q4_K_S with good quality; Vulkan shader added in PR #11501 |
| 5 | `gemma-4-E4B-it-Q5_K_M.gguf` | bartowski | 5.82 GB | Q5_K_M | Higher quality if RAM allows; fully Vulkan-supported |
| 6 | `gemma-4-E4B-it-IQ4_NL.gguf` | bartowski | 5.23 GB | IQ4_NL | Good alternative to IQ4_XS; Vulkan-supported |
| 7 | `gemma-4-E4B-it-Q3_K_M.gguf` | bartowski | 4.90 GB | Q3_K_M | If 5 GB is too large; acceptable quality, fully Vulkan-supported |

#### Download Commands

```bash
# bartowski Q4_K_M (recommended)
huggingface-cli download bartowski/google_gemma-4-E4B-it-GGUF \
  --include "gemma-4-E4B-it-Q4_K_M.gguf" --local-dir ./models/

# bartowski Q4_K_S (smaller alternative)
huggingface-cli download bartowski/google_gemma-4-E4B-it-GGUF \
  --include "gemma-4-E4B-it-Q4_K_S.gguf" --local-dir ./models/

# unsloth Q4_K_M
huggingface-cli download unsloth/gemma-4-E4B-it-GGUF \
  --include "gemma-4-E4B-it-Q4_K_M.gguf" --local-dir ./models/
```

---

### 4. Additional Notes

#### Why IQ2_M Fails on Vulkan
The llama.cpp Vulkan backend registers GPU shader pipelines for `GET_ROWS` operations at device initialization time (ggml-vulkan.cpp lines 4389–4415). The IQ2_M type (`GGML_TYPE_IQ2_M`) has no entry in this list. When the model attempts an embedding lookup (which uses `GET_ROWS`), llama.cpp silently falls back to CPU for those tensors. This affects all `IQ2_M` and `IQ3_M` models.

#### Adreno Vulkan Performance Context
There are known performance issues with the Vulkan backend on mobile Adreno GPUs (GitHub discussion #9464). Some users report GPU inference being slower than CPU in pathological cases. For Adreno, Qualcomm also provides an OpenCL backend (`--cl` flag) that may outperform Vulkan. Consider benchmarking both paths with Q4_K_M.

#### Q4_K_M vs Q4_0 for Adreno
Qualcomm's own OpenCL backend documentation recommends Q4_0 (not Q4_K_M) with `--pure` flag for maximum Adreno performance. However, Q4_K_M has significantly better quality than Q4_0 at similar sizes. For Vulkan specifically, Q4_K_M is the preferred choice.

#### The `_L` and `_XL` suffix variants
These are bartowski/unsloth conventions meaning the embedding and output weight tensors are quantized to a higher bit depth (e.g., Q8_0) while the transformer layers use the base quantization. They are not separate GGML types — the per-tensor type is still Q4_K, Q5_K, etc. — so they inherit full Vulkan support from the base type.

---

**Tags**: #gemma4 #gguf #quantization #vulkan #android #adreno #llama-cpp #model-selection

**Sources**:
- [bartowski/google_gemma-4-E4B-it-GGUF](https://huggingface.co/bartowski/google_gemma-4-E4B-it-GGUF)
- [unsloth/gemma-4-E4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF)
- [ggml-vulkan.cpp — pipeline_get_rows registrations](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/ggml/src/ggml-vulkan/ggml-vulkan.cpp)
- [vulkan: initial IQ4_XS support PR #11501](https://github.com/ggml-org/llama.cpp/pull/11501)
- [Vulkan Android performance discussion #9464](https://github.com/ggml-org/llama.cpp/discussions/9464)
- [Qualcomm OpenCL backend for Adreno](https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu)

---

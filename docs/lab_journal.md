# AIpaca Lab Journal

A centralized knowledge repository for the AIpaca development team. Each entry documents research findings, implementation notes, and actionable insights.

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

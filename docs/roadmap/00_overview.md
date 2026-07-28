# AIpaca Roadmap — Agent-Neubau + KV-Prefix-Cache

Zwei aufeinanderfolgende PRs, abgeleitet aus der konsolidierten Spec
(`research_notes/spec_agent_and_kv_consolidated.md`) und der KV-Spec
(`research_notes/spec_kv_prefix_cache.md`).

## Warum diese Reihenfolge

Der bestehende Agent-Mode (`com.aipaca.app.agent.*`) hat ein solides MCP-Fundament, aber eine
**faule Modell↔Tool-Brücke**: native Tool-Calls sind nicht verdrahtet (`inputs.tools` wird im JNI nie
gesetzt), Tool-Calls werden per Regex geparst, Tool-Ergebnisse laufen als `user`-Rolle. **Erst das
Fundament sauber machen, dann optimieren** — KV-Caching auf einem regex-basierten, rollen-falschen Loop
bringt wenig.

## PR1 — Agent-Foundation (native Tool-Calls)
Ziel: Ein funktionierender, sauberer Agent-Loop mit **nativem** Tool-Calling über llama.cpps
Jinja-Template + PEG-Parser, echten Tool-Rollen und Streaming. MCP-Schicht wird wiederverwendet.
→ Details: `PR1_agent_foundation.md`

## PR2 — KV-Prefix-Cache (Hebel F)
Ziel: Warm-KV-Reuse über Turns (engine-layer, agent-agnostisch). Größter Gewinn im Multi-Turn-Agent.
→ Details: `PR2_kv_prefix_cache.md`

## Ausgangslage (verifiziert 2026-07-28)
- Branch: `feature/kv-cache-q8` (Hebel A / KV-Quant bereits drin: q8_0 K immer, q8_0 V+FA nur CPU).
- Engine: `EngineState` (Singleton, eine `LlamaCppEngine`/`llama_context`, `generateMutex`).
- JNI: `llama_jni.cpp` nutzt `common_chat_templates_apply` (Jinja) — aber ohne `inputs.tools`.
- Server (`ApiServer`) + Chat-UI unverändert lassen (Non-Goal-Grenze).
- Kein Test-Harness vorhanden (Greenfield) → PR1 Increment 0.

## Verifizierte llama.cpp-Chat-API (aus dem Submodul `common/chat.h`)
- `struct common_chat_tool { std::string name, description, parameters; }` (parameters = JSON-Schema-String)
- `struct common_chat_tool_call { std::string name, arguments, id; }` (arguments = JSON-String)
- `struct common_chat_msg { role, content, ..., std::vector<common_chat_tool_call> tool_calls, tool_name, tool_call_id; }`
- `common_chat_templates_inputs` hat `std::vector<common_chat_tool> tools`, `tool_choice`, `parallel_tool_calls`, `enable_thinking`, `reasoning_format`.
- `common_chat_params common_chat_templates_apply(tmpls, inputs)` → `.prompt`, `.format`, `.thinking_*`.
- `common_chat_msg common_chat_parse(const std::string& input, bool is_partial, const common_chat_parser_params& params)`;
  `common_chat_parser_params` ist aus `common_chat_params` konstruierbar.

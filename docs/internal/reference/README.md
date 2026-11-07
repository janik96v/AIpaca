# Hermes Agent — Reference Analysis

This folder contains a **reference analysis** of the [NousResearch Hermes agent](https://github.com/nousresearch/hermes-agent) architecture. It is not part of firn's current implementation.

The analysis documents patterns and techniques that may inform future firn features, particularly:

- **Programmatic tool calling** — zero-context RPC via in-process code execution
- **File-based skill system** — progressive disclosure with compact index
- **Learning loop** — counter-triggered background review with anti-poisoning
- **FTS5 session search** — cross-session recall without embeddings
- **Prompt caching strategies** — frozen snapshots, stable prefix tiers

See [docs/roadmap.md](../../roadmap.md) for how these ideas map to firn's future roadmap.

## Files

| File | Content |
|---|---|
| `hermes_erklaert.md` | Deep analysis of Hermes architecture (6 core ideas, edge portability) |
| `programmatic_tool_calling_explained.html` | Detailed explanation of the zero-context RPC pattern |

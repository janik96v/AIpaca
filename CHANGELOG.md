# Changelog

All notable changes to AIpaca are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Planned
- In-app HuggingFace model browser
- Chat history persistence (Room DB)
- GPU acceleration (Vulkan / OpenCL)
- Multi-request queuing
- Multimodal / vision support

---

## [0.1.0] – 2025-05-12

### Added
- On-device LLM inference via llama.cpp (GGUF models, ARM64)
- OpenAI-compatible HTTPS server on port 8443 (`POST /v1/chat/completions`, `GET /v1/models`)
- Server-Sent Events streaming for token output
- Ed25519 asymmetric key authentication (SSH-style pairing)
- Self-signed TLS certificate generated on first launch
- QR-code and 6-digit PIN pairing flow
- Built-in streaming chat UI (Jetpack Compose + Material 3)
- Android foreground service — server stays alive with screen off
- Python example scripts (`chat.py`, `pair.py`)
- Full API client guide (`docs/api-client-guide.md`)

[Unreleased]: https://github.com/janikvollenweider/AIpaca/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/janikvollenweider/AIpaca/releases/tag/v0.1.0

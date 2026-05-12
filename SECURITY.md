# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |

## Reporting a Security Issue

Please **do not** open a public GitHub issue for security-sensitive findings.

Instead, use **GitHub's private vulnerability reporting**:
[https://github.com/janik96v/AIpaca/security/advisories/new](https://github.com/janik96v/AIpaca/security/advisories/new)

Please include:

- A description of the issue and the potential impact
- Steps to reproduce or a minimal proof-of-concept
- Any suggested fixes you may have

You can expect an acknowledgement within **48 hours** and a status update within **7 days**.

## Disclosure Policy

Once a fix is available we will:

1. Release a patched version
2. Publish a GitHub Security Advisory
3. Credit the reporter (unless you prefer to remain anonymous)

## Scope

In scope: the AIpaca Android app, the local HTTPS server, the pairing and authentication mechanisms, the JNI/C++ bridge to llama.cpp.

Out of scope: third-party libraries (llama.cpp, Ktor, …) — please report those upstream.

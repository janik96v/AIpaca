# Contributing to AIpaca

Thanks for your interest in contributing! AIpaca is an Apache-2.0 licensed open-source project, and contributions of all kinds are welcome — bug reports, feature ideas, documentation fixes, code, or just feedback.

This document explains how to get a working development environment, the conventions we follow, and the pull-request workflow.

## Quick links

- **Found a bug?** Open an issue using the "Bug Report" template.
- **Have an idea?** Open an issue using the "Feature Request" template, or start a thread in Discussions.
- **Security issue?** Please don't open a public issue — see [SECURITY.md](SECURITY.md).

## Getting started

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog 2023.1+ |
| JDK | 17 |
| Android NDK | r27.2.12479018 |
| CMake | 3.22+ |
| Device | API 28+, ≥4 GB RAM (real device required — emulators are not supported because AIpaca builds only `arm64-v8a`) |

### Build from source

```bash
git clone https://github.com/<your-fork>/AIpaca.git
cd AIpaca
git submodule update --init --recursive   # pulls llama.cpp
```

Open the project in Android Studio, let Gradle sync, then `Run` on a connected Android device.

For a release build from the CLI:

```bash
./gradlew assembleRelease
```

(Requires a signing config — see [docs/api-client-guide.md](docs/api-client-guide.md).)

## How to contribute

### Reporting bugs

Open an issue with the **Bug Report** template. The more of these you can fill in, the faster we can help:

- Device model + Android version
- Model file you were using (name + quantization)
- Exact steps to reproduce
- What you expected vs. what happened
- Logcat output (filter by `aipaca` tag)

### Suggesting features

Open an issue with the **Feature Request** template. Before opening, please check existing issues and the [Roadmap](README.md#roadmap) — your idea might already be tracked.

### Submitting code

1. **Discuss first** for non-trivial changes. Open an issue or a draft PR describing your plan before writing significant code — this saves everyone time.
2. **Fork** the repository and create a topic branch from `main`:
   ```bash
   git checkout -b feature/short-description
   ```
3. **Make your changes.** Keep PRs focused; one logical change per PR.
4. **Test on a real device.** AIpaca builds `arm64-v8a` only, so emulators won't work.
5. **Update the changelog** under `## [Unreleased]` in `CHANGELOG.md` if your change is user-visible.
6. **Open a pull request** against `main`. Reference the issue you're fixing in the PR description.

### Commit messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add support for streaming SSE responses
fix(server): reject auth header older than 60s
docs: clarify NDK setup steps
chore(deps): bump kotlinx-serialization to 1.7
```

Allowed types: `feat`, `fix`, `docs`, `chore`, `refactor`, `perf`, `test`, `build`, `ci`.

Conventional commits enable automated changelog generation later and make `git log` actually useful.

### Code style

- **Kotlin**: follow the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide). 4-space indent, no tabs.
- **C++** (JNI bridge): follow [Google C++ Style Guide](https://google.github.io/styleguide/cppguide.html) loosely. Match surrounding code.
- **Compose**: prefer stateless composables; keep state in ViewModels.
- **No new dependencies** without prior discussion — every dependency increases APK size and supply-chain risk.

### What we look for in PRs

- A clear description of *why* (not just *what*).
- Small, reviewable diffs.
- No unrelated changes.
- Tests where reasonable (we're early-stage, but please add coverage for non-trivial logic).
- Updated documentation if behavior changes.

## Areas we'd love help with

If you're looking for something to work on, these are particularly impactful:

- **GPU acceleration** (OpenCL / Vulkan backends for llama.cpp on mobile)
- **Model browser** integrated with HuggingFace
- **Multimodal support** (LLaVA, vision models)
- **Chat history persistence** with Room DB
- **iOS port** (shared C++ core, SwiftUI shell)
- **Documentation, screenshots, and example clients**

## Contributor License

By submitting a contribution, you agree that your contribution is licensed under the Apache License 2.0, the same license as the project (see [LICENSE](LICENSE)).

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you're agreeing to uphold it.

Thanks again — small contributions make a real difference.

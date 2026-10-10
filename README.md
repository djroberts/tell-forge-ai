# TellForgeAI

A WebStorm / PhpStorm (IntelliJ Platform) plugin: a bring-your-own-key AI coding
assistant with **Foretell** and **Forge** modes, powered by any
OpenAI-compatible API and with **multiple named provider profiles and a
per-project provider/model dropdown**.

## Features (MVP — chat-first)

- **Tool window** ("TellForgeAI", right dock) with streaming chat.
- **Foretell / Forge mode toggle** — different system prompts; optionally a different
  model for Foretell mode per provider.
- **Provider profiles**: name, base URL, API key, model, optional Foretell model.
  Managed in *Settings → Tools → TellForgeAI Providers*. Profiles are stored
  application-wide in `tellforgeai.xml` (shared across projects).
- **Per-project selection**: the dropdown in the tool window picks which
  profile this project uses (remembered per project).
- **Project context & tools**: every request includes a project snapshot (name,
  root, git branch, directory tree), and the model can call tools — `list_files`,
  `read_file`, `search_files`, and `run_command` (always user-approved; in Foretell
  mode restricted to read-only commands) — in an agentic loop, so it can explore
  the project before answering. In Forge mode, `download_binary` can fetch an HTTPS
  resource as raw bytes into the project after explicit approval (up to 50 MiB).
- Works with any OpenAI-compatible endpoint (`POST {baseUrl}/chat/completions`,
  SSE streaming, OpenAI function-calling format). e.g. OpenAI
  (`https://api.openai.com/v1`), OpenRouter, Ollama (`http://localhost:11434/v1`),
  LM Studio, Together, etc.

## Building

```bash
./gradlew buildPlugin        # -> build/distributions/tellforgeai-0.1.0.zip
./gradlew runIde             # sandbox IDE for development
```

Requires JDK 21 (toolchain auto-provisioned via Foojay resolver).

## Installing

Download the latest zip from [GitHub Releases](https://github.com/djroberts/tell-forge-ai/releases/latest)
(*Assets* → `tellforgeai-x.y.z.zip`), then in the IDE: *Settings → Plugins → ⚙ →
Install Plugin from Disk…*.

## Releasing (fully automated)

Merges to `main` are versioned and released automatically:

- PR title starting with `fix:` → patch bump (`0.1.0` → `0.1.1`)
- `feat:` → minor bump (`0.1.0` → `0.2.0`)
- `feat!:` (or a `BREAKING CHANGE:` footer) → major bump (`0.1.0` → `2.0.0`)
- No `feat:`/`fix:` commits since the last tag → no release

The workflow builds the plugin, tags the commit (`vX.Y.Z`) and attaches the zip
to a GitHub Release with auto-generated notes. The version lives in the tag —
no version file to maintain.

## Changelog

Release notes live in [CHANGELOG.md](CHANGELOG.md). During development, changes
accumulate under `## [Unreleased]` — every `feat:`/`fix:` commit updates it
(see `AGENTS.md` for the exact rules). When the release workflow cuts a
version, it converts `Unreleased` into a dated `## [X.Y.Z]` section, commits
that, and uses it as the GitHub Release notes.

## Roadmap (full agentic core, next phase)

- Tool-use loop: read/search/edit files with diff-based approve/reject.
- Terminal command execution with approval (Forge mode).
- Checkpoints, workspace context, MCP support.

## License

Licensed under the [MIT License](LICENSE).

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
- **Automatic context compaction**: when a known context window reaches 80% usage, older completed turns are summarized for future requests. The full transcript remains in chat history; ordinary history trimming is used if summarization fails.
- Works with any OpenAI-compatible endpoint (`POST {baseUrl}/chat/completions`,
  SSE streaming, OpenAI function-calling format). e.g. OpenAI
  (`https://api.openai.com/v1`), OpenRouter, Ollama (`http://localhost:11434/v1`),
  LM Studio, Together, etc.

## Building

```bash
./gradlew buildPlugin        # -> build/distributions/tellforgeai-0.0.0-dev.zip
./gradlew runIde             # sandbox IDE for development
```

Requires JDK 21 (toolchain auto-provisioned via Foojay resolver).

## Installing

Download the latest zip from [GitHub Releases](https://github.com/djroberts/tell-forge-ai/releases/latest)
(*Assets* → `tellforgeai-x.y.z.zip`), then in the IDE: *Settings → Plugins → ⚙ →
Install Plugin from Disk…*.

## Releasing

The release version and notes are maintained by a person in `CHANGELOG.md`; GitHub Actions does not calculate or change the version.

To prepare a release, add a dated version section immediately below `## [Unreleased]`, for example:

```markdown
## [Unreleased]

### Added
- Changes planned for a later release

## [0.4.0] - 2026-10-18
### Added
- Add the user-curated notes for version 0.4.0
```

Commit and push this changelog change to `main`. The release workflow reads the newest dated version section, validates it against existing version tags, runs tests, builds the plugin with that version, and creates a GitHub Release with the section's notes and the plugin zip. It tags the same commit with `vX.Y.Z`. It does **not** commit or push anything to `main`. An existing tag for the selected version is treated as already released and is skipped. Keep `## [Unreleased]` at the top to collect changes after the release.

The version is passed to Gradle as `-PpluginVersion=X.Y.Z` at release-build time; `CHANGELOG.md` is the only manually maintained release-version source.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Add user-facing changes under `## [Unreleased]`; only add a dated version section when deliberately preparing that release. See `AGENTS.md` for contributor instructions.

## Roadmap (full agentic core, next phase)

- Tool-use loop: read/search/edit files with diff-based approve/reject.
- Terminal command execution with approval (Forge mode).
- Checkpoints, workspace context, MCP support.

## License

Licensed under the [MIT License](LICENSE).

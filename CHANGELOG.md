# Changelog

All notable changes to TellForgeAI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.7.0] - 2026-10-15

### Added
- Dedicated **OpenRouter** provider protocol: a new "OpenRouter" entry in the API-protocol dropdown that pre-fills `https://openrouter.ai/api/v1`, sends the OpenRouter-specific `HTTP-Referer` and `X-Title` headers, and exposes an OpenRouter-specific settings panel with site URL, app title, provider routing (`order`, `allow_fallbacks`, `ignore`, `only`, `sort`), quantization/data-collection/ZDR/require-parameters filters, `max_price` limits, preferred throughput/latency targets, `plugins` JSON and a catch-all extra-body JSON field (`feat(settings)`)
- `injectOpenRouterBody` request-body enrichment in `ProviderProfile`, covered by new `OpenRouterBodyTest` unit tests (`feat(llm)`)

### Fixed
- API-key handling is now whitespace-safe: keys are trimmed when saved and when read, and `ApiKeyStore.resolve` prefers the transient in-memory value over PasswordSafe so freshly pasted keys are used without requiring an IDE restart (`fix(settings)`)
- Clear error messages when no API key is configured instead of sending a request with a missing `Authorization` header; failed responses now log and report whether the Authorization header was actually sent, plus a network interceptor that logs auth-header presence and warns on cross-host redirects that may drop it (`fix(llm)`)
- Provider dropdown in the chat panel now hydrates API keys before comparing profiles, so key rotations in PasswordSafe are reflected when settings are applied (`fix(ui)`)

## [0.6.0] - 2026-10-14

### Added
- Tela wiki integration: three new agent tools (`wiki_search`, `wiki_read_page`, `wiki_write_page`) that let the model search, read and create/update pages on any tela wiki instance, authenticated with a personal access token stored in the IDE PasswordSafe (`feat(tools)`)
- Per-project Tela wiki configuration dialog (Wiki button in the tool window gear menu) with base URL, masked token field, a one-time write-opt-in checkbox and a "Test connection" button that verifies the token before anything is saved (`feat(ui)`)
- `TellForgeAIWikiState` persistent service that roams the wiki URL and endpoint path with the project (`feat(settings)`)
- Global "Max pasted image size" setting (256 / 512 / 1024 / 2048 / Original) in the providers configurable, letting users cap the longest side of pasted images before they are sent to the model (`feat(settings)`)
- `@path` token support: `referencedPaths` field on `ChatMessage` records which files were referenced via `@path` for display and history (`feat(chat)`)

### Changed
- Image token estimation in `HistoryTrimmer` is now size-aware — it scales with base64 payload length instead of a flat 1,300 tokens per image, reducing waste for small pasted screenshots (`perf(chat)`)

## [0.5.3] - 2026-10-13

### Fixed
- Ignore-rule changes are now picked up without an IDE restart: `IgnoreRules` caches a fingerprint (name, last-modified, size) of each ignore file and `VfsTools` reloads rules automatically when `.gitignore` or an AI ignore file (`.aiignore`, `.cursorignore`, ...) is created or edited. Previously, a rule added after the first tool call was silently ignored until restart (`fix(tools)`)

## [0.5.2] - 2026-10-13

### Fixed
- API keys no longer disappear after an IDE restart when the settings file is reset: known profile ids are now persisted, so the "Recover API keys" dialog actually offers orphaned PasswordSafe keys back instead of silently dropping them (`fix(settings)`)
- Blank API-key fields no longer wipe stored PasswordSafe entries on Apply unless the key was deliberately cleared in the settings UI (`fix(settings)`)

## [0.5.1] - 2026-10-12

### Fixed
- Reset the context usage meter and per-turn tool counter when starting a new chat or opening an existing one; the meter previously kept showing the previous chat's token usage until the first send (`fix(ui)`)

## [0.5.0] - 2026-10-12

### Added
- Automatically compact older completed conversation turns into a persisted summary when context usage reaches 80%; the full chat transcript remains intact and existing trimming is used if summarization fails

## [0.4.0] - 2026-10-12
### Added
- Download binary files from HTTPS URLs into the project with explicit approval (tools)

### Fixed
- Show all permitted Foretell commands in the rejection message (tools)

## [0.3.1] - 2026-10-10
- See commit history.

## [0.3.0] - 2026-10-10
### Added
- CopyLink action in the chat panel (`feat(ui)`)
- Rebranded the plugin as TellForgeAI (`feat`)

### Fixed
- Keep unsaved provider keys visible when switching profiles and use them for connection tests, model fetches, and context detection (`fix(settings)`)
- Corrected command output parsing in CommandTool (`fix(tools)`)
- Clarified that mutations work in Forge mode via run_command (`fix(tools)`)
- Fixed the semver bump loop that aborted under `set -e` in CI (`fix(ci)`)

# Changelog

All notable changes to TellForgeAI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

# Changelog

All notable changes to TellForgeAI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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

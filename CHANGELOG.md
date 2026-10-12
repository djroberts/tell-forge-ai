# Changelog

All notable changes to TellForgeAI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
<<<<<<< HEAD
The `## [0.3.1] - 2026-10-10
- See commit history.
=======
## [Unreleased]

### Added
- Download binary files from HTTPS URLs into the project with explicit approval (tools)
>>>>>>> aded50d (feat(tools): add approved binary downloads)

### Fixed
- Show all permitted Foretell commands in the rejection message (tools)

## [0.3.0] - 2026-10-10
### Added
- CopyLink action in the chat panel (`feat(ui)`)
- Rebranded the plugin as TellForgeAI (`feat`)

### Fixed
- Keep unsaved provider keys visible when switching profiles and use them for connection tests, model fetches, and context detection (`fix(settings)`)
- Corrected command output parsing in CommandTool (`fix(tools)`)
- Clarified that mutations work in Forge mode via run_command (`fix(tools)`)
- Fixed the semver bump loop that aborted under `set -e` in CI (`fix(ci)`)

## [Unreleased]` section is curated by hand (and by AI agents — see
`AGENTS.md`) as part of each `feat:`/`fix:` commit. The release workflow moves
it into a dated `## [X.Y.Z]` section and uses it as the GitHub Release notes.


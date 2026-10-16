# Changelog

All notable changes to TellForgeAI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.10.0] - 2026-10-16

### Fixed
- Attachment blocks (file/image/video/pdf) could not be deleted by the agent: `notion_read_page` only emitted `(block_id: …)` markers for text blocks, so the model had no block id to target and wrongly concluded Notion's API cannot delete files. Attachment block ids are now included in the markers (with the file name/caption shown next to `[file]`/`[image]` lines), and `notion_update_block` docs/state clarify that attachments are deletable with `archive=true` (`fix(tools)`)

### Added
- Text-extension fallback for Notion uploads: when a file's bytes are plain text but its extension is one Notion's File Upload API rejects (`.asc`, `.log`, `.conf`, …), the upload is sent under a Notion-friendly name with the original extension embedded as a suffix (`foo.asc` → `foo_asc.txt`, content type `text/plain`, result message notes the rename); extensions Notion already accepts are never renamed (`feat(tools)`)
- `notion_move_file` tool: moves a file-attachment or image block between Notion pages by downloading it to a temp file (explicitly deleted when the call ends, success or failure), re-uploading via the File Upload API, attaching to the target page and archiving the original block last; external-URL attachments are moved by re-embedding the URL with no download. The Notion API cannot re-parent file blocks, so this is an explicit copy-then-delete (`feat(tools)`)
- Duplicate-page guard for Notion and tela wiki writes: `notion_write_page` refuses to create a page when one with the same or a near-identical title (stripped of trailing "2"/"(copy)"/"notes" qualifiers) already exists, and `wiki_write_page` does the same on create, pointing the model to `notion_append_blocks`/`notion_update_block` (or replacing the tela page) instead (`feat(tools)`)

### Changed
- Tool descriptions and search-output hints now push "look before you create": `notion_search`/`wiki_search` results remind the model to check for existing pages, `notion_write_page` requires a search first, `notion_append_blocks` is marked as the preferred write tool, and `wiki_write_page` explains that an existing title replaces the page body in one shot (`feat(tools)`)

## [0.9.0]

### Changed
- `instructions_write` no longer exposes a `path` argument to the model: the AI instructions are presented as a single document — no repo, no file names, no directory layout in any tool description or tool output. Legacy calls still passing a `path` fail with an explanatory error unless they target the canonical file, and the mutating service layer rejects any path other than the canonical `INSTRUCTIONS.md` as tooling-level enforcement (`fix(tools)`).

## [0.8.0] - 2026-10-18

### Fixed
- Configured Notion write roots refused ALL writes under them, including children of the root itself: Notion returns ids dashed (search results, raw UUID args) while the persisted root id is store dashless, so every id comparison failed and the containment walk classified the target as outside the root ("parent 'TellForgeAI' is outside it" with the root set to TellForgeAI). All Notion ids are now compared in a canonical dashless form — `WriteRoot.normalizeNotionId`, applied in `isUnderNotionRoot`, `writePage`/`updatePage`/`appendBlocks`, and `resolveTitleToId` (`fix(tools)`)
- Write-root picker in Configure Integrations now shows real page titles instead of raw Notion UUIDs (pages via the existing title extraction, databases via the `title` rich-text array) with the id kept at the end (`fix(ui)`)
- The same tela search that fed the picker is ranked full-text over titles AND bodies, so a title like "child" was buried under unrelated body-matches. Title matches are now bubbled to the top of the suggestions list, and each hit shows its breadcrumb (Space › Parent › …) so same-titled pages can be told apart (`fix(ui)`)
- The suggestions popup opened centered on the write-root field, covering what was typed, and grabbing focus meant further keystrokes never reached the field. It now opens just below the field, unfocusable and refreshed in place (`fix(ui)`)


### Changed
- The separate **Wiki** and **Config** toolbar buttons were merged into a single **Integrations** button: "Configure Tela Wiki" and "Configure Instructions Repo" are now three tabs of one "Configure Integrations" dialog (Tela wiki · Notion · AI instructions), each with a one-line info banner and a tab tooltip so the purpose of each integration is visible on sight. All existing per-tab behaviour — connection tests, write opt-ins and per-action approval toggles — is unchanged. Model-facing tool hints now point at "Configure Integrations…" instead of the removed dialog names (`feat(ui)`)

### Fixed
- `notion_search` with an empty query now lists all pages the integration can access: the `query` field is omitted rather than sent as `""` (Notion treats the two differently); also hardened title parsing against unexpected JSON shapes (`fix(tools)`)
- The Configure Instructions Repo "Test / Sync now" result showed raw HTML tags (`<html>`, `<br>`, `<b>`) as literal text: the timer prefix ("after 1s —") was prepended outside the `<html>` opening tag, so Swing fell back to plain-text rendering. All result strings are now wrapped in a single well-formed HTML label update (`fix(ui)`)
- A successful auto-seed of a default `INSTRUCTIONS.md` was reported in the sync result as "Error: … Instructions loaded anyway". Informational sync outcomes (seeded default, missing branch created locally) now travel in a dedicated `Snapshot.note` field and are shown as a success note, reserving `error` for real failures (`fix(llm)`)

### Added
- **Instructions tab** in the tool window (next to Chat): a live mono editor for the repo's canonical `INSTRUCTIONS.md` with a tight status line (`synced 2m ago (3 files)`), Sync now, Open in editor, Configure…, and Save & push… — saving goes through the same diff-preview → approval → commit+push path (and write opt-in) the agent's `instructions_write` uses, with a Revert for unsaved edits. Unsaved edits are never silently overwritten by a background refresh (`feat(ui)`)
- `notion_describe_database` agent tool: describes a Notion database's schema (property names, types and select/status/multi-select options via `GET /v1/databases/{id}`); read-only, both modes, no approval. `notion_query_database`'s spec now tells the model to call it first instead of guessing property names, and a `validation_error` on a query now auto-fetches and appends the schema so the agent self-corrects in one round trip (`feat(tools)`)
- Canonical `INSTRUCTIONS.md` convention: bootstrapping an empty instructions repo now seeds a starter `INSTRUCTIONS.md` (guidelines-for-writing-guidelines starter content) instead of an empty tree that would only produce the confusing "no .md files" error again (`feat(llm)`)
- Instructions-local paths in `instructions_write` and the UI editor are now rebased onto the repo root for git (`add` / `checkout` / `reset`) when a `subpath` is configured — previously `git add` ran against a path relative to the content root and silently no-opped (or failed) for subpath repos (`feat(llm)`)

### Changed
- The "no .md files found" sync error is now self-diagnosing: it appends a listing of what the repo actually contains (`repo contains: README.md, docs/ …`) and a distinct message when the configured subdirectory itself is missing — previously the bare message left you guessing whether the branch was empty, the files were in a subdir, or the URL was wrong (`fix(llm)`)
- Instructions-repo layer: a new **Config** button (next to Wiki) opens "Configure Instructions Repo", pointing a project at a separate — typically private and versioned — git repository that holds AI agent instructions kept out of (possibly public) project repositories. Accepted URLs: `owner/repo[/subdir]`, full https/ssh URLs, optional `#subdir` fragment. The plugin shallow-clones into the plugin temp dir, refreshes on a TTL, and keeps the merged markdown in memory so building a system prompt never runs git; on failure the last-known-good copy is served. Instructions are injected as a "User instructions (configuration repo)" section of the system prompt (`feat(settings)`)
- Two agent tools for that repo: `instructions_read` (both modes, returns the raw merged markdown) and `instructions_write` (Forge only, requires a separate write opt-in): writes the content, shows the diff, and — after an always-on approval dialog, even when command approval is disabled — commits and pushes to the repo, with a polite `pull --rebase` retry on push rejection; declining reverts the working-tree change (`feat(tools)`)
- Tests for the instructions-repo URL parsing (`owner/repo`, subdirectories, `#fragment`, ssh/https forms, rejection of host-prefixed input) (`test(settings)`)

## [0.7.3] - 2026-10-17

### Added
- Per-action write permissions for Tela wiki and Notion in the Configure Tela Wiki dialog: below the single "Allow the agent to create, update and delete pages" opt-in there are now three checkboxes — **"Ask permission to add page"**, **"Ask permission to update page"** and **"Ask permission to delete page"** — all checked by default, so every mutating tool call pops a confirmation dialog unless the user unticks the matching action (`feat(settings)`)
- `wiki_delete_page` agent tool: deletes a tela wiki page by page id or exact title (resolves the title first so the confirmation names what is deleted); Forge mode only, gated by the write opt-in and the "Ask permission to delete page" checkbox (`feat(tools)`)
- Extendable Notion tool set is now wired to the same per-action approval flags: `notion_update_page` / `notion_update_block` follow the update checkbox, `notion_write_page` / `notion_append_blocks` / `notion_create_comment` follow the add checkbox (`feat(tools)`)

### Fixed
- Write approvals were broken and inconsistent: the Notion tool called a non-existent `skipApproval()` method (compile error), `NotionState` carried eight dead per-action flags that defaulted to silently skipping all confirmation dialogs with no UI to change them, and the Tela wiki "Skip approval" checkbox had inverted semantics vs. Notion's. Both state classes now share one `WikiWriteAction` model (CREATE / UPDATE / DELETE) with "ask by default" as the safe default on first run (`fix(tools)`)
- `wiki_write_page` previously always confirmed with a generic "create or update" dialog; the create path had no approval at all. Create and update now ask separately, and the update dialog shows a preview of the new body before it overwrites the page (`fix(tools)`)

## [0.7.2] - 2026-10-16

### Fixed
- Tela wiki tokens are now stored PER PROJECT in PasswordSafe instead of one global keyring entry — previously every project's "Configure Tela Wiki…" dialog showed the same token (dots) and all projects authenticated with it. A one-time migration moves the legacy global token into the first project that reads it and removes the shared entry; other projects start clean and show an empty field until configured (`fix(tools)`)

### Added
- "Enable Tela wiki tools for this project" toggle in the Configure Tela Wiki dialog: when unticked the `wiki_search` / `wiki_read_page` / `wiki_write_page` tools are not offered to the model at all for that project (and stale in-flight calls are refused with a clear message) (`feat(tools)`)
- **Notion integration** in the same dialog (new "Notion" tab): per-project internal-integration token (PasswordSafe, per-project key), enable toggle, connection test against `/v1/users/me` and a one-time "allow the agent to create Notion pages" opt-in. Three new agent tools: `notion_search` (POST /v1/search), `notion_read_page` (page + block tree flattened to markdown) and `notion_write_page` (page under an existing parent, Forge mode + per-write approval). Handles Notion's 3 req/s rate limit (429 retry with Retry-After) and 100-block pagination (`feat(tools)`)

## [0.7.1] - 2026-10-14

### Fixed
- Switching provider profiles in the settings no longer leaves the previous provider's fetched model suggestions in the dropdown; the list is cleared on every profile change, add and delete, and stale async model-list replies are dropped when the selection changes mid-fetch (`fix(settings)`)
- A stale "Test connection" reply can no longer prefill the context-window field (or pop a connection dialog) for a different profile than the one tested (`fix(settings)`)
- Merely viewing or switching a provider profile no longer marks the settings as modified — Apply/OK is enabled only after real user edits, eliminating spurious unsaved-changes prompts (`fix(settings)`)
- Picking the "list truncated at 200" entry in the model-suggestions dropdown no longer writes that notice text into the Model field (`fix(settings)`)
- Editing flushes are tracked by profile id instead of list index, so pending edits can never be written into a different profile when the list shifts during add/delete (`fix(settings)`)
- Reset in the settings now re-seeds its session state: a key cleared before Reset can no longer wipe the stored PasswordSafe entry on the next Apply, and leftover model suggestions or orphan-candidate state are cleared (`fix(settings)`)
- Adding a provider now seeds the new profile with the currently selected API protocol and its default base URL instead of always defaulting to OpenAI-compatible (`fix(settings)`)
- Invalid command-timeout values are clamped to the documented 5–600 s range and invalid context-window values warn on Apply instead of being silently discarded (`fix(settings)`)
- Merged the duplicate listeners on the web-search-provider dropdown into one suppress-aware listener (`fix(settings)`)

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

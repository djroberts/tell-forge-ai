# AI agent instructions

## Commit messages

When creating a commit, use a concise, meaningful Conventional Commit message that describes the changes being committed. Prefer the format:

```text
<type>(<scope>): <imperative summary>
```

Use a scope when it helps identify the affected area (for example, `tools`, `settings`, or `chat`). Choose the type that accurately reflects the change, such as:

- `feat`: adds user-facing functionality
- `fix`: fixes a bug (the Conventional Commits type; do not use `bug`)
- `refactor`: restructures code without changing behavior
- `test`: adds or updates tests
- `docs`: changes documentation
- `chore`: maintenance or tooling changes

The release workflow parses Conventional Commit subjects: `fix` triggers a patch release, `feat` a minor release, and `!` or a `BREAKING CHANGE:` footer a major release. Use `feat` or `fix` only when that release impact is intended; docs, tests, refactors, and chores do not trigger a release on their own.

## Project context and conventions

- TellForgeAI is a Kotlin/JVM IntelliJ Platform plugin targeting WebStorm 2025.1 / platform build 251+, built with Gradle and JDK 21. Keep changes compatible with the IntelliJ Platform APIs and existing Kotlin style; avoid adding dependencies unless they are needed.
- Main code is under `src/main/kotlin/dev/tellforgeai/`, organized into `llm`, `model`, `settings`, `tools`, and `ui`. Tests live under the matching `src/test/kotlin/dev/tellforgeai/` packages. Add or update focused tests for behavior changes, and run `./gradlew test`; use `./gradlew buildPlugin` when plugin packaging or platform integration needs validation.
- This is an IDE plugin: respect IntelliJ threading and lifecycle requirements. Keep blocking network, process, and filesystem work off the Swing event-dispatch thread; marshal UI updates to the EDT and use the existing project/application services and UI patterns where appropriate.
- Persisted state uses IntelliJ `PersistentStateComponent` and XML serialization. Preserve backward compatibility when adding settings or model fields by providing sensible defaults. Mutable persisted Kotlin properties (`var`) are important for XML deserialization; do not casually change them to `val` or rename serialized fields without considering existing user settings and migration.
- Provider API keys and search credentials are secrets: use the IDE `PasswordSafe` through `ApiKeyStore`, never store secrets in settings XML, project state, logs, or committed files. Preserve the per-project versus application-wide state boundaries.
- Keep the Foretell/Forge boundary intact. Foretell is read-only; Forge is the mode for file modifications. Command execution has explicit safety restrictions and approval behavior—do not bypass these by introducing shell execution, unsafe command variants, or silent edits. Prefer dedicated project tools and the IDE editor APIs for file changes.
- Project browsing/search must respect `.gitignore` and supported AI ignore files. Avoid exposing ignored/generated directories or likely secrets; use the existing `IgnoreRules` behavior rather than adding ad hoc traversal rules.
- The LLM layer supports more than one wire protocol. Keep protocol-specific request/response and streaming behavior in the appropriate `LlmConnector`; avoid hardcoding one provider's payload assumptions into shared UI or client code. Preserve compatibility with existing provider profiles and OpenAI-compatible endpoints.

## Working safely

- Inspect the relevant implementation and tests before editing; verify assumptions about current code rather than relying on README descriptions alone.
- Keep changes focused and consistent with existing patterns. Do not modify generated output, caches, IDE sandbox files, or user-local configuration as part of ordinary feature work.
- For persisted-state, provider, or security changes, consider existing-user upgrades and failure paths (missing profiles, keys, or optional settings), not only the new happy path.
- After making changes, review the diff for unintended edits and run the narrowest relevant tests; run the full test task when practical. Report tests that were not run rather than implying they passed.
- Do not commit unrelated user changes. Before a commit, inspect both staged and unstaged changes, stage only the intended files, and ensure the Conventional Commit message accurately reflects the committed diff.

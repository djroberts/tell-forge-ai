# Making this agent faster / cheaper (findings from the TellForgeAI chat-bug investigation)

Same LLM, two runs of the same task ("why isn't the plugin remembering chats?"):
one finished in ~6 tool calls with hard proof, the other took ~15 calls with heavy
truncation and never confirmed its hypothesis. Root causes and fixes:

## 1. Exclude generated directories from code search (biggest win)

A broad regex search (`chat|messages|history`) matched
`.intellijPlatform/sandbox/**/events-scheme.json` — megabytes of generated
JetBrains telemetry JSON — which consumed the whole search budget and poisoned
every later decision.

**Action:** add a global ignore list to the search/list tools:
`.intellijPlatform/`, `build/`, `.gradle/`, `.git/`, `node_modules/`, `sandbox/`.
Also truncate search results per file (e.g. max 5 matches/file) instead of one
global cap, so a single generated file can't flood the context.

## 2. Add a `list_files`/tree tool; prefer it as the first step

The fast run did: `list_files` → read 4 small files → `find` one filename → read
it. Without a cheap listing tool, broad regex search is the only discovery
mechanism and it's the highest-variance first move.

**Action:** add `list_files(path, depth)`; in the system prompt: "For open-ended
bug reports, list the project structure and read the most likely-named files
first; use code search only after you know the naming scheme."

## 3. Cap all command output

`grep idea.log` dumped the entire JVM command line (~8k chars of `-D` flags).

**Action:** truncate every tool result to a few KB (head + tail), and prompt
rule: "pipe greps through `head`; never grep log files without head/tail."

## 4. Domain heuristics in the prompt

For IntelliJ-plugin "state isn't persisting" bugs, inspecting the actual storage
file beats reading code that says what *should* be written.

**Action:** prompt hint: "IntelliJ plugin state issues: first inspect the real
storage XML (`@Storage(...)` → `.idea/<file>.xml` for project-level,
`config/options/` for app-level), then diff persisted fields vs. class fields."

## 5. Force an interim hypothesis checkpoint

After ~6–8 tool calls, require the model to state its current hypothesis and the
single cheapest experiment to confirm it (here: `grep -c messages .idea/tellforgeai.chats.xml`).
Also show a "tool calls so far: N" counter — a concrete nudge beats prose rules,
and prevents re-inspecting paths already ruled out.

## Cost note

Speed and cost are the same lever: nearly all the overrun was tokens from noisy
tool output and re-reads. Fixes 1–3 alone would have cut the slow run roughly in
half with identical output quality; the model was never the bottleneck — the
tool trajectory was.

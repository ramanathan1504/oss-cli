# DEVELOPING.md — OSS-CLI internals

**Audience:** anyone changing the code. For running it, see [`SETUP.md`](SETUP.md).

---

## Shape of the thing

Java 17, Maven, picocli, SQLite (`org.xerial:sqlite-jdbc`), Jackson.

```
com.osscli
├── AppPaths            on-disk locations, incl. the legacy path
├── Main / RootCommand  picocli entry point
├── cli/                one class per subcommand
├── github/             GitHubClient — REST + search
├── llm/                OllamaClient, ClaudeClient, GeminiClient, OpenAiClient — generation only
├── retrieval/          ContextRetriever — assembles prompt context
│                       Embeddings / LocalEmbedder — the in-process embedder
├── storage/            DatabaseManager (schema), SqliteStorage (queries)
├── model/              records
└── report/             Markdown report writers
```

Four LLM clients behind one shape is deliberate: **no provider is load-bearing.**
Anything that makes one of them required is a regression.

Embedding is not one of the four. It runs in this process — `Embeddings` over
`LocalEmbedder`, ONNX Runtime, all-MiniLM-L6-v2 — so nothing that indexes or
searches needs a daemon installed and running. `llm/` is only ever asked to
generate text.

---

## Storage and migrations

`DatabaseManager` holds a numbered `Migration[]`. Each has a target version and
an `execute(Connection)`. On boot, every migration above the stored version runs
in order, then the version is bumped.

**Adding a migration:**

1. Append to `MIGRATIONS` with the next version number.
2. Bump `CURRENT_SCHEMA_VERSION`.
3. Update the fresh-install `getCreate…TableSql()` too, so new databases skip it.
4. Guard everything — `columnExists`, `addColumnIfMissing`, `CREATE TABLE IF NOT
   EXISTS`. Migrations must be re-runnable without error.

Never drop or rewrite a column that holds user data. The engine is
non-destructive by contract; users trust it to run unattended at startup.

### Embedding provenance (v9)

`personal_chat_memory`, `personal_pr_memory` and `embeddings` each carry
`embedding_model` and `embedding_dim` alongside the vector.

This exists because **model mismatch fails silently**. Common embedding models
emit 384, 768 or 1024 dimensions. Mix them in one table and cosine similarity
either compares unrelated coordinate spaces or short-circuits to `0.0` on a
length check — which reads as "unrelated" rather than "incomparable". The user
sees bad retrieval, never an error.

There is one producer now — `Embeddings.MODEL`, recorded as
`all-MiniLM-L6-v2-onnx`. Provenance did not stop mattering when the choice went
away: reads filter on the model name (`loadEmbeddings`,
`loadEmbeddedIssueNumbers`), so vectors an Ollama daemon wrote under
`all-minilm` are ignored and re-embedded on the next sync rather than compared.
Those are 384 dimensions as well, so a length check would never have caught
them — the name is the only thing that does.

Rules when touching vectors:

- **Every write records the model.** Save methods take the model name. The
  no-model overloads exist only for legacy call sites and should not gain users.
- **`NULL` means unknown, not "fine".** Rows predating v9 are treated as stale
  and re-embedded rather than guessed at.
- **Sync busts its cache on model change**, not just on content change:
  ```java
  boolean modelChanged = cachedContent != null && !embedModel.equals(cachedModel);
  if (cachedContent == null || !content.equals(cachedContent) || modelChanged) { … }
  ```

`SqliteStorage.countVectorsByModel(table)` reports the current mix.

### The legacy path

The project was renamed from `issue-ai`. `AppPaths` still declares
`LEGACY_BASE_DIR`, and `DatabaseManager.relocateLegacyDatabase()` moves the old
database across **only when the canonical location has none** — so it can never
overwrite live data. WAL and shared-memory sidecars move with it; leaving them
behind would strand uncheckpointed transactions. Cross-volume moves degrade to a
copy and say so.

Do not delete this before it is safe to assume nobody is upgrading from an old
build. A silent empty database is indistinguishable from data loss.

---

## Retrieval

`ContextRetriever.retrieve(issueNumber, repository)` assembles chunks in
priority order — the issue itself, an extracted stack trace, label-related
issues, similar past PRs, similar past conversations, JIRA links, cross-repo
links — each scored, token-counted, and flagged `included` against
`TOKEN_BUDGET`.

Two things to keep in mind:

- **`TOKEN_BUDGET` and `ollama.context_limit` are different numbers with
  different owners.** The first governs what gets assembled; the second decides
  whether to escalate. If the config is lower than the budget, everything in the
  gap escalates needlessly. Keep them reconciled.
- **The entry point is an issue number, not a question.** Free-text retrieval
  exists only in `SearchCommand`, and only over the issue `embeddings` table —
  `personal_chat_memory` carries vectors that no free-text path currently
  reaches. That is the clearest gap in the codebase.

### Passage-level embedding

Notes are split by `PassageSplitter` into overlapping passages (1500 chars,
200 overlap) and each is embedded separately into `personal_chat_chunk`.

This exists because embedding models read only a few hundred tokens. A note
longer than that is not rejected — it is silently truncated, so its vector
describes the opening and nothing else. Measured on a real corpus: a 305 K
note had its answer at character 243,426, and the note-level window covered
0.7% of the file. No chat model can recover text the embedder never read, so
this is not something a bigger model fixes.

Three rules hold this together:

- **Passages overlap.** A fact split across a boundary would otherwise survive
  in neither passage intact.
- **Boundaries prefer seams** (`\n\n`, then `\n`, then `. `) searched in the
  last quarter of the window, falling back to a hard cut. Minified JSON and
  base64 blobs have no seams; the fallback is what stops that hanging.
- **One passage per note reaches the context.** Only the best-scoring passage
  per note becomes a candidate — otherwise a single long note whose passages
  all score alike crowds every other source out of the budget.

`loadPersonalChatChunkVectors()` deliberately returns vectors WITHOUT content.
A chunked corpus is tens of thousands of passages; loading their text on every
retrieval would drag tens of megabytes through memory when only a handful is
ever read. Content is fetched by key, for the winners only.

Retrieval falls back to note-level vectors when the chunk table is empty, so a
database that has not re-synced still works.

**Nothing is capped.** Capping passages per note would have dropped 50–70% of
a real corpus. If you ever add a bound here, log what it discarded.

---

## Escalation

`PromptCommand`:

```
retrieve → totalTokens > contextLimit ?  → build expert prompt
         → model unavailable ?           → build expert prompt
         → ask locally, parse JSON       → confidence >= threshold ? answer
                                         → else                     escalate
```

The local model is asked for strict JSON: `{answer, confidence, escalate}`.

`OllamaClient` resolves its endpoint from `ollama.url` on every request. It used
to carry `http://localhost:11434` as a literal in three request paths, so the
configured value was read by `doctor` and by nothing else — a remote endpoint
reported reachable while every real call went to localhost. Keep it resolved in
one place: three copies of an endpoint is how that happened.

**Three traps when adding a model.** Reasoning models may return their content in
a separate field, leaving the parsed response empty — which looks like a low-
quality answer and escalates every time. `isAvailable()` does a literal substring
match on the model name, so a name that does not appear verbatim in the tag list
is reported unavailable. And generation time scales with context, model and
hardware over a range of *minutes*: the request timeout is
`ollama.timeout_seconds` (default 300) and a fixed low value silently fails every
full-context request.

**Report the real failure.** A timeout was once reported as `parse_error`, which
pointed diagnosis at the model's output when the cause was the clock. Escalation
reasons are user-facing diagnostics — keep `timeout`, `parse_error`,
`ollama_unavailable`, `low_confidence` and `context_overflow` distinct.

---

## Style

- Formatting is enforced by spotless; run it before committing.
- Log at the level the user cares about. `LOGGER.info` is user-facing output
  here, not debug noise.
- Dry-run or report-only defaults where a command mutates anything.
- Comments explain **why**, especially where the code guards against a silent
  failure. Those are the ones that get "simplified" away by someone who does not
  know what they are protecting against.

---

## Releasing

One command:

```bash
./release.sh 1.3.2
```

Everything that touches this repository happens locally in that script; everything
that touches the outside world happens in CI, triggered by the tag it pushes.

| `release.sh` (local) | `.github/workflows/release.yml` (on tag `v*`) |
|---|---|
| Refuses a dirty tree, a non-`main` branch, a duplicate tag, or a `main` behind origin | Rebuilds the jar from the tagged tree |
| Sets the pom version | Fails if the jar name and the tag disagree |
| Rewrites `oss-cli-<version>.jar` in the docs | Copies this version's `CHANGELOG.md` section into the release notes |
| Prepends a `CHANGELOG.md` entry from every non-merge commit since the last tag | Publishes the GitHub release with the jar |
| Formats, builds, commits, tags, pushes | Bumps the Homebrew formula's `url` and `sha256` |

The split is deliberate. The tag ends up pointing at the exact tree that was built
and reviewed, and CI rebuilds from that tag rather than trusting a jar uploaded
from someone's laptop.

**Before releasing**, skim the generated `CHANGELOG.md` entry — it is commit
subjects, so a subject that only made sense inside its diff should be reworded.
Commit that edit, then run the script.

### One-time setup

- `TAP_TOKEN` — a repo-scoped token with write access to `homebrew-oss-cli`,
  added under *Settings → Secrets and variables → Actions*. Without it the tap
  step is skipped rather than failing, so a fork can still cut a release.
- `main` is protected. Pushing the release commit relies on admin bypass
  (`enforce_admins: false`), which is why releases are cut by a maintainer
  rather than by CI.

### If a release goes wrong

The tag is the trigger, so deleting it locally and remotely and re-tagging
re-runs the whole pipeline:

```bash
git tag -d v1.3.2 && git push --delete origin v1.3.2
```

Delete the GitHub release too if one was created — `gh release delete v1.3.2`.

---

## Things worth fixing

1. **Free-text search over the notes** — `search` covers only the issue
   `embeddings` table. `personal_chat_chunk` now holds passage vectors that no
   free-text path reaches, which is the clearest remaining gap.
2. **Redaction on ingest** — there is none. Anything fed in is stored verbatim.
   Either scrub on the way in, or keep documenting loudly that ingestion must be
   pointed at already-clean text.
3. **`sync --me` ordering** — the personal-profile section can `return 1` before
   directory ingestion runs, despite that section being labelled "always runs".
4. **JSON-array re-ingestion** — rows are keyed `path#index` while the cache
   check reads `path`, so array exports are re-ingested and re-embedded every run.
5. **Vectors are stored as JSON text.** Readable and portable, but roughly 4.6 KB
   per 384-dimension vector. At corpus scale that dominates database size; a
   BLOB encoding would cut it several-fold.
6. **`InspectCommand` log format** — a summary line passes 5 arguments to a
   pattern with 1 placeholder, so the chunk table prints mangled.

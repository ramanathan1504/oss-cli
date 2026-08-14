# Working on oss-cli

Notes for anyone — human or model — changing this repository. Everything here was
learned by getting it wrong at least once.

## What this is

An offline-first workbench for open-source maintainers. It reads any repository
through the GitHub API, keeps what it finds in local SQLite, answers from that
corpus, and hands you an expert prompt when it cannot answer well itself.

**The claim it lives or dies on**, stated in the Homebrew formula:

> Nothing but a GitHub token is required — no Java, no model, no account.

Three rules follow, and they settle most design arguments:

1. **Every capability is a layer; none is mandatory.** Term search is the
   *floor*, not a degraded fallback — for a new install it is the whole product.
   Anything that would make a feature required must instead degrade and say so.
2. **Never act unasked.** No silent downloads, no writes to anybody's
   repository, no background fetches. `oss model --fetch` exists because 22 MB
   arriving unrequested is how people stop trusting a tool.
3. **Prefer removing a prerequisite over configuring one.** Ollama is a
   connector somebody may attach for local generation. Nothing that indexes or
   searches may depend on it.

## Generic, never personal

This serves every OSS developer, not one person's setup. In examples and
defaults:

- Use `owner/name`. **Never a real third-party repository** — a worked example
  naming somebody's project reads as "this tool is for that project", which is
  the opposite of true.
- No personal paths. Everything comes from config: `drive.paths`, `AppPaths`.
- Never seed identity. A default ships to every machine, so a seeded username
  would harvest a stranger's history onto somebody else's laptop.

## Traps that have already cost something

**`OSS_CLI_HOME` is an environment variable. `oss.cli.home` is not an input.**
`AppPaths.resolveBaseDir()` reads only the environment variable. The system
property is written *by* the application so `log4j2.xml` can read the path back;
setting it from outside redirects nothing at all. A test once "redirected" itself
with the property and deleted a real 496 MB database. If code must not touch the
real store, assert where it is pointing and refuse — do not trust configuration.

**The deployed website is `site/` in this repository.** `ubuos.com` links to a
Cloudflare Pages project published from here by the `deploy-site` job. A copy of
the same page exists in the ubuos-site repository and is served by nothing. Edit
the one here.

**A backup written inside a `drive.paths` folder feeds itself.** `sync --me`
reads everything under those folders, so it would ingest the archives as notes
and back up what it ingested. `BackupCommand` refuses; keep it that way.

**Several `oss` processes share one database, and the pragmas are what make
that work.** `getConnection()` sets WAL, `busy_timeout` and `synchronous=NORMAL`
on every connection. Without them SQLite is in rollback-journal mode, where one
writer locks the whole file and readers are refused outright — a `sync` in one
window made `chat` in the next fail mid-sentence. The retry loop around
`getConnection` never helped, because connecting is not the step that fails; the
lock is taken when a statement runs, which is what `busy_timeout` covers.

**Pick the sequence number inside the insert, never read-then-write.**
`ChatSessionStore.append` chooses `seq` in a subquery of the INSERT. As two
statements it is a race between any two writers, and — worse — reading in one
transaction and writing in the next asks SQLite to upgrade a read lock, which it
refuses immediately rather than waiting, so `busy_timeout` would not have saved
it either.

**A chat turn is durable the moment it is said.** Chat used to hold the whole
conversation in a `StringBuilder` and write it out only on a clean `exit`;
ctrl-c discarded everything. Anything that accumulates a user's words in memory
belongs in `chat_turn` instead. A resumed session rewrites *the note it already
has* (`chat_session.note_path`) rather than filing a second overlapping copy for
retrieval to fight over.

**An older build must refuse a store a newer one migrated.** The migration loop
only runs forwards, so when the stamped version exceeds `CURRENT_SCHEMA_VERSION`
it used to match nothing and fall through in silence -- the command then read,
and wrote, a schema it did not understand. `initializeSchema` now throws
`SchemaTooNewException`; `Main` prints what happened and exits 1, letting only
`doctor`, `--version` and `--help` past, because taking away the command that
explains the refusal is not a fix.

**A capability may degrade; it may not be gated on one provider.** This has been
got wrong in both directions and cost a release each time. `chat` refused without
a Gemini key, so a local-only user could not chat. Fixed — and it then refused
without Ollama, so a cloud-key user could not either, and the escalation path ran
its alignment step through Ollama as well. `guide` was the same: it returned
before it had read `--gemini`, the flag documented as bypassing the local model.

When adding anything that needs a model that writes, resolve **both** backends
first, refuse only when both are missing, and name both fixes. Where a step
genuinely needs a local model — aligning an answer against the user's own history,
which must not be posted to the API that wrote it — skip it out loud. An answer
that has not been checked looks exactly like one that has.

`analyze` is deliberately local-only: it loops over the whole backlog, and doing
that against a metered API would spend money nobody agreed to. It says so.

**Retry what can recover; fail once on what cannot.** All three cloud clients
grew the same bug independently: each singled out 429 as retryable, threw
`IOException` for every other non-200, then caught `IOException` in a loop that
slept and tried again. A rejected key was therefore sent three times, printing
the same raw JSON three times — burying the one line that said what to fix under
two copies of itself. `ApiFailure` decides this in one place now, and
`ApiFailure.Permanent` is caught **before** the generic `IOException` in each
client. Three copies is how the bug happened; do not make a fourth.

**Vectors from different models are never comparable.** Every vector is stored
with the model that produced it and every read filters on it. All models used
here emit 384 dimensions, so nothing catches a mix by shape — it produces
plausible nonsense instead of an error.

## Shape of the code

```
cli/         picocli commands, one per verb
retrieval/   Corpus, ContextRetriever, TextIndex, the in-process embedder
model/       records, plus References and Tier (pure, tested)
storage/     SQLite and the migration chain
llm/         Ollama and cloud clients — generation only, never embedding
ui/          Live: the status line for anything slower than a second
             Picker: the keyboard list, with a numbered fallback that always works
ext/         extensions, attached by path
runner/      the matrix engine; a pack supplies what to run
```

Embedding runs **in this process** via ONNX. It is not one of the providers in
`llm/`.

## Database

`CURRENT_SCHEMA_VERSION` in `DatabaseManager` (14). A fresh database runs the real
migrations rather than being stamped at the current version — it used to be
stamped, drifted, and shipped new installs missing three tables. `SchemaTest`
names every expected table in a list so adding one without updating it fails.

## Style

Comments explain **why**, especially the failure that motivated the code. The
repository is full of them and they are the reason it is possible to change
safely. Do not write comments that restate the line below.

Failures must be loud. A warning that scrolls past inside a command which then
prints success is worse than no warning. Refuse, or report honestly.

Prefer one implementation over two. Two embedders, two reference parsers and two
copies of a web page have each caused a real bug here.

## Before you finish

- `mvn verify` — compiles, formats, runs the tests
- Added or removed a command or flag? `release-surface.json` has to follow:
  `mvn test -Dtest=ReleaseSurfaceTest -Dsurface.update=true`, then commit it.
  `ReleaseSurfaceTest` fails the pull request otherwise, which is the point --
  the record is updated by the person making the change, not months later
- Examples use `owner/name`
- No new required prerequisite
- Anything slower than a second reports what it is doing

## Releasing

`./release.sh <version>` from a clean `main`. It bumps the version, writes the
changelog from commit subjects, opens a PR, waits for CI, merges, and tags. CI
publishes the release and the archives; the Homebrew tap follows.

**The version number is checked before anything is written.** `release.sh` runs
`ReleaseGuardTest` against `release-surface.json` as it stood at the previous
tag and refuses a bump smaller than the change requires:

| Change | Required |
| --- | --- |
| command or flag removed (aliases count) | major |
| schema version raised, or command or flag added | minor |
| neither | patch |

This is bnd's rule with the schema added, and the schema is the half that
matters: nothing imports `com.osscli.*`, so what actually breaks for a user is
an older binary meeting a newer store. **Do not add bnd or OSGi headers** —
`maven-shade-plugin` rewrites packages and would invalidate them, and no
container resolves this jar.

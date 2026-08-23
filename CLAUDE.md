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
  the opposite of true. This covers **commit messages, pull request titles and
  pull request bodies** as well as the code and the sites: those are published
  the moment they are pushed, and a squashed commit on `main` cannot be edited
  afterwards the way a description can. Say "a named upstream project" and move
  on -- which repository it was is never the point being made.
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

**There are TWO sites, and only one of them lives here.** `site/index.html` is the
landing page, folded into ubuos.com by the ubuos-site repository's Deploy
workflow. The *documentation* site — ubuos.com/docs — is a separate Astro
project in `ramanathan1504/ubuos-site` under `docs/`, and **nothing here updates
it**. Add or hide a command and that site keeps teaching the old surface until
somebody changes it by hand: on 2026-08-23 it was still teaching `oss chat` as
the way to hold a conversation, three releases after `oss ask` replaced it.

`DocumentedCommandsTest` already derives every figure on the landing page from
`release-surface.json`: the board's two halves must partition the whole command
set, and each stated total must be the length of its own list. Add a command and
that test fails until the board has it — which is why the board is right and the
prose around it is what drifts.

So `tools/check-site.sh` covers what a unit test cannot reach. Locally, and
fatally: a command that `--help` shows, that the board lists, and that no
paragraph on the page ever explains — the state `ask` was in for three releases.
Remotely, and only as a report: whether ubuos.com/docs names each shown command,
with the two files to change. A network hiccup must not stop a release, and
another repository is not this one's to fix mid-release.

Do not add a second counter in shell. Two implementations of one thing is how
this repository got two embedders, two reference parsers and two copies of a
web page.

**The deployed landing page is `site/` in this repository.** `ubuos.com` links to a
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

**A prompt built from the corpus must be budgeted, not concatenated.** `chat`
and `guide` each grew their own context builder that appended the *entire text*
of every note scoring above 0.35, uncapped. On a real store — 592 notes, 34 MB,
332 matching — that is a ~19 MB prompt for a 6,000-token model, about eight
hundred times what it can take. It timed out, which reads as a slow machine
rather than a prompt that was never going to work. `ContextRetriever` had solved
this for `prompt` all along; both now go through `MemoryContext`, which ranks,
fills the budget and **says what it dropped** — "25 of 67 included" is a
different answer from "67 included" and the user must not have to guess which
they got.

**A build out of `target/` must not migrate the real store.** `SchemaTooNewException`
refuses to *read* a store written by a newer build, loudly. The other direction was
silent: the checkout's jar opened `~/.oss-cli`, ran every pending migration, stamped
it, and printed a progress line — after which the *installed* `oss` refused that
store until a release carrying the new schema existed. That cost an afternoon on
2026-08-22, from one command missing its `OSS_CLI_HOME`. `DatabaseManager` now
refuses when all three are true: the jar is under `target/`, the store is the
default one, and the store already holds data. Both ways out are printed —
`OSS_CLI_HOME=/tmp/…` or `OSS_ALLOW_SCHEMA_UPGRADE=1`. **Always set `OSS_CLI_HOME`
when running a development build.**

An unversioned store is *two* situations that read the same number: a database
created a second ago, and one from before stamping existed that holds everything
somebody has. `tableExists(conn, "issues")` is what separates them, and both
refusals run *before* the bootstrap, because that bootstrap writes.

**The engine must not know what it walks.** That was the architecture from the
start and it was not true of `runner/engine.sh` until 4.0: it defaulted to one
project's pack, swept that project's application and configuration names when
told nothing, built that project's Maven module before any Gradle app, exempted
versions beginning `3.` from a module rule that belongs to that project, and
took `--log4j` as a spelling of `--version`. None of it failed a test, because
the only pack anybody ran had all those names in it.

Two rules follow. **Every optional pack hook is defined by the engine before the
pack is sourced** — bash cannot declare a hook optional, so "optional" has to
mean "there is already one"; without that a minimal pack printed five
`command not found` lines per cell and still exited 0. And **an empty axis is an
error**: `0 pass, 0 fail, 0 skip` with exit 0 is the shape of a clean sweep, and
it is what every pack but one used to get.

**Never read `$?` through a pipe.** `oss run list | head; echo $?` reports
*head's* status. This has produced a wrong answer twice — once concluding a
working command was broken, once concluding a broken one was fine — and both
times the mistake was in the measurement, not the code. Run the command bare,
redirect both streams, then read the status. The same applies to grepping for
escape sequences: `grep -c` counts *lines*, so a colour test that pipes through
`head -1` first can report zero on output that is full of them. `od -c` settles it.

CI asserts the exit codes now, on all four platforms, so this is checked rather
than remembered.

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

`CURRENT_SCHEMA_VERSION` in `DatabaseManager` (15). A fresh database runs the real
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
- **Same change, same commit: `site/index.html`.** `DocumentedCommandsTest` fails
  until the command board has your command on the right side of the partition.
  Then `tools/check-site.sh --local` — fatal in CI and in `release.sh` — asks the
  question the test cannot: does any paragraph actually *explain* it. Finally
  run `tools/check-site.sh` in full; it names what ubuos.com/docs is missing, and
  that is a pull request in the other repository
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

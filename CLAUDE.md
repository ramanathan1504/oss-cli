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
ext/         extensions, attached by path
runner/      the matrix engine; a pack supplies what to run
```

Embedding runs **in this process** via ONNX. It is not one of the providers in
`llm/`.

## Database

`CURRENT_SCHEMA_VERSION` in `DatabaseManager`. A fresh database runs the real
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
- Examples use `owner/name`
- No new required prerequisite
- Anything slower than a second reports what it is doing

## Releasing

`./release.sh <version>` from a clean `main`. It bumps the version, writes the
changelog from commit subjects, opens a PR, waits for CI, merges, and tags. CI
publishes the release and the archives; the Homebrew tap follows.

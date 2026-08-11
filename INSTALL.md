# Installing OSS-CLI, and what ends up where

OSS-CLI is the core. It reads any repository through the GitHub API — no clone,
any language, any forge. Two questions it cannot answer alone are *does this
actually run?* and *have I worked this out before?*, so those are **extensions**
you attach: a **bench** that executes something real, a **kb** that remembers.

You install the core once. Everything else is optional and attaches later.

---

## 1. Requirements

| Need | Why | Check |
|---|---|---|
| **Java 17+** | the CLI is a Java jar | `java -version` |
| **Homebrew** | how the core installs | `brew --version` |
| GitHub token | reading repositories | `gh auth status`, or macOS Keychain |
| Ollama | *optional* — local verdicts, embeddings | `ollama list` |
| Cloud API key | *optional* — escalation past the local budget | — |
| Maven 3.9+ | *only* to build from source | `mvn -v` |

Nothing below the first two rows is required. OSS-CLI works with none of it.

---

## 2. Install the core

```bash
brew install ramanathan1504/oss-cli/oss-cli
oss-cli --version
```

Upgrades later:

```bash
brew update && brew upgrade oss-cli
```

Then configure — every prompt may be skipped with Enter:

```bash
oss-cli setup
```

`setup` covers the GitHub username and default repo, Ollama and cloud model
names, Drive and backup paths, credential checks, and offers to attach a bench
and a kb. It configures **nothing** about upstream writes, deliberately — see §6.

Check everything at once:

```bash
oss-cli doctor
```

> `doctor` **exits non-zero when any optional prerequisite is missing** — a
> stopped Ollama, or a token held in the Keychain rather than `GITHUB_TOKEN`,
> both report and both set exit 1. That is a report, not a broken install. Read
> the lines, not the exit code; do not wire it into a script that treats
> non-zero as fatal.

---

## 3. Run it as a local service

```bash
oss-cli serve                 # http://localhost:1504
oss-cli serve --port 9000     # if 1504 is taken
oss-cli serve --no-open       # do not launch a browser
```

The page lists what is attached and lets you attach more by pasting a path. It
binds to **loopback only**, and that is not a setting: this process can start
other programs on your machine.

The page attaches and reports; it does not *run* verbs. An outward write must be
confirmed at a terminal and a browser has none, so running stays on the CLI.

---

## 4. Attach extensions

An extension is any directory containing an `oss-ext.json`. Attaching records a
**path** — nothing is uploaded or copied, and the extension stays an ordinary
repository.

```bash
git clone https://github.com/ramanathan1504/log4j2-workout ~/apache/log4j2-workout
oss-cli ext add ~/apache/log4j2-workout

git clone https://github.com/ramanathan1504/knowledge-creator ~/knowledge-creator
oss-cli ext add ~/knowledge-creator

oss-cli ext list
```

```
NAME           KIND   STATE     VERBS
log4j          bench  ok        list, run, matrix, coverage, repro, review, issue, pr, followup, hub
devon          kb     ok        file, index, harvest, map, digest, doctor
```

Then use them through the core:

```bash
oss-cli bench list --apps
oss-cli bench followup --since 4234
oss-cli kb doctor
```

| Command | Does |
|---|---|
| `ext add <path>` | attach, or update an existing entry of the same name |
| `ext list` | what is attached, and whether it is still `ok` |
| `ext refresh <name>` | re-read that manifest after editing it |
| `ext remove <name>` | forget the path. **Deletes nothing on disk** |

### Writing your own bench

Anyone can. Put this at the root of your repo, make the executable executable,
and paste the path:

```json
{
  "name": "kafka",
  "kind": "bench",
  "description": "My own Kafka setup — brokers on localhost:9092",
  "exec": "./kbench",
  "verbs": { "list": "list", "run": "run" },
  "axes": ["topic"]
}
```

`verbs` maps a **portable name** to whatever your tool actually calls it, so you
never have to rename your own commands to join in. `exec` may be bash, Python,
Go — anything; it is run as a child process, in your repo's own directory.

If a verb of yours posts somewhere public, declare it:

```json
"writes": ["publish"],
"writesTo": "acme/orders"
```

`writesTo` must be a bare `owner/name`. It is compared for equality against the
approval flag, so a sentence there can never match and would silently make the
verb unusable.

> **Edited a manifest?** Run `oss-cli ext refresh <name>`. The registry stores a
> snapshot plus its SHA-256, so an edited file shows as `STALE` and **dispatch is
> refused** until refreshed — rather than acting on a stale copy.

---

## 5. Folder structure

### The core, once installed

```
/opt/homebrew/bin/oss-cli                  → launcher on your PATH
/opt/homebrew/Cellar/oss-cli/<version>/
  └── libexec/oss-cli.jar                  the whole program

~/.oss-cli/                                YOUR DATA — never inside a clone
  ├── extensions.json                      what is attached (paths + manifest snapshots)
  ├── data/issue_intelligence.db           issues, PRs, vectors, notes
  └── logs/                                rotating logs
```

> `~/.oss-cli` survives uninstalling and re-cloning. It is the one directory
> worth backing up (`oss-cli backup`). `OSS_CLI_HOME` relocates all of it, which
> is how a development build avoids touching a release's data.

### oss-cli — source (only needed to build)

```
oss-cli/
├── src/main/java/com/osscli/
│   ├── cli/          the commands (setup, review, ext, …)
│   ├── ext/          extension model, registry, runner
│   ├── safety/       the upstream write guard
│   ├── serve/        the local service and its page
│   ├── github/       API client — GET only
│   ├── retrieval/    context assembly, vectors
│   ├── storage/      SQLite
│   └── llm/          Ollama and cloud clients
├── site/index.html   the public site (Cloudflare Pages)
├── release.sh        cut a release; CI publishes and updates the tap
└── pom.xml
```

### log4j2-workout — a **bench** extension

```
log4j2-workout/
├── bench                 the only entry point (symlink into ~/.local/bin)
├── oss-ext.json          declares this repo as the `log4j` bench
├── packs/
│   ├── log4j/pack.sh     WHAT is tested: versions, apps, app→module map
│   └── example/pack.sh   a worked example — copy it for your own project
├── apps/                 19 real applications (Spring Boot, JPA, JMS, web, bridges…)
├── configs/              73 configurations across XML/JSON/YAML/properties + 1.x
├── scripts/              hub.py, repro.sh, gh-*.sh
├── docs/
│   ├── pr-reviews/       reviews written, + ledger.tsv (head SHA at review time)
│   ├── site/             the AsciiDoc command reference
│   └── *.md              PR-REVIEW, BY-HAND, HANDOVER, FEATURE-MATRIX, …
├── repros/<kind>-<n>/    standalone reproductions: zip, matrix, per-version logs
├── logs/<config>/        what the appenders produced — where findings are confirmed
└── .bench/               caches, sweep logs, hub reports, review evidence (disposable)
```

The engine is `bench`; the content is `packs/`. `BENCH_PACK=example ./bench list`
runs the same matrix machinery against different content.

### knowledge-creator — a **kb** extension

```
knowledge-creator/
├── kb                    one entry point: file, index, harvest, map, digest, doctor
├── oss-ext.json          declares this repo as the `devon` kb
├── oss-harvest.py        the daily harvester (launchd, 09:15)
├── pr-review-file.py     files a written review into the archive
├── devon-index.sh        nudge your indexer to re-read changed files
└── logs/                 harvester output — check here when a sync looks wrong

~/Library/Mobile Documents/…/Devon Capture/   THE ARCHIVE (iCloud, not in the repo)
  ├── Projects/<topic>/                       filed topic-first, provenance second
  ├── Tooling/  Reference/  Blog/
~/Documents/Knowledge.dtBase2                 the DEVONthink database
```

---

## 6. Writing to a public repository

**Refused by default, everywhere, and there is no setting that changes it.** No
stored credential, no environment variable, nothing remembered between runs.

Reading a public repo is free to get wrong. Posting to one is not: a comment
reaches every watcher and the mailing list the instant it is sent, and deleting
it afterwards reaches neither.

Two things must both be true, and neither can be made permanent:

1. **You name the repository** — `--approve-upstream owner/name`. The name is
   compared, not merely counted: an approval for one repository is not an
   approval for another.
2. **You confirm that write, now, by retyping the repository name.** Every time.

```bash
oss-cli bench --approve-upstream apache/logging-log4j2 hub --pr 4234
./bench hub --approve-upstream apache/logging-log4j2
```

This binds every path equally — a command, an extension, a local model, a cloud
model. A model deciding a comment should be posted has decided nothing; it still
comes through the guard, and the guard still asks you.

> The always-on hub agent passes no approval flag, so **it cannot post at all**.
> Posting requires starting one by hand, with the repository named. The thing
> that runs unattended is the thing that must not be able to write.

---

## 7. Ports

| Port | Serves | Started by |
|---|---|---|
| **1504** | `oss-cli serve` — the palette | you, by hand |
| **8787** | `bench hub` — the log4j working page | launchd at login, or by hand |

---

## 8. Building from source

Only needed to develop the core.

```bash
git clone https://github.com/ramanathan1504/oss-cli && cd oss-cli
mvn package -DskipTests
OSS_CLI_HOME=~/.oss-cli-dev java -jar target/oss-cli-<version>.jar doctor
```

Always set `OSS_CLI_HOME` for development builds: both are built from the same
pom by the same command, so nothing else tells them apart at runtime, and the
schema migrations are one-way.

---

## 9. When something is wrong

| Symptom | Cause and fix |
|---|---|
| `no bench extension is registered` | nothing attached — `oss-cli ext add <repo>` |
| `STALE` in `ext list` | the manifest changed on disk — `oss-cli ext refresh <name>` |
| `MISSING` in `ext list` | the checkout moved — re-add it at the new path |
| `refused — no --approve-upstream` | working as designed; see §6 |
| `doctor` exits 1 | expected when an *optional* prerequisite (Ollama, `GITHUB_TOKEN` in env) is absent |
| `could not listen on port 1504` | another instance is serving — `--port <n>` |
| Hub shows stale content after a code edit | the agent loads its source at start: `launchctl kickstart -k gui/$(id -u)/com.ramanathan.bench-hub` |
| Daily harvest reports failed stages | `gh` is unauthenticated under launchd (no keychain); an interactive `gh auth status` will say it is fine. `./kb doctor` |

---

## 10. Uninstalling

```bash
brew uninstall oss-cli          # removes the program only
rm -rf ~/.oss-cli               # ONLY if you also want the database and palette gone
```

Deleting an extension's clone is safe — but `bench` **is** its repository (the
apps and configs are the product, not a build artefact), and `.bench/` and
`logs/` hold results git does not track. Detaching first is tidier:
`oss-cli ext remove <name>`.
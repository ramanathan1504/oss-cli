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
| **A GitHub token** | reading repositories — **the only hard requirement** | `gh auth status`, or macOS Keychain |
| Homebrew | *optional* — the easiest way in. Every release also ships a self-contained archive you can unpack anywhere | `brew --version` |
| Java 17+ | *only if you run the plain `.jar`.* The macOS, Linux and Windows archives carry their own runtime, built with `jlink`, so nothing needs a JDK installed | `java -version` |
| Embedding model | *optional* — search by meaning. About 22 MB, runs in-process, fetched once by `oss model --fetch` | `oss model` |
| Ollama | *optional* — local verdicts and guidance. Nothing indexes or searches through it | `ollama list` |
| Cloud API key | *optional* — escalation past the local budget | — |
| Maven 3.9+ | *only* to build from source | `mvn -v` |

Only the first row is required. OSS-CLI works with none of the rest.

> The two operations that take real time — `oss model --fetch`, and the vector
> index `sync` builds for a repository — print a live status line with elapsed
> time, so a long wait cannot be mistaken for a hang. It goes to **stderr**,
> never stdout, so piping and redirecting a command's output is unaffected, and
> it animates only when attached to a terminal: under a pipe, cron or CI it is
> one plain line per step with no colour. `NO_COLOR` gives you that plain output
> anywhere, and `OSS_NO_QUIPS=1` drops only the one-line quip that appears after
> 8 seconds of waiting.

---

## 2. Install the core

```bash
brew install ramanathan1504/oss-cli/oss
oss --version
```

Upgrades later:

```bash
brew update && brew upgrade oss
```

Then configure — every prompt may be skipped with Enter:

```bash
oss setup
```

`setup` covers the GitHub username and default repo, Ollama and cloud model
names, Drive and backup paths, credential checks, and offers to attach a bench
and a kb. It configures **nothing** about upstream writes, deliberately — see §6.

Check everything at once:

```bash
oss doctor
```

> `doctor` **reports layers, not only failures.** An unfetched embedding model,
> a stopped Ollama, or a token held in the Keychain rather than `GITHUB_TOKEN`
> are all reported as warnings and none of them sets exit 1 — the tool still
> runs, just not at its best. Exit 1 is kept for something actually broken, such
> as a `drive.paths` folder that no longer exists. Read the lines, not the exit
> code.

---

## 3. Run it as a local service

```bash
oss serve                 # http://localhost:1504
oss serve --port 9000     # if 1504 is taken
oss serve --no-open       # do not launch a browser
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
# A runner: any directory declaring kind "runner" in its oss-ext.json. A git
# repository is the usual case, but nothing requires one — a plain local folder
# works, because attaching records a path.
oss ext add ~/path/to/your-runner

# A memory: an archive that files and indexes what you have worked on.
git clone https://github.com/ramanathan1504/knowledge-creator ~/knowledge-creator
oss ext add ~/knowledge-creator

oss ext list
```

```
NAME           KIND    STATE     VERBS
your-runner    runner  ok        list, run, matrix, coverage, repro, review, issue, pr, followup, hub
devon          memory  ok        file, index, harvest, map, digest, doctor
```

Then use them through the core:

```bash
oss bench list --apps
oss bench followup --since 4234
oss kb doctor
```

The built-in memory answers the same health question with nothing attached:

```bash
oss memory doctor       # archive reachable? last run? is the schedule loaded?
```

| Command | Does |
|---|---|
| `ext add <path>` | attach, or update an existing entry of the same name |
| `ext list` | what is attached, and whether it is still `ok` |
| `ext refresh <name>` | re-read that manifest after editing it |
| `ext remove <name>` | forget the path. **Deletes nothing on disk** |

### Writing your own bench

Anyone can. Put this at the root of your repo, make the executable executable,
and attach it with `oss ext add <path>`:

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

> **Edited a manifest?** Run `oss ext refresh <name>`. The registry stores a
> snapshot plus its SHA-256, so an edited file shows as `STALE` and **dispatch is
> refused** until refreshed — rather than acting on a stale copy.

---

## 5. Folder structure

### The core, once installed

```
/opt/homebrew/bin/oss                      → launcher on your PATH (a symlink)
/opt/homebrew/Cellar/oss/<version>/libexec/
  ├── oss                                  the launcher it points at
  ├── lib/oss.jar                          the whole program
  └── runtime/                             a bundled Java, so none is required

~/.oss-cli/                                YOUR DATA — never inside a clone
  ├── extensions.json                      what is attached (paths + manifest snapshots)
  ├── data/issue_intelligence.db           issues, PRs, vectors, notes
  ├── models/                              the embedding model, if you fetched it
  └── logs/                                rotating logs
```

> `~/.oss-cli` survives uninstalling and re-cloning. It is the one directory
> worth backing up (`oss backup`). `OSS_CLI_HOME` relocates all of it, which
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
│   ├── retrieval/    context assembly, the in-process embedder, vectors
│   ├── storage/      SQLite
│   └── llm/          Ollama and cloud clients — generation only
├── site/index.html   the public site (Cloudflare Pages)
├── release.sh        cut a release; CI publishes and updates the tap
└── pom.xml
```

### A **runner** extension — the shape of one

```
your-runner/
├── bench                 the only entry point (symlink into ~/.local/bin)
├── oss-ext.json          declares this repo as a runner, and names it
├── packs/
│   ├── <project>/pack.sh WHAT is tested: versions, apps, app→module map
│   └── example/pack.sh   a worked example — copy it for your own project
├── apps/                 real applications, the ones worth exercising
├── configs/              configurations across whatever formats the project reads
├── scripts/              hub.py, repro.sh, gh-*.sh
├── docs/
│   ├── pr-reviews/       reviews written, + ledger.tsv (head SHA at review time)
│   ├── site/             the command reference
│   └── *.md              PR-REVIEW, BY-HAND, HANDOVER, FEATURE-MATRIX, …
├── repros/<kind>-<n>/    standalone reproductions: zip, matrix, per-version logs
├── logs/<config>/        what the run produced — where findings are confirmed
└── .bench/               caches, sweep logs, hub reports, review evidence (disposable)
```

The engine is `bench`; the content is `packs/`. `BENCH_PACK=example ./bench list`
runs the same matrix machinery against different content — which is the whole
point of the split: the engine here knows how to run a matrix, and the pack says
what the matrix is.

**A pack is a directory, not a repository.** It is one holding a `pack.sh`, and
it is reached either by name from a runner's `packs/` directory or by path:

```bash
BENCH_PACK=example ./bench list        # by name, from packs/
oss run --pack ~/some/local/folder list --apps   # by path, from anywhere
```

Neither form clones or copies anything, which is the same rule `ext add`
follows: what is recorded is a path, and the directory stays wherever you keep
it. `runner/packs/example/pack.sh` in this repo is a working pack of about
thirty lines — copy the directory, change the five declarations, and the engine
runs against your project instead.

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

The harvester also takes `--repo owner/name`, repeatable, which collects **every**
issue and pull request discussion in that repository — comments, reviews and
inline review threads — rather than only the threads you appear in. The daily
script reads that list from `KB_HARVEST_REPOS`, unset by default. It only reads;
it never writes to any repository. What it writes locally lands in a folder named
in `drive.paths`, and `oss sync --me` embeds it — marked as reference rather than
your own work, which [`SETUP.md`](SETUP.md#3-connecting-your-knowledge) explains.

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
oss bench --approve-upstream owner/name hub --pr 4234
./bench hub --approve-upstream owner/name
```

This binds every path equally — a command, an extension, a local model, a cloud
model. A model deciding a comment should be posted has decided nothing; it still
comes through the guard, and the guard still asks you.

> The always-on hub agent passes no approval flag, so **it cannot post at all**.
> Posting requires starting one by hand, with the repository named. The thing
> that runs unattended is the thing that must not be able to write.

---

## 7. Ports

| Port | Serves | Started by | Needed? |
|---|---|---|---|
| **1504** | `oss serve` — the board, the questions you can ask, and the palette of attached extensions | you, by hand, or `oss serve --install` | **no.** Everything the page does is a command you can type, and it says which one on hover. `oss serve --uninstall` stops it starting at login and removes nothing |

One port, and only one. The board opens on `oss hub` over whatever repositories you
follow, so it is not tied to any particular project.

It is not required to use a **pack** either. A pack has nothing to attach, so it needs
no service at all: `oss run --pack <dir> <verb>`.

**Both are long-lived JVMs, and an idle one must cost nothing.** Up to 1.11.16
they did not: the async file appender used a queue that busy-waits for an entry
rather than parking, so each process pinned a core for as long as it lived. On a
laptop with both installed at login that was four cores, indefinitely, and a
machine left shut overnight was flat by morning. Fixed in 2.0.0 — an idle
`oss serve` now measures 0.0% CPU. If you installed either agent before that,
upgrade and restart them:

```bash
brew upgrade oss
launchctl kickstart -k gui/$UID/com.osscli.serve
```

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
| `no bench extension is registered` | nothing attached — `oss ext add <repo>` |
| `STALE` in `ext list` | the manifest changed on disk — `oss ext refresh <name>` |
| `MISSING` in `ext list` | the checkout moved — re-add it at the new path |
| `refused — no --approve-upstream` | working as designed; see §6 |
| `doctor` exits 1 | something is genuinely wrong — a missing `drive.paths` folder, or vectors from more than one model. A missing *optional* piece (the embedding model, Ollama, `GITHUB_TOKEN` in env) only warns |
| `could not listen on port 1504` | another instance is serving — `--port <n>` |
| Hub shows stale content after a code edit | the agent loads its source at start: `launchctl kickstart -k gui/$(id -u)/com.ramanathan.bench-hub` |
| Daily harvest reports failed stages | `gh` is unauthenticated under launchd (no keychain); an interactive `gh auth status` will say it is fine. `./kb doctor` |

---

## 10. Uninstalling

```bash
brew uninstall oss              # removes the program only
rm -rf ~/.oss-cli               # ONLY if you also want the database and palette gone
```

Deleting an extension's clone is safe — but `bench` **is** its repository (the
apps and configs are the product, not a build artefact), and `.bench/` and
`logs/` hold results git does not track. Detaching first is tidier:
`oss ext remove <name>`.
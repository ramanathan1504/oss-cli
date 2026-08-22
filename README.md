# OSS-CLI — Developer Investigation Workbench

An advanced, offline-first **Prompt Intelligence Platform** for open-source maintainers. Instead of being another AI chatbot, OSS-CLI acts as an intelligent context assembler — it searches your entire local knowledge base (issues, PRs, vectors, chat logs, personal notes) and generates a perfect, expert-quality prompt you can hand to whichever AI you already use.

> "OSS-CLI does not call an AI. It **becomes** the intelligence layer — and hands a perfect prompt to whichever AI you choose."

---

## 🧭 Where this sits, of the three

**This repo knows → a runner runs → an archive remembers.**

| Role | Owns | Reach for it when |
|---|---|---|
| **this one** | facts about any repo from the GitHub API, cached by head SHA — no clone, any language, any forge | you want PR facts, conventions or a verdict without building anything |
| the **runner**, built in | `oss run detect / init / build / test / doctor` — reads what your project already declares and runs its own build | you want to know whether this thing builds, here, now |
| a **runner** you attach | the matrix: real applications, real JVMs, version × config × app, `oss run review <n>` | the question needs a real application of one project |
| the **memory**, built in | `oss memory` — file, index, search, harvest, digest, over a folder of markdown | you want to keep and find what you worked out |
| a **memory** you attach | a richer archive: classified, linked, searchable in a year | you already have one |

**Both halves are built in, and an extension takes over rather than being
required.** That is the rule, not a convenience: nothing that indexes, searches
or runs may be gated on something you have to attach first. An attached
extension wins for the verbs it declares, and a verb it does not declare falls
back to the core rather than being refused.

The boundary that matters: **OSS-CLI never needs a clone and is never specific
to one project.** Reading a project's own build file is the same work everywhere,
so it lives here. Walking a version × config × app matrix needs a **pack** —
only you know what a real application of your project looks like, and
`oss run init` writes the starter one from what is already in your directory.

Both write into the same archive and stay out of each other's way by location:
everything OSS-CLI generates goes to `<topic>/oss-cli/`, hand-written reviews go
to `<topic>/pr-reviews/`.

---

## 🧠 Architecture: Adaptive Local-First Intelligence

```
Tier 1 — Retrieval (Local, Instant)
  SQLite + Vector DB → Issues, PRs, Stack Traces, Chat Logs, Notes

Tier 2 — Local Answer (a local model, primary)
  A local model tries to answer directly from retrieved context
      ↓ Within token limit + confident → Answer shown immediately
      ↓ Context too large OR low confidence → Escalate to Tier 3

Tier 3 — Expert Prompt (Fallback, On-Demand)
  Prompt Builder assembles full context into a structured expert prompt
  → Copy to whichever AI you use   OR   auto-send via --send-* flag
```

The platform separates public repository data from your private developer identity:

1. **The Repository Engine (Public):** Syncs whatever repositories *you* register — any language, any forge account, from one repo to hundreds — into a unified SQLite database with cross-project dependency tracking and JIRA Bridge matching. Nothing is hardcoded to a particular project.
2. **The Personal Copilot (Private):** Ingests your own GitHub PR footprint and whatever note folders you point it at (assistant exports, hand-written Markdown) to build a **Developer Expertise Vector**.

You bring the repositories and the data. OSS-CLI does the mapping, indexing and retrieval so you no longer chase the same context by hand — upstream and downstream docs, inherited build rules, old work on the same area, and past conversations all become one searchable corpus.

---

> **Working offline?** One command needs the internet — `sync`. Everything else,
> including search by meaning, reads a file on your disk. See
> [OFFLINE.md](OFFLINE.md) for what that means exactly, and a sync-once-then-unplug
> walkthrough.

## 🧩 Bring what you have — nothing is mandatory

Every capability is a layer, and each is optional. The tool reports which layers a given answer actually used, so a thin result is never mistaken for a confident one.

| You have | You get |
|---|---|
| A GitHub token only | Sync, issue tracking, PR facts, commits, diffs, CI state, convention checks — and `oss run detect / build / test`, which reads the build file your project already has |
| ...plus the embedding model | Semantic search, vector indexing, duplicate detection |
| ...plus a local model | Local answers and PR verdicts |
| ...plus a cloud API key | Escalation when local context or confidence is not enough |
| ...plus your own notes | Your history and past reasoning blended into retrieval |
| ...plus a `pack.json` | The built-in engine runs *your* applications across *your* versions — `oss run init` writes the first draft of one |
| ...plus a `kb.json` | The knowledge base points at your own archive, with your topics and yardsticks |

A brand-new user with none of the optional pieces still gets working commands. Missing layers print one line saying what they would add and how to enable them — never a hard failure.

---

## 🧭 Twelve commands, and the twenty-three behind them

`oss --help` used to list all forty entries at once. A list that long is an
inventory, not a menu: somebody asking *"what is waiting on me"* had to already
know that `hub` was the answer and that `critical`, `prs`, `followup`, `backlog`
and `pick` were not — which is exactly what the list was supposed to tell them.

Since **3.0** the help shows the twelve that carry the daily work, plus the four
engine prefixes that go in front of them:

```
sync  search  review  triage  chat  hub  pr  ext  serve  run  memory  doctor  setup
llm   claude  gemini  codex
```

**Nothing was removed.** The other twenty-three still run, still take the same
flags, still print their own usage — a script written last year does not care what
this help looks like, and breaking one to tidy a screen would charge somebody else
for a decision they did not make.

```bash
oss --help-all      # every command, grouped: the everyday set and the rest
```

That line is printed in the footer of `oss --help`, because a hidden command
nobody can find again is a removed command with extra steps. CI checks both
halves — that the twelve are listed, *and* that the twenty-three are still there.

---

## 🪜 One interface, whichever backend you have

Naming an engine grants permission; it does not order a call. Every ask starts on
the local rung and climbs only when that rung fails a stated test — and the rung
that answers says so before it answers.

| What you have | What answers |
|---|---|
| An API key | The provider's API |
| No key, but their CLI installed and signed in | **That CLI, on your subscription** — announced, not silent |
| Neither, but Ollama running | The local daemon |
| None of it | The built-in model: ranks and retrieves, offline |

A key is never abandoned for a subscription without being asked — that would
change who pays and what the harness may read. Having *no* key is a different
situation: there is no account to move away from, and the alternative was a dead
end you had to already know a flag to escape.

---

## ✍️ It writes the way you write

```bash
oss profile --me
```

Measured from text with **your** name on it — issues and pull requests you
authored, the comments you wrote, your own turns in `oss chat`. Harvested threads
and generated notes are excluded on purpose: a voice learned from those is the
tool's own, handed back to you as yours.

Every trait is arithmetic over your text — words per sentence, how often a list or
a code fence or a heading appears, British against American spelling. Nothing asks
a model what you "sound like", because that returns flattery and flattery cannot be
checked against anything.

**Under twenty samples it tells no model anything.** The profile is still written
and marked *Provisional*, because the way to fix a thin sample is to see that it is
thin. `oss memory harvest <your-username>` keeps the comments you wrote as it goes,
from pages it was already fetching.

---

## 🧱 Packs, and the support packs under them

A **pack** is a subject; a **support pack** is something attached to it. Every
repository you follow is already a pack, which is what makes this work before you
configure anything. A manifest only says which one it sits under:

```json
{ "name": "log4j-workout", "kind": "runner", "supports": "apache/logging-log4j2" }
```

`oss ext list` renders the tree, and the assistant is told what is attached and to
which subject — stated as fact, never as instruction. Told *"use the bench"*, a
model reaches for it on an unrelated issue to be helpful; told *"this exists, it
supports that"*, it has what it needs to decline.

---

## 🛠 What you need

**A GitHub token. That is the whole list.**

The published archives carry their own Java runtime — built with `jlink`, one per
platform — so installing does not mean installing a JDK first. This README used to
open by asking for Java 17 and Maven, which is a fine answer for a Java developer
and the wrong one for everybody else, and it contradicted the promise the Homebrew
formula makes.

* **A GitHub token** — the only hard requirement
* **Java 17 and Maven** — *only if you build from source.* See [DEVELOPING.md](DEVELOPING.md)
* **The embedding model** *(optional)* — all-MiniLM-L6-v2, quantised to about 22 MB, Apache-2.0. It runs inside this process; there is no server to install. `oss model --fetch` puts it under `~/.oss-cli/models`, once. Nothing fetches it on your behalf, and until it is there everything that would search by meaning searches by shared terms instead.
* **Ollama** *(optional)* — local text *generation* only: guidance, triage verdicts and PR verdicts. Nothing indexes or searches through it. Models are your choice; defaults are `llama3.2:3b` and `qwen2.5:0.5b`.
* **A cloud API key** *(optional)* — any major provider, for escalation

---

## 🔎 Repository & PR Intelligence

`profile` reads a repository and reports what it *is* — language, build system, toolchain version, documentation, and the conventions a change must respect. Everything is pattern-matched rather than hardcoded, so an unfamiliar project is handled by the same code path as a familiar one.

For Maven projects it **follows the inherited POM chain through Maven Central**, because a project's real rules are often published in a parent artifact rather than committed to the repository you are looking at. One large Apache project, for example, declares no OSGi configuration anywhere in its own tree, yet every module is a bundle — the bnd setup and the API baseline gate live two levels up.

`review` then uses that profile to review a pull request, caching evidence **by head commit SHA** so re-reviewing unchanged code is instant while a new push re-fetches automatically. No local clone is needed.

```bash
oss profile -r owner/name
oss review 4234
```

---

## 🔗 Stated references, not only resemblance

Retrieval ranked everything by similarity, which is the right tool when nobody has said what relates to what, and the wrong one when somebody has. A pull request whose entire body is "fixes #4100" shares almost no wording with the issue it closes, so the two scored as unrelated at exactly the moment they were most related.

References are now read out of issue and pull request titles and bodies at sync time and stored as edges, and retrieval walks them **in both directions** — what this issue points at, and what points at it. The incoming direction is the one nobody records: an issue never knows which pull request closed it, because the claim lives in the pull request.

`fixes/closes/resolves #N` is a stronger edge than a bare `#N` and ranks higher, because the author said so rather than a ranking inferring it. `#123`, `owner/name#123` and commits (a full 40-character SHA, a `/commit/<sha>` URL, or the literal word "commit" and a hash) are all recognised, and fenced and inline code are stripped first, so a stack trace mentioning `#1` no longer becomes an edge to a real, unrelated issue. Cross-repository references are recorded even when that repository has not been synced; retrieval only follows the targets it actually has.

---

## 🧾 What you worked out, and what you only collected

Notes are classified as **knowledge** — your own work — or **reference**, a discussion collected for context that you had no part in. Both belong in the corpus and they are not the same thing: ranked identically, the collected material wins on volume alone, and answers start being assembled out of conversations you have never read.

The classification comes from the note's own frontmatter: a note is reference only when `my_role: none` and `source: repo-scan` both appear. Anything else is knowledge, including a thread found by scanning that you turn out to have authored or reviewed, and including notes with no frontmatter at all — those are your own writing and this tool's own recorded resolutions.

Retrieval prefers knowledge without discarding reference: reference passages still compete for the token budget, at a 0.75 discount, and are labelled `reference` in the assembled prompt so provenance is visible. Nothing is hidden. Promotion is automatic — the tier is re-read every time a note is embedded, so taking part in a thread you had only collected moves it to knowledge on the next harvest and `oss sync --me`.

---

## 🚀 Install

```bash
brew install ramanathan1504/oss-cli/oss
oss setup
```

`setup` is an interactive wizard: it registers your GitHub token, any cloud API
keys, local model names and note folder paths into the SQLite `system_config`
table. Nothing is required beyond the token — every prompt after it may be
skipped.

Not on Homebrew? Every release carries a self-contained archive for macOS, Linux
and Windows, and a plain jar for anyone who would rather bring their own Java:
[releases](https://github.com/ramanathan1504/oss-cli/releases). Full details,
including what lands where, are in [INSTALL.md](INSTALL.md).

Building from source is [DEVELOPING.md](DEVELOPING.md) — that is the path that
needs Java 17 and Maven.

---

## ⏱ Running it on a schedule, if you want that

This section used to describe `osscli-master.sh` and a `.plist` you edited by
hand. Neither is in this repository — the script had been deleted and the
instructions outlived it, which is worse than no instructions: they send a
reader looking for a file that is not there.

The tool installs its own, on the platform you are on — launchd, a systemd user
timer, or a scheduled task — and **nothing installs itself unasked**:

```bash
oss memory schedule --install            # harvest your own work daily, 09:15
oss memory schedule --install --at 07:00 # or whenever suits you
oss memory schedule                      # what is installed, and is it LOADED
oss memory schedule --uninstall

oss serve --install                      # the board on :1504 at login
oss serve --uninstall
```

A missed run is caught up rather than skipped. `oss memory doctor` answers the
question a file-based check cannot: whether the schedule is **loaded** rather
than merely installed — the state a dead job sits in while everything on disk
looks correct.

```bash
tail -f ~/.oss-cli/logs/oss-cli.log
```

---

## 🔄 The Master Workflow

### Standard Investigation Flow

```bash
# 1. Sync every repository you have registered
oss sync --all

# 2. Sync your personal 1-year Developer Profile & Google Drive chat logs
oss sync --me

# 3. Fast offline ranking — no AI required
oss critical

# 4. AI severity analysis (Ollama) + duplicate detection (the built-in embedder)
oss analyze
oss duplicates -t 0.85

# 5. Generate your Personal Contribution Roadmap Report
oss report --me
```

The two slowest operations — fetching the embedding model with `oss model --fetch`, and building a repository's vector index during `sync` — print a live status line with elapsed time, because a command that says nothing for forty seconds is indistinguishable from one that has hung. It goes to **stderr**, never stdout, so piping and redirecting results is unaffected. The spinner and colour appear only when attached to a terminal; a pipe, cron or CI gets plain one-line-per-step output and no colour. `NO_COLOR` is honoured. After 8 seconds of waiting the line adds a rotating one-line quip in its dim tail, which `OSS_NO_QUIPS=1` turns off on its own.

### Prompt Intelligence Flow (New — Adaptive)

```bash
# The engine goes in front. Plain assembles the context and hands it to you.
oss prompt 1666

# Ollama answers locally if it can — falls back to the expert prompt when the context is too large
oss llm prompt 1666

# See exactly what context was retrieved, and which rung would answer
oss inspect 1666

# Force the expert prompt regardless (skip local Ollama)
oss prompt 1666 --force-prompt

# Copy the generated expert prompt to clipboard
oss prompt 1666 --copy

# Save expert prompt to a Markdown file
oss prompt 1666 --out ~/Desktop/issue-1666-prompt.md

# Send it, if the local rung cannot answer. The engine goes in front.
oss gemini prompt 1666
```

### Conversations you can come back to

`oss chat` talks an issue through with you. Every turn is written the moment it
is said, so ctrl-c, a closed lid or a dropped connection loses nothing — and
tomorrow you carry on rather than starting again.

```bash
oss chat 4129            # start on an issue
oss chat --continue      # carry on with the most recent conversation
oss chat --resume 7      # resume a specific one
oss history              # browse them all, arrow keys and a preview, enter to resume
```

Chat runs on **Ollama, a cloud API key, or both** — it refuses only when you have
neither, and then names both ways to fix it. With only a key, every turn leaves
your machine and the banner says so on its first line.

`oss history --search "the flaky test one"` finds a conversation by what it was
about, using the built-in embedder — no server, no account. Without the model it
matches shared terms instead and says so.

Running three terminals is fine. They share one database in WAL mode, so a long
`sync` in one window never blocks a chat in another. A conversation already open
elsewhere is spotted and offered as a fork rather than letting two terminals
interleave one transcript, and a killed terminal releases its conversation by
itself — there is no lock file to clean up.

On `exit` the transcript is filed into your archive and embedded, which is when
it becomes part of what every other command can retrieve. Resuming rewrites that
same note rather than leaving three overlapping copies of one conversation.

---

## 🔒 Backup & Restore

Safeguard your entire AI memory (database, embeddings, chat logs) with a single command:

```bash
# Create a timestamped zip archive (auto-rotates, keeps last 5)
oss backup

# Restore from a previous archive (preserves your local API keys)
oss restore /path/to/sa_brain_backup_20260627_104000.zip
```

---

## 🖥 Desktop UI (Roadmap)

A Tauri-based desktop application (Rust + React) has been **sketched, not started**.
No code for it exists in this repository. The shape it would take:

| Panel | Content |
|---|---|
| Left Sidebar | Issues, history, searches, repositories |
| Main Panel | Prompt workbench with Markdown, copy button, edit, token count |
| Right Panel | Context Inspector — retrieved documents, sources, relevance scores |

---

## 💾 Database

A zero-configuration SQLite database (`~/.oss-cli/data/issue_intelligence.db`) with an
**automatic, non-destructive migration engine**: schema changes are applied at boot
without dropping anything. A fresh database runs the real migrations rather than
being stamped at the current version, and it starts **empty** — no repository is
monitored until you add one with `oss sync --add owner/name`.

An older binary meeting a store a newer one has migrated **refuses to run** rather
than reading a schema it does not understand; `doctor`, `--version` and `--help`
still work, because taking away the command that explains the refusal is not a fix.

---

## 📐 What is built, and what is not

This section used to be headed "planned and in-progress" and listed things that
shipped releases ago. A roadmap that never moves is read as a description of the
product, so it is split here:

**Built.** The retrieval pipeline and its context sources; the prompt builder and
its templates; the context organiser written by the local model; `prompt_history`
and `prompt_context_chunks` in the schema; the extension interface (`oss-ext.json`,
attached by path, written in any language); the built-in runner and the built-in
knowledge base.

**Not built, and not currently being worked on.** A Tauri desktop application, and
a streaming JSON API contract. They are ideas, not commitments — if either matters
to you, say so in an issue rather than waiting.

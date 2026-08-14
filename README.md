# OSS-CLI — Developer Investigation Workbench

An advanced, offline-first **Prompt Intelligence Platform** for open-source maintainers. Instead of being another AI chatbot, OSS-CLI acts as an intelligent context assembler — it searches your entire local knowledge base (issues, PRs, vectors, chat logs, personal notes) and generates a perfect, expert-quality prompt you can hand to whichever AI you already use.

> "OSS-CLI does not call an AI. It **becomes** the intelligence layer — and hands a perfect prompt to whichever AI you choose."

---

## 🧭 Where this sits, of the three

**This repo knows → a runner runs → an archive remembers.**

| Role | Owns | Reach for it when |
|---|---|---|
| **this one** | facts about any repo from the GitHub API, cached by head SHA — no clone, any language, any forge | you want PR facts, conventions or a verdict without building anything |
| a **runner** you attach | execution — real applications, real JVMs, a version × config × app matrix, `bench review <n>` | the question needs something to actually run, on one project |
| a **memory** you attach | the archive: harvest, file, index, retrieve | you want it findable in a year |

The boundary that matters: **OSS-CLI never needs a clone and is never specific
to one project.** Anything that has to check out a branch, run a build, or know
what one particular project does belongs in a runner — which is why the
red/green PR gates live there and not here. Only you know what a real
application of your project looks like; the core knows how to walk a matrix, and
your repository says what the matrix is.

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
| A GitHub token only | Sync, issue tracking, PR facts, commits, diffs, CI state, and convention checks |
| ...plus the embedding model | Semantic search, vector indexing, duplicate detection |
| ...plus a local model | Local answers and PR verdicts |
| ...plus a cloud API key | Escalation when local context or confidence is not enough |
| ...plus your own notes | Your history and past reasoning blended into retrieval |

A brand-new user with none of the optional pieces still gets working commands. Missing layers print one line saying what they would add and how to enable them — never a hard failure.

---

## 🛠 Prerequisites

* **Java 17**
* **Apache Maven**
* **A GitHub token** — the only hard requirement
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

## 🚀 Setup & Installation

1. **Compile the Project:**
   ```bash
   mvn clean package
   ```

2. **Run the Interactive Wizard:**
   Securely registers your GitHub token, any cloud API keys, local model names, and note folder paths into the SQLite `system_config` table.
   ```bash
   oss setup
   ```

3. **Install Global Command (macOS/Linux):**
   ```bash
   sudo nano /usr/local/bin/oss
   # Paste: java -jar /absolute/path/to/target/oss-cli-1.11.5.jar "$@"
   sudo chmod +x /usr/local/bin/oss
   ```

---

## ⏱ Background Automation (macOS Launchd)

The project includes a background daemon (`osscli-master.sh`) that automatically syncs repositories, runs AI severity assessments, rebuilds the vector index, generates weekly reports, sends native desktop notifications for new Hidden Critical threats, and performs automated vault backups.

1. Configure `osscli-master.sh` with your correct paths.
2. Load the macOS `.plist` scheduler:
   ```bash
   launchctl load ~/Library/LaunchAgents/osscli.plist
   ```
3. Monitor the background service logs:
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
# Ollama answers locally if it can — escalates to expert prompt if context is too large
oss prompt 1666

# See exactly what context was retrieved and whether Ollama will answer or escalate
oss inspect 1666

# Force the expert prompt regardless (skip local Ollama)
oss prompt 1666 --force-prompt

# Copy the generated expert prompt to clipboard
oss prompt 1666 --copy

# Save expert prompt to a Markdown file
oss prompt 1666 --out ~/Desktop/issue-1666-prompt.md

# Auto-send to an external AI when escalation occurs
oss prompt 1666 --send-gemini
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

A Tauri-based desktop application (Rust + React) is planned, providing a Warp/Cursor-style interface:

| Panel | Content |
|---|---|
| Left Sidebar | Issues, history, searches, repositories |
| Main Panel | Prompt workbench with Markdown, copy button, edit, token count |
| Right Panel | Context Inspector — retrieved documents, sources, relevance scores |

---

## 💾 Database

The tool uses a zero-configuration SQLite database (`data/issue_intelligence.db`) with an **automatic, non-destructive migration engine**. Schema changes are applied safely at application boot without dropping existing data.

---

## 📐 New Architecture Modules

Planned and in-progress work:

* Retrieval pipeline (9 context source types)
* Prompt Builder engine and template structure
* Context organizer, written by the local generation model
* Streaming JSON API contract
* Tauri desktop UI architecture
* Plugin interface for future data sources
* Database schema additions (`prompt_history`, `prompt_context_chunks`)
* 8-milestone implementation roadmap

# CLI Command Reference (`COMMANDS.md`)

This guide outlines all subcommands available in the `oss-cli` CLI platform.

## Global Options
*   `-r`, `--repo` : Target GitHub repository to analyze (Default: `owner/reponame`).

---

## 🛠 Configuration & Setup

### `setup`
Interactive wizard to configure your secure environment, API keys, Google Drive paths, and AI models.
```bash
oss setup
```
*   **Security:** Checks active environment variables and the macOS Keychain for `GITHUB_TOKEN`, `GEMINI_API_KEY`, `OPENAI_API_KEY`, and `ANTHROPIC_API_KEY`.
*   **Storage:** Saves all configurations to the local SQLite `system_config` table.
*   The embedding model is reported, not asked for — it ships with the tool, so there is no endpoint to point at and no name to get wrong.

### `model`
Reports whether the embedding model is present, and fetches it if you ask for it.

The embedder is all-MiniLM-L6-v2 (quantised, about 22 MB, Apache-2.0). It runs in this process rather than behind a server, and lives under `~/.oss-cli/models`. Nothing downloads it on your behalf: 22 MB arriving in the middle of a search looks like a hang, and a tool that fetches things unasked is one people stop pointing at their work.

*   `--fetch` : Download it. Once, about 22 MB.

```bash
oss model            # present or not, and what it would be searching
oss model --fetch
```

While it downloads, a live status line reports the stage it is on and how long it
has been running. 22 MB over an unknown connection is the longest silence this
tool has, and the point at which a user is most likely to decide it has hung.

*   It is written to **stderr**, never stdout, so piping or redirecting a
    command's real output is unaffected.
*   The spinner and elapsed time appear only when attached to a terminal. Not a
    TTY — a pipe, cron, CI — means one plain line per step and no colour at all.
*   `NO_COLOR` is honoured, and drops it to that same plain output.
*   After 8 seconds of waiting the line grows a rotating one-line quip in its dim
    tail. `OSS_NO_QUIPS=1` removes just the quips and changes nothing else.

Without it nothing breaks — `search`, `duplicates` and note indexing rank by shared terms instead of by meaning, which needs nothing installed.

---

## 🔄 Data Synchronization

### `sync`
Fetches issues, pull requests, author profiles, and ecosystem dependencies from GitHub into SQLite.
*   `-a`, `--all` : Sequentially synchronize all active repositories in your database registry.
*   `--add <repo>` : Register a new repository to your local database watchlist.
*   `--remove <repo>` : Remove a repository from the database watchlist.
*   `--me` : **Personal Sync.** Fetches your 1-year PR history, creates your Developer Expertise Vector, and recursively crawls your Google Drive (automatically parsing assistant `.json` exports and `.md` files) to index your conversational memory.

*   `--no-embed` : Skip building the local vector index. Sync is faster, but issues fetched in that run are not semantically searchable until a later sync indexes them.

Every sync builds the vector index for the repositories it touched, using the built-in embedder that runs inside this process — no model server is involved. Indexing is incremental (only issues without a current vector), resumable (vectors commit in batches of 50), and model-aware (vectors produced by the older Ollama embedder are ignored and re-indexed here, rather than mixed into a vector space they cannot be compared against). If the embedding model has not been fetched, nothing is downloaded mid-sync: the issue data is still saved, and a warning names how many issues have no vector and how to fetch the model.

Indexing prints the same live status line as `oss model --fetch` — on stderr, with elapsed time and a running count of issues embedded. Thousands of model calls on a first index is otherwise the longest stretch of silence in a sync, and silence is indistinguishable from a hang. The same rules apply: animation only on a terminal, plain one-line-per-step output otherwise, `NO_COLOR` and `OSS_NO_QUIPS` honoured.

Sync also reads the **references** out of every issue and pull request it saves — `fixes #123`, a bare `#123`, `owner/name#123`, and commits — and stores them as edges that retrieval follows. See [`prompt`](#prompt) for what is done with them.

`sync --me` is the exception. Everything it builds is vectors, so it requires the embedder and stops with the fetch hint rather than running to no effect. Its development stories are the one part that still wants Ollama; when Ollama is absent they are skipped with a warning and everything else is indexed as usual. It also records, per note, whether the note is **your own work or material you merely collected** — see [`prompt`](#prompt).

`--add` also builds the repository profile — see [`profile`](#profile).

```bash
oss sync --all
oss sync --me
oss sync --add owner/name
oss sync --remove owner/name
```

---

## 🔎 Repository Intelligence

### `profile`
Builds a technical profile of a repository from the files it actually contains: language, build system, toolchain version, documentation, and the conventions a change must respect.

Everything is pattern-matched, never hardcoded per project. Documentation is matched by **name across any extension**, so a project whose README is `README.adoc` is not reported as undocumented. For Maven projects the **inherited POM chain is followed through Maven Central**, because many projects publish their packaging and API rules in a parent artifact rather than committing them to the repository being reviewed.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--rebuild` : Re-read the repository even if a profile is stored.

```bash
oss profile -r owner/name
oss profile --rebuild
```

Detected conventions are tagged with their source, so rules inherited from elsewhere are visible as such:

```
bnd-baseline-maven-plugin — OSGi/API baseline enforced [inherited from org.apache.logging:logging-parent:12.1.1]
checkstyle                — checkstyle enforced        [inherited from org.apache:apache:34]
```

### `onboard`
Answers "I want to contribute to this project — what do I need to know?"

Reads the same profile `review` judges against, from the other direction: a maintainer needs the rules to check a change, a newcomer needs them before writing one. Deriving both from one source is what stops contributor advice drifting from the standard pull requests are actually held to.

Reports what the project enforces **as instructions rather than plugin names** — a newcomer cannot act on `bnd-baseline-maven-plugin`, but can act on being told that adding a public class will fail the build until the API baseline is updated.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--rebuild` : Re-read the repository rather than using the stored profile.
*   `--no-steps` : Skip the model-written build steps and list the source documents only.

```bash
oss onboard -r owner/name
```

Build commands are extracted from the project's own `BUILDING` / `CONTRIBUTING` document, and the model is told to return nothing rather than supply commands from general knowledge — a plausible invented command fails somewhere unrelated to the real setup and costs more time than an admission.

Starter issues are matched on **whole words in a normalised label**, so `good-first-issue`, `Good First Issue` and `E-easy` all match while `area/resteasy-classic` and `spring boot starter` do not. They are listed fewest-comments-first: a starter issue with a long thread usually turned out to be hard, or someone is already on it.

### `review`
Reviews a pull request using every source you have connected, and nothing you have not.

Built as a **ladder**. Layer 0 needs only a GitHub token; every layer above it is optional and additive. The output states which layers were actually used — not which are installed — so a thin review is never mistaken for a clean one.

| Layer | Requires | Adds |
|---|---|---|
| Facts | GitHub token | Diff, commits, files by area, CI checks, review threads |
| Conventions | a built profile | Deterministic gate checks (no model involved) |
| History | notes corpus | Your own prior work on the changed paths |
| Verdict | a local model | Local judgment against the project's rules |
| Escalation | cloud key | The whole diff read by a cloud model when it exceeds the local budget |

Evidence is cached **by head commit SHA**, not by PR number. A pull request is rewritten by every push, so caching by number alone would serve a review of code that no longer exists. Re-reviewing unchanged code is instant; after a push it re-fetches automatically.

No local clone is required — everything comes from the GitHub API.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--refresh` : Re-fetch even when this exact commit is cached.
*   `--no-verdict` : Report facts and conventions only, without asking a model to judge.
*   `--no-notes` : Do not consult your own notes.
*   `--escalate` : When the diff exceeds the local budget, send it to a cloud model instead of truncating. Picks whichever provider key is configured.
*   `--send-claude`, `--send-openai`, `--send-gemini` : Escalate to a named provider.

Escalation fires **only when it would change the answer** — a diff that already fits the local budget is answered locally and says so, rather than spending a cloud call to reread what the local model could see anyway.

```bash
oss review 4234
oss review 4234 --no-verdict
oss review 4234 -r owner/name --refresh
```

When a verdict is produced it is filed to `<topic>/oss-cli/` in your notes archive and indexed, so it becomes retrievable evidence for later questions.

Everything OSS-CLI generates goes to that one folder, kept separate from anything you wrote yourself. Provenance then follows from location: a knowledge base can exclude generated notes when scoring what *you* know, and your own filing tools cannot collide with names OSS-CLI chose.

---

## 🔍 Prompt Intelligence (New)

### `prompt`
Core new command. Runs the full **Retrieve → local model → Adaptive Response** pipeline.

**The local model answers first.** The command retrieves all relevant local context (issues, PRs, stack traces, chat logs, notes, similar fixes), estimates the token count, and passes everything to the local model.

- **If context fits within the local model's limit AND confidence is high** → the local model answers directly from the local database. No external AI needed.
- **If context exceeds the local model's limit OR confidence is too low** → The platform builds a high-quality expert prompt from all gathered context and presents it for copy/send to your AI of choice.

Retrieves from all local sources:
- Issue body, labels, and comments
- Related issues and linked PRs
- Issues, pull requests and commits this one **references**, and the ones that reference it
- Similar past fixes (vector similarity)
- Extracted stack traces
- Previous AI conversation transcripts
- Personal notes from Google Drive
- Cross-project JIRA dependencies

Two of those are not similarity, and are worth stating precisely:

**References are followed in both directions.** A stated reference is not a resemblance to be scored: a pull request whose entire body is "fixes #4100" shares almost no wording with the issue it closes, so ranked by similarity the two look unrelated at exactly the moment they are most related. The incoming direction is the one nobody records — an issue does not know which pull request closed it, because the claim lives in the pull request. `fixes/closes/resolves #N` is stored as a stronger edge than a bare `#N` and scores higher here, because somebody said so rather than a ranking inferring it. Fenced and inline code are stripped before matching, so a stack trace or log line mentioning `#1` does not become an edge. Commits are recorded from a full 40-character SHA, a `/commit/<sha>` URL, or the literal word "commit" followed by a hash; they stay in the index but are not spent on prompt budget, since without a clone there is nothing local to say about them. References to a repository you have not synced are recorded too — retrieval simply only follows the ones whose target it has.

**Collected discussion ranks below your own work.** Notes are classified as knowledge or reference (see [`sync --me`](#sync)). Reference passages still compete for the budget, at a 0.75 discount, and are labelled `reference` rather than `chat_memory` in the assembled prompt so it is visible which passages came from a discussion you had no part in.

```bash
oss prompt 1666                   # Ollama answers, or builds expert prompt if too complex
oss prompt 1666 --copy            # Copy generated prompt to clipboard (macOS pbcopy)
oss prompt 1666 --out prompt.md   # Save prompt to a Markdown file
oss prompt 1666 --send-gemini     # Auto-send to Gemini when escalation occurs
oss prompt 1666 --send-openai     # Auto-send to OpenAI when escalation occurs
oss prompt 1666 --send-claude     # Auto-send to Anthropic when escalation occurs
oss prompt 1666 --force-prompt    # Skip Ollama — always build and display the expert prompt
```

*   **Thresholds (configurable via `setup`):**
    *   `ollama.context_limit` — Max tokens Ollama handles locally (default: `4096`)
    *   `ollama.confidence_threshold` — Min confidence to trust local answer (default: `0.70`)
*   **Escalation reasons logged to SQLite:** `context_overflow`, `low_confidence`, `forced`

### `inspect`
Shows the raw context retrieval result for an issue — what documents were found, their sources, relevance scores, and token counts. Critically, it also shows **whether Ollama would answer locally or escalate to a prompt**, so you can understand the decision before running `prompt`.

```bash
oss inspect 1666
oss inspect 1666 -r owner/name
```

*   **Output includes:**
    *   Each retrieved document (source type, reference, relevance score, token count). Source types include `referenced_issue` for a stated reference, and `reference` for a passage from collected material rather than your own notes (`chat_memory`)
    *   Documents dropped due to token budget limits (marked `excluded`)
    *   Total token estimate vs. `ollama.context_limit`
    *   **Decision preview:** `✔ Ollama will answer locally` OR `⚠ Context too large — prompt will be built`
    *   Escalation reason if applicable (`context_overflow` / `low_confidence`)

---

## 🤖 The Personal Copilot

### `chat`
An interactive conversation about one issue, which survives the terminal it was started in.

*   **Saved as you go.** Every turn is written to SQLite the moment it is said. Ctrl-C, a closed lid or a dropped connection loses nothing — the conversation is state on disk that a process is attached to, not state in a process.
*   **Context aware, and budgeted.** Retrieves your personal memory (past PR stories, assistant exports, notes) ranked by relevance, and fills the model's token budget in that order — the same retrieval `prompt` uses. It reports what it used: `25 of 67 matching passages included (~5943 tokens; the rest did not fit)`.
*   **Either model will do.** Ollama, a cloud key, or both. It refuses only when you have neither, and then it names both ways to fix it.

| You have | What happens |
| --- | --- |
| Ollama and a key | The local model answers. `y` escalates the last question, and the cloud's answer is read back against your own past work. |
| Ollama only | The local model answers. Nothing to escalate to, so `y` is not offered. |
| A cloud key only | The cloud answers every turn directly — no `y` needed, since there is nothing to escalate *from*. Answers cannot be aligned against your history, and it says so each time. |
| Neither | Refuses, naming both ways to fix it, and points at `oss prompt` which assembles the same context as a prompt you can paste anywhere. |

*   **Alignment needs a local model.** When both are connected, an escalated answer is read back against your past PRs and notes before you see it. Sending that history to the same API that wrote the answer would undo the reason the two steps are separate, so with only a key the step is skipped — visibly, because an answer that has *not* been checked looks exactly like one that has.
*   **Filed once.** On `exit` the transcript is written to your archive and embedded. Resuming rewrites *that same note* rather than filing a second overlapping copy.

```bash
oss chat 4129                  # start on an issue
oss chat --continue            # carry on with the most recent conversation
oss chat -c                    # the same thing
oss chat --resume 7            # resume a specific one
oss chat --resume              # pick one from the list
oss chat 4129 --resume         # the latest conversation about #4129
oss chat 4129 --openai         # escalate to OpenAI instead of Gemini
```

**Long conversations are folded, not silently truncated.** Once the transcript outgrows the model's context, the older turns are summarised into a running summary and the recent ones kept verbatim. With no generation model attached the oldest turns are dropped instead — and that is printed, because quietly forgetting the first half of a conversation while continuing to answer confidently is the failure worth shouting about. The full transcript stays readable in `oss history --show` either way.

**Two things you can type that are not questions.** Compaction is lossy however carefully it is done, so the moment it happens should never be the first you hear of it.

| Typed at the prompt | What it does |
| --- | --- |
| `/context` | Draws how full the conversation is: characters used against the budget, how many turns, how much the retrieved notes are taking, and whether anything has been folded already. |
| `/compact` | Folds the older turns now, rather than waiting for the automatic threshold mid-answer. Says what it reclaimed, or that there was nothing to fold. |

Neither becomes a turn, so neither shows up in the transcript or goes to the model.

**The budget covers the whole prompt, not just the transcript.** The retrieved notes beside it are charged against the same window — they used to be budgeted separately, which meant two limits that could each be satisfied and still overflow together. The ceiling is `chat.context.chars` in `system_config`, defaulting to 32,000 (≈ an 8k-token window, which is what small local models commonly run). Raise it if your model has more room; a value that is missing, zero or not a number falls back to the default rather than failing.

A conversation that has folded older turns is marked with a `+` after its turn count in `oss history`, so the number you see is honestly "still verbatim" rather than "everything ever said".

### `history`
Every conversation you have saved, and the way back into any of it.

Plain `oss history` is interactive: arrow keys (or `j`/`k`) move through the list, a preview shows what each conversation was about and where it got to, and `enter` resumes the highlighted one.

```bash
oss history                        # browse and resume
oss history --list                 # print the list, open nothing
oss history --show 7               # print one conversation in full
oss history --search "the flaky test one"
oss history -r owner/name -i 4129  # narrow to one repository or issue
oss history -n 200                 # look further back than the default 50
```

`--search` matches by **meaning** using the built-in embedder, so "the flaky test one" finds a conversation that never used the word flaky. With no model on disk it matches shared terms instead, and says which it did.

**Without a terminal it still works.** Raw keyboard input needs a real TTY and, on unix, `stty` — not available under cron, in CI, over some remote shells, or on Windows. There the same list is numbered and picked by typing a number, and `oss chat --resume <id>` needs no list at all.

### Several terminals at once
The conversations live in one SQLite database shared by every `oss` process, so what you say in one terminal is visible to `oss history` in the next as soon as it is written.

*   **Reading never blocks.** The database runs in WAL mode, so a long `sync` in one window does not stop a chat in another. Writers take turns and wait rather than failing.
*   **Two terminals cannot interleave one conversation.** Each session records the process holding it and a heartbeat. Resuming one that is live elsewhere offers to **fork** it — a new conversation carrying the same history — so neither transcript ends up a mix of two people's thinking.
*   **A dead terminal does not lock you out.** The heartbeat goes stale on its own after two minutes; there is no lock file to clean up after a crash.
*   **The knowledge base is shared once a conversation ends.** Turns are private to their conversation while it is open. On `exit` the transcript is filed and embedded, and from then on every terminal's `search`, `prompt` and `review` can retrieve it.

### `guide`
Generates a structured, step-by-step resolution blueprint for a specific issue using local RAG (Retrieval-Augmented Generation).

*   **Context is budgeted**, the same way `chat` and `prompt` budget theirs, and it says how much of what matched was used.
*   **Either model will do**, like `chat`. With a local model it drafts locally and offers to refine with a cloud expert; with `--gemini`, `--openai` or `--claude` it goes straight to the cloud — including when no local model is installed at all.
*   `--gemini` / `--openai` / `--claude` : Bypass the local model and route immediately to that API.
*   **Verification needs a local model.** A cloud blueprint is normally read back against your own past work before you see it. Without a local model that step is skipped and says so, because an unverified blueprint looks exactly like a verified one.
*   It refuses only when you have neither, and then names both ways to fix it.
```bash
oss guide 1666
```

### `triage`
Executes an automated triage audit on a specific issue.
*   Outputs V1 severity, local AI severity, semantic duplicate overlaps, JIRA-bridge dependencies, and immediate recommended actions.
```bash
oss triage 4088
```

---

## 📊 Analytics & Reporting

### `critical`
Performs a fast, fully **offline** severity ranking of all locally-synced issues using the V1 keyword-score engine. No Ollama or cloud API required. Ideal for an instant triage snapshot.

```bash
oss critical
oss critical -r owner/name
```
*   **Output:** Summary count (Critical / High / Medium / Low) followed by ranked issue lists sorted by score descending.
*   **Options:** `-r`, `--repo` — Target repository (defaults to `default.repository` if not provided).

### `search`
Performs an offline semantic vector lookup on your backlog, using the built-in embedder. No model server is involved.
*   `-g`, `--global` : Run the search across all synced repositories simultaneously.
```bash
oss search "deadlock in network appender" --global
```
When nothing has been indexed yet, or the embedding model has not been fetched, the search ranks by shared terms instead of refusing — the issues are already local, and declining to search data you have is the wrong answer to a missing optional layer.

### `report`
Compiles SQLite data into a unified Weekly Health Report in Markdown format.
*   `--me` : Generates a highly personalized roadmap tailored to your Developer Expertise Vector, including Regression Guard alerts and your specific stale PRs.
```bash
oss report --me
oss report -r owner/name
```

### `trend`
Tracks project health metrics over time.
*   `-s`, `--save` : Save today's metrics as a new historical snapshot.
```bash
oss trend --save
```

### `analyze`
Performs batch AI Severity Analysis on all open issues using local Ollama.
```bash
oss analyze
```

### `duplicates`
Identifies duplicate issue clusters using local vector embeddings (Cosine Similarity). The vectors come from the built-in embedder, so no model server is involved; anything not indexed yet is embedded here and cached. Near-duplicates are the one thing shared terms cannot find — they say the same thing in different words — so without the model this clusters only what was indexed earlier and says how many issues it left out.
*   `-t`, `--threshold` : Cosine similarity threshold, 0.0 to 1.0 (default: `0.80`).
```bash
oss duplicates -t 0.85
```

### `hidden-critical`
Cross-references raw metadata against AI evaluations to detect underestimated security threats.
```bash
oss hidden-critical
```

---

## 💾 Backup & Restore

### `backup`
Export your entire AI memory and database into a portable archive, with automatic rotation that keeps the last 5 backups.
```bash
oss backup
```
*   **Archive format:** `sa_brain_backup_yyyyMMdd_HHmmss.zip` (contains `.db` and `.txt` files from `data/`).
*   **Destination:** Uses `backup.path` from `system_config`, or defaults to the global backups folder.
*   **Auto-rotation:** Automatically deletes the oldest archive once more than 5 backups exist.

### `restore`
Import and restore your AI memory and database from a previously created backup archive.
```bash
oss restore /path/to/sa_brain_backup_20260627_104000.zip
```
*   **Parameters:** `<backup-file>` — Path to the `.zip` backup archive.
*   **Safe restore:** Buffers all local `system_config` values (API keys, model paths) before extraction and re-applies them afterward — your credentials are never overwritten.
*   **Security:** Protected against Zip Slip path traversal attacks.

---

## 🩺 Health

### `doctor`
Checks every prerequisite in turn and names what to fix. It is the first thing to run when something is not behaving, and it works when nothing else does — including when the database itself has been refused.

What it reports:

*   **token** — whether a GitHub token is present and usable
*   **data directory** — where `~/.oss-cli` resolved to, and whether `OSS_CLI_HOME` moved it
*   **database** — that the file exists and its size
*   **schema** — the store's schema version against the one this build understands
*   **models** — whether the built-in embedder's weights are on disk, and whether a generation model is reachable
*   **vector provenance** — that stored vectors were produced by the embedder currently in use

```bash
oss doctor
```

#### The schema check, and why it can fail

Migrations only ever run forwards. If a **newer** `oss` has already migrated your store, an older build cannot understand it — so it refuses rather than reading tables whose meaning may have changed and then writing rows in the shape it believes in:

```
This database was written by a newer oss than this one.
  database:    schema 15
  this build:  schema 14  (oss 1.11.0)
Nothing has been read or changed.
```

Before this refusal existed, that case fell through in **silence**: the migration loop matched nothing and the command carried on regardless.

Every other command exits `1` in this state. `doctor`, `--version` and `--help` keep working on purpose — taking away the command that explains the problem is a poor way to report it.

```bash
brew upgrade oss                       # the usual fix
OSS_CLI_HOME=~/other-store oss ...     # work elsewhere meanwhile
oss doctor                             # reports both versions
```

A store **older** than this build is not a problem: the next command migrates it forwards, and `doctor` says so.

---

## Quick Reference

| Command           | Mode           | AI Required             | Description                                  |
|-------------------|----------------|-------------------------|----------------------------------------------|
| `setup`           | Interactive    | No                      | Configure API keys, paths, models            |
| `model`           | Local          | No                      | Report or fetch the built-in embedding model |
| `serve`           | Local service  | No                      | **Runs locally on http://localhost:1504; attach benches by path** |
| `ext add <path>`  | Local          | No                      | Attach a bench/kb from a local repo containing `oss-ext.json` |
| `ext list`        | Local          | No                      | What is attached, and whether it is still reachable |
| `bench <verb>`    | Local          | No                      | Dispatch to an attached **bench** (runs something real) |
| `kb <verb>`       | Local          | No                      | Dispatch to an attached **kb** (files and indexes) |
| `sync --all`      | Online         | No                      | Sync all registered repositories             |
| `sync --me`       | Online         | Embedder (required)     | Sync personal PR profile + Drive logs        |
| `profile`         | Online         | No                      | Language, build, toolchain, conventions      |
| `review`          | Online         | Optional Ollama         | **Review a PR from every connected source**  |
| `onboard`         | Online         | Optional Ollama         | What a project expects before you contribute |
| `critical`        | Offline        | No                      | Fast keyword-score severity ranking          |
| `analyze`         | Offline        | Ollama (local only)     | AI batch severity scoring                    |
| `duplicates`      | Offline        | Embedder                | Vector-based duplicate detection             |
| `prompt`          | Offline/Online | Ollama + Optional cloud | **Generate expert prompt from full context** |
| `inspect`         | Offline        | No                      | Show retrieved context for an issue          |
| `search`          | Offline        | Optional embedder       | Semantic vector search, else shared terms    |
| `triage`          | Offline        | Ollama                  | Full triage audit for one issue              |
| `guide`           | Offline/Online | Ollama **or** cloud key | Step-by-step resolution blueprint            |
| `chat`            | Offline/Online | Ollama **or** cloud key | Resumable conversation, saved as you go      |
| `history`         | Offline        | Optional embedder       | Browse and resume saved conversations        |
| `report`          | Offline        | No                      | Weekly health report                         |
| `trend`           | Offline        | No                      | Historical metric snapshots                  |
| `hidden-critical` | Offline        | No                      | Underestimated security threat detection     |
| `backup`          | Offline        | No                      | Export AI memory to zip archive              |
| `restore`         | Offline        | No                      | Restore AI memory from zip archive           |
| `doctor`          | Offline        | No                      | Checks every prerequisite and names the fix  |

**Offline** and **Online** in the Mode column are exact: see
[OFFLINE.md](OFFLINE.md) for the full list of what opens a socket and what does not.

**Embedder** means the built-in all-MiniLM-L6-v2 that runs in this process — `oss model --fetch`, once, and nothing is running afterwards. **Ollama** means local text generation, and nothing in this table indexes or searches through it.

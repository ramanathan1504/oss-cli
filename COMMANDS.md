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

**Pointing a provider somewhere else.** The cloud endpoints are overridable, for a corporate gateway, a self-hosted relay or an Azure-style deployment:

| Provider | Environment | Config key |
|---|---|---|
| Gemini | `GEMINI_BASE_URL` | `gemini.base_url` |
| OpenAI | `OPENAI_BASE_URL` | `openai.base_url` |
| Claude | `ANTHROPIC_BASE_URL` | `claude.base_url` |

A JVM property (`-Doss.claude.base_url=…`) wins over both, for a single run. An override is announced on the first request, because a redirected client that fails looks exactly like a rejected key. A trailing slash is trimmed, so pasting one does not produce a `//` that 404s.

**Sync fetches issues that are still open, and only those changed since its last run.** A closed issue therefore never arrives this way — `sync` reports `Open Issues Saved: 0` and nothing changes, however many times it is run. Reach for it by number instead:

```bash
oss issue 4129 --repo apache/logging-log4j2
```

That fetches the issue whatever its state and **keeps it**, so `chat`, `prompt` and the rest can use it afterwards. Which matters more than it sounds: a closed issue is the kind you go back to read, because it is closed on account of having been resolved and the resolution is the interesting part.

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

### `hub`
Is anyone waiting on you? Every project you follow, in one list.

Ranks by whose turn it is rather than by date, so a thread where the ball is in your court outranks a busier one where it is not.

*   `-r`, `--repo <repo>` : Only this repository.
*   `--all` : Include the ones where the ball is not in your court.

```bash
oss hub
oss hub --all
```

### `pick`
What to work on next, scored against what you have already worked on.

Ranks the backlog against your own history — the areas you have touched, the reviews you recorded — so the suggestion is one you are already equipped for rather than the newest thing open. With nothing recorded yet it says so and names the two commands that build that profile.

*   `-r`, `--repo <repo>` : Only this repository.
*   `--limit <n>` : How many to suggest (default 10).
*   `--issues-only` : Skip pull requests.

```bash
oss pick
oss pick --issues-only --limit 5
```

### `prs`
Analyse cached open pull requests for stale status, reviews, and critical fixes.

Reads what `sync` already stored, so it answers offline.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.

### `pr`
Every mechanical fact about a pull request.

No judgement and no model: the title, author, state, base branch, head commit, and — on request — the files or the patch itself.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--files` : Only the files it touches.
*   `--diff` : The patch itself.

```bash
oss pr 4249 -r owner/name
oss pr 4249 -r owner/name --files
```

Issues and pull requests share one numbering sequence on GitHub, so asking for a number that is an issue says exactly that rather than failing on a null.

### `issue`
Read an issue as it was filed.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--comments` : Include the discussion.

```bash
oss issue 1666 -r owner/name --comments
```

### `followup`
What moved on a reviewed pull request since you reviewed it.

Records that you reviewed a pull request at a particular head commit, then tells you what the author pushed after that — so a re-review starts from the difference rather than from the top.

*   `-r`, `--repo <repo>` : Target repository. Defaults to the repository already on that row.
*   `--record`, `--sync <pr>` : Record a pull request as reviewed at its current head.
*   `--verdict <v>` : With `--record`: `take`, `changes`, `blocked` or `routine`.
*   `--note <text>` : With `--record`: one line, for you, later.
*   `--since <pr>` : What the author pushed since you reviewed it.
*   `--write` : With `--since`: append the report to the review file.
*   `--changed` : Only the ones that moved.
*   `--mine` : Only where the last word is not yours.
*   `--comment <pr>` : Print just the paste-ready block of a review, to pipe.

`--comment` prints; it does not post. Nothing in this tool writes to anybody's repository — every GitHub call it makes is a read.

### `ext`
Attach and inspect runners and memories.

An extension is a directory somebody points at; the tool reads its manifest and gains the verbs it declares. Nothing is downloaded and nothing is registered without a path being given.

*   `oss ext list` : Show every registered extension.
*   `oss ext add <path>` : Register the extension declared by a repository.
*   `oss ext remove <name>` : Unregister an extension.
*   `oss ext refresh <name>` : Re-read a registered extension's manifest from disk.

### `alias`
Give this command your own name — `buddy`, `hey`, anything.

*   `--list` : Show the names already created.
*   `--remove <name>` : Remove a name.
*   `--force` : Create it even if the name is already taken.

```bash
oss alias buddy
oss alias --list
```

### `backlog`
The whole backlog as one page: what is mergeable, what is one fix away, what to pick up next.

Fetches the repository's open issues and pull requests, buckets them, builds a cross-reference graph between them, and writes a single HTML page into the current directory. The fetch is cached, so `--dry-run` rebuilds the page from the last real fetch without spending API calls.

*   `-r`, `--repo <repo>` : Target repository. Defaults to `default.repository`.
*   `--no-ai` : Skip the model-written enrichment.
*   `--dry-run` : Reuse the cached fetch rather than calling GitHub again.

```bash
oss backlog -r owner/name
oss backlog -r owner/name --dry-run
```

The report itself is POSIX shell, so on Windows it says so and points at WSL rather than half-running. Anything option-shaped that this command does not recognise is refused **here**, by name — passing it down would make the shell script answer with its own interface, which names flags and environment variables no reader of `oss backlog --help` has heard of.

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
Escalation fires **only when it would change the answer** — a diff that already fits the local budget, on a rung that can read it, is answered here and says so rather than spending a call to reread what the local model could already see.

```bash
oss review 4234                    facts, conventions and your notes; no model
oss llm review 4234                + a local verdict
oss claude review 4234             + Claude, and it reads the whole diff
oss review 4234 --verify --clone ~/src/project    + built and re-run with the change reverted
oss review 4234 --no-verdict       facts only
oss review 4234 -r owner/name --refresh
```

### `--verify` — the layer that produces facts

Every other layer reads. This one **builds the change and runs its tests, then takes the production
change back out and runs them again**:

```
── Verification (built and run, not read) ──
  ✔ build
  ✔ tests with the change
  ✔ revert the change
  ✘ tests without the change — Tests run: 15, Failures: 1, Errors: 1

  ✔ ThrowablesTest — fails when the production change is reverted
  ⚠ SomeOtherTest — passes with the production change reverted
      It would have passed before the change, so it is not covering it.
```

That warning is the point. **A test that passes with the fix reverted is proving nothing**, and it
is invisible to reading: the test is present, well written, green, and covers none of the change.
No model can find it, because the fact does not exist in the diff — it only exists once something
has been run.

- Everything happens in a throwaway `git worktree`; **your working tree is never touched**.
- The change is reverted to the **merge base**, not to the branch tip — the tip has moved on, and
  reverting to it would revert other people's work and make every test look proven.
- Each test class is judged on **its own** result line, not on the build's exit code, and a class
  that never ran reports `was not run` rather than passing quietly.
- A file the change **adds** is removed rather than restored — there is no earlier version of it to
  restore to, and reverting the set with one checkout used to fail on it and so revert nothing.
- If the project will not build once the change is out — usually because the change adds a type the
  test names — that is reported as **nothing proven**, never as proof. A test run that fails because
  it could not compile looks exactly like one that failed because it caught the change.

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
oss gemini prompt 1666            # Send it, if the local rung falls short
oss codex prompt 1666             # the same, to OpenAI
oss claude prompt 1666            # the same, to Anthropic
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
oss codex chat 4129            # escalate to OpenAI instead of Gemini
```

**Long conversations are folded, not silently truncated.** Once the transcript outgrows the model's context, the older turns are summarised into a running summary and the recent ones kept verbatim. With no generation model attached the oldest turns are dropped instead — and that is printed, because quietly forgetting the first half of a conversation while continuing to answer confidently is the failure worth shouting about. The full transcript stays readable in `oss history --show` either way.

**Every answer says what went into it.** `review` has always closed with the layers it used; `chat` and `guide` now do the same. Without it an answer built from your whole corpus and one built from the issue title alone print identically, and you are left guessing which you got.

```
── What went into this answer ──
  ✔ The issue as filed              #4129 in apache/logging-log4j2
  ✔ Your own prior work             22 passages (~5750 tokens) of 32 that matched
        1 issue · 16 notes · 5 related issues
  ✔ Answered by                     Gemini
  ✗ Read back against your history  no local model — the API that wrote the
                                    answer cannot also check it

── What would make the next one better ──
  · attach a local model that fits — then a cloud answer is checked against your own work
```

Three things in order: **what you already had**, **what the model added**, and **what would improve the next one**. `22 of 32` is the honest number — ten of your own passages did not fit the budget, and saying so is the point. Every absence carries its remedy, because an absence without one is a complaint.

Alignment is the line worth watching. A cloud answer read back against your own past work is a different object from one that was not, and only a local model can do that check — sending your history to the same API that wrote the answer would undo the reason the two steps are separate.

**A model that does not fit is not loaded.** Ollama does not refuse a model larger than the free memory — it loads it, the machine swaps, and everything stops responding for minutes. That cannot be read like an error or cancelled like a command; it has to be waited out. Measured at ten minutes for a 7B model on an 8 GB laptop with a browser open.

So the size is checked first, against what is actually free:

- at most **half the free memory** is offered to a model, and the other half is left for everything else you are running — fitting the model in and making the desktop unusable is the same freeze from where you sit
- when it does not fit, the largest installed model that *would* is named, because "too big" is a complaint and "too big, use this one" is an instruction
- `chat` falls back to whatever else is connected; `guide` says to pass `--gemini`, `--openai` or `--claude`
- `oss doctor` reports it in advance: `guidance model — qwen2.5-coder:7b does not fit in memory right now`

A machine whose memory cannot be read is never refused on. The check exists to prevent a freeze, and refusing on no evidence would be its own kind of broken.

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

**A result below 0.25 is not a result.** Ranking by meaning always produces a ranking, so a corpus with nothing on your subject still returns its least-unrelated documents — and prints them in exactly the shape a real hit takes. Asked for `keyspace` against six notes, this used to answer with three, at 0.10, 0.09 and 0.08, about a website deployment and two releases. Below the floor nothing is listed and the search says so; real subject matches land at 0.35 and above, and are unaffected. The floor is read from the `search.relevance_floor` config key when one is present, so a restored configuration can carry a different value; there is no command that writes it today.

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

### `serve`
Runs the local page on `http://localhost:1504`: the board, the questions you can ask, and a palette of whatever extensions are attached.

**The board opens on `oss hub`** — who is waiting on you, across every repository you follow — and lists the pull requests you have reviewed, from the same ledger `hub` and `followup` read. Beside each row is the question that belongs there: **Seen this?** on every row, and **Since I reviewed** only where a verdict exists, because anywhere else it would answer about nothing.

**The page never reimplements a command.** Every button runs one and shows what came back, so the two cannot disagree — and if they ever did, the page would be the one lying. Hovering a button says what it asks and which command it runs.

**Nothing reachable from the page writes.** A browser has no terminal, and an outward write must be confirmed at one, so the ask table carries only commands that read — `hub`, `pick`, `search`, `duplicates`, `followup`, `hidden-critical`, `doctor`. A test fails the build if a command that writes is ever added to it. Anything that posts stays on the CLI.

*   `--install` : Start it at login and restart it if it dies.
*   `--uninstall` : Stop starting it at login.
*   `--port` : Somewhere other than 1504.
*   `--no-open` : Do not open a browser.

**`oss serve` on its own is foreground.** It runs while that terminal is open and stops when it closes; `--install` is what makes it outlive the terminal. If `localhost:1504` is dead after you were using it, that is almost always why.

`--install` uses the platform's own service manager — launchd, `systemd --user`, or the Task Scheduler — rather than a background thread, a wrapper script or a cron entry. None of those restart after a crash, survive a reboot, or can be inspected and stopped with the tools an administrator already knows.

**What it records matters.** A service stores *how to start itself* at install time, and if that path later moves it dies at every login into a log nobody reads — the symptom is "the page stopped working" with nothing to connect it to. This wrote the resolved jar, which under Homebrew is `…/Cellar/oss/<version>/libexec/lib/oss.jar` — a directory `brew upgrade` deletes. It now records `oss` as found on `PATH`, because Homebrew re-points that name on every upgrade. Re-running the installer rewrites the recorded path, which is the fix for any service that has stopped answering:

```bash
oss serve --uninstall && oss serve --install
launchctl list | grep osscli          # second column is the last exit status
```

Logs are in `~/.oss-cli/logs/serve.{out,err}.log`.

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

## 🧠 Which engine may answer

The engine goes **in front of** the command, and it is the whole answer to "did a model see this,
and whose".

```bash
oss review 4249              nothing leaves this machine
oss llm review 4249          a local Ollama daemon may answer
oss claude review 4249       Anthropic Claude may answer
oss gemini review 4249       Google Gemini may answer
oss codex review 4249        OpenAI may answer
oss llm claude review 4249   either may, in that order
```

### `--cli` — answer on the subscription instead of the API key

Each of the three cloud engines has a command-line tool of its own, and anybody using it has
already installed and signed in to it. `--cli` reaches the engine that way, so the call bills
against the subscription rather than against API credit:

```bash
oss claude --cli review 4249     Claude, through the Claude Code CLI
oss codex --cli review 4249      OpenAI, through codex exec
oss gemini --cli review 4249     Google, through the gemini CLI
```

An account with no API credit is not a broken install. When a provider refuses a call for
billing and its tool is on your PATH, the error says so and names the one keystroke that
recovers — but it never switches by itself. Which engine saw your code, and whose account paid,
stays the line you typed.

Two things worth knowing before you use it. These tools are **agent harnesses, not completion
endpoints**: they can read files and run commands, so `codex` is invoked `--sandbox read-only`
and none of them is pointed at the repository being reviewed. And `oss llm --cli` does not
exist — Ollama is a daemon this already speaks to, with no command-line tool to put in front.

### May, not will

Naming an engine grants **permission**, it does not order a call. Every ask starts on the local
rung — your own notes, the vector index, the deterministic checks — and an external engine is
reached only when that rung fails a test the command states out loud, with the reason printed:

```
↳ qwen2.5-coder:7b answered and the diff fits its budget — no call to claude needed.
```

A question your own archive already answers is not worth a network round trip, and paying for one
anyway is how a tool teaches you to distrust its judgement about when it genuinely needs help.

### Three classes of command

| | commands | plain `oss` | with a prefix |
|---|---|---|---|
| **never generates** | everything else | the whole command | **refused** — the prefix would change nothing, and running anyway would suggest a model was involved |
| **optionally generates** | `review` `onboard` `sync` | facts, conventions, your own notes | adds a verdict, build steps, or development stories |
| **only generates** | `analyze` `chat` `guide` `prompt` | says which prefix it needs | answers |

`analyze` refuses a **cloud** prefix on purpose: it scores a whole backlog in a loop, and doing
that against a metered API is a bill nobody asked for. `oss llm analyze` is the local form, and
`oss critical` ranks the same backlog with no model at all.

`triage` is in the first row and used to be documented as an Ollama command. It never generated
anything — it reads the stored `analyze` result and the keyword analyzer.

### What replaced what

| gone | now |
|---|---|
| `review --escalate` | `oss claude review <n>` (or `gemini` / `codex`) |
| `review --send-claude` etc. | `oss claude review <n>` |
| `chat --claude` / `--gemini` / `--openai` | `oss claude chat <n>` |
| `guide --claude` / `--gemini` / `--openai` | `oss claude guide <n>` |
| `prompt --send-claude` etc. | `oss claude prompt <n>` |
| an automatic local verdict when Ollama happened to be running | `oss llm <command>` |

The last row is the one that changes behaviour without a flag being typed: a daemon that happens
to be installed is no longer treated as a request.

### Running a local model in this process

`oss` can run an ONNX decoder **in its own process** — no daemon, no key, no network, nothing to
start first. Point it at one:

```bash
export OSS_BUILTIN_MODEL=~/models/qwen2.5-3b-instruct-int8.onnx
```

The model's shape is read from the graph, so any decoder exporting the usual
`input_ids` / `attention_mask` / `position_ids` / `past_key_values.N` signature works. Loading is
**refused** rather than attempted when the machine cannot spare the memory: a runtime that takes
more than the free RAM does not fail, it swaps, and the machine stops responding for minutes in a
way that cannot be read as an error or cancelled.

**Nothing is bundled, and that is measured rather than assumed.** A small model was going to ship
inside the install so that naming no engine still produced a sentence. Two were built and measured
against five real pull requests:

| asked for | result |
|---|---|
| one-sentence summary from the title | invented — "add a new exception type" for a change that adds none |
| one-sentence summary from the diff | invented — "reviewed the changelogs" for a change touching none |
| pick one of six labels, raw scores | 1 of 5, and the same label every time |
| pick one label, prior subtracted | 2 of 5 at 135M, 1 of 5 at 360M |
| pick one label, four examples given | 2 of 5, a different two |

Three lines of keyword matching score 5 of 5 on the same set and cannot invent anything. So the
capability ships and the claim does not: on a machine with room for a 3B model this is genuinely
useful, and on a laptop the honest default is the deterministic path plus `oss llm`.

### The knowledge base is built in

`oss memory` is the whole knowledge base, and needs no repository. `oss kb` is the same command
under a shorter name:

```bash
oss memory file notes.md      # keep a note
oss memory index              # read it into the corpus
oss memory search "…"         # find it again, by meaning
oss memory map                # which notes touch which topic
oss memory coverage           # what you have covered, and what you have not
oss memory harvest            # pull your own public work on GitHub into the archive
oss memory digest             # what you actually worked out, per topic
oss memory import <folder>    # a chat product's data export, redacted
```

`harvest` is the one verb here that needs the network. It searches for everything you were
*involved* in — the comment you left, the review you gave, the issue you triaged — not only what
you authored, and writes one markdown file per item. `sync --me` is narrower on purpose: it stores
the pull requests you wrote and got merged, which is a fraction of the record.

```bash
oss memory harvest                 # the username from oss setup
oss memory harvest someone-else    # or say whose
oss memory index                   # then read them into the corpus
```

Each harvested note carries the three headings every harvester in this archive writes — the problem
as filed, the conversation in order with who said what and when, and how it ended — because `digest`
reads those headings and a harvest with its own layout would write notes the rest of the tool cannot
read.

`digest` is the difference between an index and an answer. `map` tells you which notes mention log4j;
`digest` reads them and says what was solved, putting **the public record above private reasoning** and
labelling each — what was agreed on a thread and what was reasoned in a conversation are different
kinds of evidence, and merging them reads as one account when it is two.

`import` is for the products that keep nothing on your machine. Claude Code, codex and gemini write
their sessions to disk, so `harvest` can go and get them; ChatGPT, Claude.ai and AI Studio do not, so
the only route in is the export you download. **Secrets are redacted rather than dropped** — a real
export carried AWS keys and tokens in seven conversations whose troubleshooting was worth keeping —
and the original download is never modified. Files that are not text are counted, not silently
skipped, because an export is mostly screenshots and silence would read as loss.

The file name is stable, so harvesting or importing twice rewrites the note rather than filing a second copy.
Everything it writes is ordinary markdown in a folder — no database, no front matter, nothing an
archive extension has to understand — which is why the built-in can read what an extension wrote,
and the other way round.

With nothing configured it works over `~/.oss-cli/memory`. Pointing it somewhere else, and telling
it what you are trying to learn, is a file — `~/.oss-cli/kb.json`:

```json
{
  "archive": "~/Documents/notes",
  "topics":  { "log4j": ["log4j", "appender", "layout"] },
  "yardsticks": { "log4j": ["Appenders", "Layouts", "Filters", "Lookups", "Garbage-free logging"] }
}
```

**A yardstick is the outside opinion, and it is the point of `coverage`.** Counting your own notes
can only report what you have written: an archive with nothing on Lookups will happily report all
of its Log4j notes as Log4j notes and call that complete. The yardstick is what the technology's
own manual documents, so an area nobody wrote about scores `○ nothing` instead of being invisible.

Three grades, and the floors are deliberate: an area needs **3 mentions in a note** before that note
counts for it — one passing use of a word is not knowledge of the subject — and **3 notes** before it
grades `● covered` rather than `◐ thin`. A single long note that returns to a term forty times is
one afternoon's reading; three notes that each come back to it is a subject you have worked in.

Matching is literal and case-insensitive on purpose. A model deciding whether a note is "about" an
area turns a measurement into an opinion, and makes the number move when nothing was written.

### A pack is a file

A pack is what points the built-in engine at *your* applications, versions and configurations. It
is **data the tool reads**, not a program it runs:

```json
{
  "name": "log4j",
  "description": "Apache Log4j across a version x config x app matrix, on real JVMs",
  "useWhen": { "repository": "apache/logging-log4j2", "files": ["log4j-core/pom.xml"] },
  "versions": ["2.24.1", "2.25.5", "2.26.1"],
  "defaultVersion": "2.26.1",
  "apps": ["core-java", "db", "network"],
  "appsDir": "apps",
  "configsDir": "configs",
  "modulePath": "apps/{app}"
}
```

Save it as `pack.json`, or as a ```json block inside `pack.md` if you want the same file to explain
itself to a person as well. A worked example ships at `runner/packs/example-json/pack.json` —
copy it into your own repository and edit the four things that are yours: the name, `useWhen`,
the versions and the apps. Then:

```bash
oss run --pack <dir> list
cd <dir> && oss run list      # the same thing
oss bench list                # `bench` is the same command under an older name
```

**`useWhen` is the part a directory could never carry.** A pack states what it is for — the
repository, or files whose presence identifies the project — so the tool can find the right pack
instead of being told which one. A pack that says nothing claims nothing: it will not be picked
automatically, because one pack in a folder of them becoming the answer to every question is how
the wrong pack gets used without anyone choosing it.

**Why a file rather than a script.** The previous format was `pack.sh`, sourced by the engine —
which meant "point oss at this pack" and "run this person's shell script" were the same sentence.
Reading a pack cannot execute anything, every value is quoted on the way to the engine, and a pack
can be written by somebody who does not know bash arrays. `pack.sh` still loads, and wins when both
are present: a directory holding both is a pack mid-migration, and the script is the one that has
been tested.

## 🔌 Extensions

`oss` reads any repository through the GitHub API without a clone, in any language. That boundary
is why it generalises, and it leaves two questions it cannot answer alone: **does this actually
run?** and **have I worked this out before?** An extension answers one of them. A `runner` executes
something real; a `memory` remembers. Both are declared by an `oss-ext.json` at the root of any
repository and called as child processes, so an extension can be written in anything.

```bash
oss ext add ~/apache/log4j2-workout    # register whatever that repo declares
oss ext list                           # what is wired up, and is it still reachable
```

### Typing a dispatcher with no verb

```bash
oss memory        # what can I actually type here?
oss run
```

Lists the verbs read from the manifest of the extension attached **on this machine**, marking any
that write outward, plus the built-in verbs that keep working when the archive is unreachable. It
exits 0 — asking what is available is a question, not a mistake.

This is deliberately not usage text. Usage describes the command's own grammar, which is the same
for everybody; the useful answer is a list that only exists on your machine and cannot be compiled
in. With nothing attached, `oss memory` names the built-in store and `oss run` names `--pack`,
which walks a pack with the engine that ships inside.

```
  devon (memory) — Topic-first archive indexed by DEVONthink
  /Users/you/knowledge-creator

    oss memory file
    oss memory index
    oss memory coverage

    oss memory search   (built in, always available)
```

A verb that the extension does not declare is refused by name, and the refusal lists what it does
declare — so the two paths agree.

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
| `run` / `memory`  | Local          | No                      | Typed bare, list the verbs the attached extension declares |
| `llm <cmd>`       | Local          | Ollama                  | Let a local daemon answer when the local rung cannot |
| `claude <cmd>`    | Online         | Anthropic key           | Let Claude answer when the local rung cannot |
| `gemini <cmd>`    | Online         | Gemini key              | Let Gemini answer when the local rung cannot |
| `codex <cmd>`     | Online         | OpenAI key              | Let OpenAI answer when the local rung cannot |
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
| `triage`          | Offline        | No                      | Full triage audit for one issue              |
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

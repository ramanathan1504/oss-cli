# SETUP.md — installing and connecting OSS-CLI

**Audience:** anyone running OSS-CLI. For internals see
[`DEVELOPING.md`](DEVELOPING.md).

OSS-CLI is a **prompt intelligence workbench**, not a chatbot. It retrieves
from your own history and either answers locally or hands you a fully-assembled
expert prompt to send wherever you like. You choose what leaves your machine.

---

## 1. What you need

|                            | Why                                                              |
|----------------------------|------------------------------------------------------------------|
| Java 17 + Maven            | building and running                                             |
| The embedding model        | search by meaning. Ships with the tool, runs in this process     |
| Ollama *(optional)*        | local answers, triage and PR verdicts — generation, nothing else |
| `gh` CLI or a GitHub token | syncing repositories and your own PR history                     |

**Model choice is yours and is config, not code** — for the two roles that
generate text, because they have different demands:

| Role      | Config key               | What it does                           |
|-----------|--------------------------|----------------------------------------|
| triage    | `ollama.model.triage`    | cheap, high-volume classification      |
| guidance  | `ollama.model.guidance`  | writes the local answer                |

**Embedding is not one of them, and no longer has a key.** all-MiniLM-L6-v2
ships inside the tool and runs in-process, so there is no endpoint to point at
and no name to get wrong. `ollama.model.embedding` is gone: `setup` does not ask
for it, nothing seeds it, and an old database that still carries it is ignored.
It is fetched once, by a command you type:

```bash
oss model --fetch     # about 22 MB, Apache-2.0, stored under ~/.oss-cli/models
```

Nothing fetches it for you — a command that quietly pulls 22 MB the first time
it runs is one people stop trusting. Until it is there, `search`, `duplicates`
and note indexing answer by shared terms instead of by meaning, which works and
needs nothing installed.

The fetch, and the vector index `sync` builds, are the two longest waits here, so
both print a live status line with elapsed time — silence for forty seconds is
indistinguishable from a hang. It is written to **stderr**, never stdout, so
piping or redirecting a command's real output is unaffected. It animates only on
a terminal: a pipe, cron or CI gets one plain line per step and no colour.

| Variable            | Effect                                                          |
|---------------------|-----------------------------------------------------------------|
| `NO_COLOR`          | plain output, as if there were no terminal                      |
| `OSS_NO_QUIPS=1`    | drops only the quip in the dim tail; the status line stays       |

The quip is one rotating line that appears after 8 seconds of waiting, on the
theory that a joke attached to work finishing in 200 ms is noise. Somebody
reading a build log at three in the morning is entitled to a plain line, which is
what the second variable is for.

Retrieval quality still sets the ceiling. No corpus of any size fits in a
context window, so every question is answered from retrieved passages, and a
larger chat model cannot recover text the embedder never saw.

Two practical cautions when picking a guidance model:

- **Reasoning models may return an empty answer.** Some emit their output in a
  separate "thinking" field, leaving the normal response empty — which reads
  here as "no answer" and escalates every time. Disable thinking mode or pick a
  non-reasoning model.
- **Match `ollama.context_limit` to reality.** It is the escalation trigger. Set
  it below what retrieval actually assembles and you escalate needlessly.

**Ollama does not have to be on this machine.** `ollama.url` is read by every
request now. It used to be read only by `doctor` while the client carried
`http://localhost:11434` as a literal, so a configured remote endpoint was
reported reachable while every real request went to localhost anyway.

---

## 2. Install

```bash
mvn clean package
java -jar target/oss-cli-3.2.0.jar setup
```

The wizard stores credentials, model names and paths in SQLite. For a global
command, put a wrapper on your `PATH`:

```bash
#!/usr/bin/env bash
exec java -jar /absolute/path/to/target/oss-cli-3.2.0.jar "$@"
```

Everything lives under `~/.oss-cli/` — database, reports, backups, and the
embedding model once you fetch it.

**Upgrading from a pre-rename build?** Data in `~/.issue-ai/` is moved across
automatically on first run. It moves only when the new location has no database,
so it can never overwrite live data. If you somehow have both, the new one wins
and the old is left untouched — move the unwanted one aside by hand.

---

## 3. Connecting your knowledge

OSS-CLI ingests a directory tree of notes. Set `drive.paths` to a
comma-separated list of folders and run:

```bash
oss sync --all    # public repository backlogs
oss sync --me     # your PR history + the folders in drive.paths
```

`sync --me` is the one command that requires the embedder, because everything it
builds is vectors; it stops and tells you how to fetch it rather than running to
no effect. `sync --all` does not — the issue data is saved either way, and only
the vector index waits. If Ollama is absent, `sync --me` skips the development
stories it writes and indexes everything else as usual.

### Choosing what to include

> `drive.paths` lives in the SQLite config, not in this repository. Nothing you
> pull from git will set it for you, and a folder left out is simply invisible —
> no warning, no error, it just never appears in any answer. It is worth being
> deliberate about it once.

Include **everything a human would read to answer a question**. That is wider
than it first looks. A companion archive typically has two kinds of folder:

- **Harvested notes** — the raw material, usually the bulk of the corpus.
- **Hand-written reference notes** — the ones you wrote yourself rather than
  harvested. These are small, dense, and easy to forget, because they sit in a
  folder that *looks* derived. Leaving them out is the most common mistake: on
  one real setup, 39 hand-written files were the only copy of that knowledge
  anywhere and had never once been retrieved.

Also worth including: distilled summaries, if your archive generates them. A
digest that states *problem → what resolved it* is the most information-dense
thing in the corpus, and a single passage of it can outweigh a whole note.

### What to leave out

- **Personal or non-technical folders.** Keeping them out of technical search is
  a feature, not an oversight.
- **Extracted-code libraries**, if your archive builds them. They are assembled
  *from* notes you have already embedded, so including them puts the same code
  in the index twice, competing with itself for the context budget — and
  duplicate detection will not catch it, because the surrounding prose differs.
  On one real setup this folder alone was 93% of the derived layer.
- **Link indexes and graph files.** A list of links has no prose to match on.

### The transcripts already on this machine

`oss memory harvest --sessions` reads what Claude Code, codex and gemini wrote
to disk — every session each of them kept. That is the half of the record that
never reaches GitHub, and it needs no network and no token, so it works on a
machine that has neither.

It is budgeted rather than concatenated: the newest turns of the newest
sessions, with the path to the full transcript, and whatever the cap dropped is
counted and said out loud. Only the two speakers are kept — tool calls and their
output are both the bulk of a transcript and where the keys and file contents
are. What survives is redacted anyway. On one real machine a first run replaced
two sets of database credentials and a password, in conversations whose
troubleshooting was the part worth keeping.

`oss memory schedule --install` runs the whole harvest daily if you want that,
and nothing installs it for you. `oss memory doctor` says whether it worked —
including whether the job is **loaded** rather than merely installed, which is
the state a dead scheduled job sits in while every file-based check reports
that everything is fine.

### Harvesting whole repositories, and what that changes

A harvester usually collects the threads you were involved in. The companion
`knowledge-creator` (the `devon` memory extension) also takes `--repo owner/name`,
repeatable, which harvests **every** issue and pull request discussion in that
repository — comments, reviews and inline review threads — not only the ones you
appear in. Its daily script reads the list from `KB_HARVEST_REPOS`, unset by
default. It only reads; it never writes to any repository. The Markdown it
produces lands in one of the folders in `drive.paths`, and `oss sync --me`
embeds it like anything else. It is a companion tool, not part of this CLI.

That puts thousands of other people's conversations in the same corpus as your
own conclusions. Both belong there and they are not the same thing, so each note
is recorded as one of two tiers:

| Tier          | What it is                                                    |
|---------------|----------------------------------------------------------------|
| **knowledge** | yours: written by you, decided by you, or a thread you took part in |
| **reference** | collected: a discussion pulled down for context, which you had no part in |

The tier is read from the note's own frontmatter. It is **reference** only when
`my_role: none` and `source: repo-scan` both appear. Anything else is knowledge —
including a thread found by scanning that you turn out to have authored or
reviewed, because how a note was found is not the same as what you did in it. A
note with no frontmatter is knowledge too: those are your own writing and the
resolutions this tool recorded.

Retrieval then prefers knowledge without discarding reference. Reference passages
still compete for the context budget, at a 0.75 discount, and appear in the
assembled prompt labelled `reference` rather than `chat_memory`, so an answer
never implies that a discussion you never read was your own reasoning. Ranked
identically they would fill the budget on weight of numbers alone, because there
is far more of them. Nothing is hidden; search still finds reference material.

**Promotion happens by itself.** The tier is re-read every time a note is
embedded. Comment on an issue you had only collected, and the next harvest
records your name in the note; the next `oss sync --me` re-reads that and the
note becomes knowledge. There is no step to remember, because a step you have to
remember is one that does not happen.

### If a folder is regenerated on every run

Derived files often carry a `generated: <timestamp>` header. That makes the
content differ on every run even when nothing meaningful changed, so the cache
sees them as modified and re-embeds them daily. For small folders that is
seconds and not worth avoiding. For a large one it is minutes of identical work
each day — a reason to exclude it, or to normalise the volatile line out of the
comparison before including it.

Every file is embedded and stored with the model that produced the vector.
Unchanged files are skipped by content comparison, so re-running is cheap.

**Notes are embedded as passages, not whole.** Embedding models read only a few
hundred tokens; a longer document is silently truncated, so a single vector per
note would describe its opening and leave the rest unsearchable. Each note is
therefore split into overlapping ~1500-character passages, embedded separately.

Budget for the first run accordingly — it is proportional to total text, not to
the number of files. As a real data point: 514 notes totalling 33 M characters
became 28,165 passages, took about 40 minutes, and grew the database from
81 MB to 321 MB. Later runs only touch changed files.

Adding the reference folders described above cost 63 notes and 645 passages —
about 16 MB and 11 minutes. Small prose files are cheap; it is total characters
that matter, which is why the extracted-code library is the expensive one to
include and the hand-written notes are nearly free.

### Point it at clean text, not raw exports

> **OSS-CLI does not redact.** Whatever you feed it is stored verbatim.

Raw AI-assistant exports routinely contain API keys, tokens and database URLs
that you pasted into a conversation months ago and forgot. If you ingest those
directly, they land in the database in cleartext.

If you use a harvester that scrubs secrets on the way in (the companion
`claude-cli` project does this), point `drive.paths` at **its output**, not at
the original export. One scrub on the way in beats none at all downstream.

If you ingest raw sources anyway, know that you are doing it, and check what
went in:

```sql
SELECT file_name FROM personal_chat_memory WHERE content LIKE '%ghp_%';
```

---

## 4. One embedder, and why every vector still carries its name

Vectors from different models are not comparable, and the failure is silent:
comparing across them yields plausible-looking nonsense rather than an error.

Every vector is therefore stored with the model and dimension that produced it,
and every read filters on that name. With one built-in embedder there is nothing
left to choose, so what the filter now protects you from is history. Vectors
written by the earlier setup — where an Ollama daemon served the same weights
under the name `all-minilm` — are recorded under that name and ignored. They are
also 384 dimensions, so nothing would have caught the mix by shape, and they are
quantised and pooled differently enough not to be comparable.

Nothing to clean up. Ignored vectors are re-embedded in-process on the next
sync, and rows written before provenance tracking existed have no recorded model,
are treated as unknown, and get re-embedded once.

---

See [OFFLINE.md](OFFLINE.md) for exactly which commands need a network and which
do not, with a walkthrough you can follow and then unplug.

## 5. Connecting a model that writes

The embedder above is the only model that ships with the tool. Anything that
*writes* — a verdict, a triage audit, an answer — is something you connect, and
both kinds are optional. With neither, `prompt` still assembles the expert
prompt and hands it to you.

### Locally, with Ollama

Ollama is used for generation and nothing else. It is never asked to embed, so
`search`, `duplicates`, `pick` and note indexing do not involve it at all.

```bash
ollama serve
ollama pull llama3.2:3b       # then name it during 'oss setup'
```

It does not have to be on this machine. `setup` asks where it is, so a laptop
can borrow a desktop's GPU:

```
Current Ollama address: [ http://localhost:11434 ]
Enter new Ollama address (e.g. http://gpu-box.local:11434) or press Enter to keep current:
```

That address is `ollama.url`, and until now it was seeded, displayed by
`doctor`, and read by nothing — every request went to localhost whatever it
said, so pointing it at another host produced a clean bill of health and a tool
that could not reach a model.

#### The three models, and which commands use which

`setup` asks for each and stores them; they can be the same model, and usually are
to start with.

| config key | used by | reasonable default |
|------------|-----------------------------------------|--------------------|
| `ollama.model.guidance` | `oss llm review`, `guide`, `chat`, `sync --me` narratives | `qwen2.5-coder:7b` |
| `ollama.model.triage` | `oss llm analyze` | the same |
| `ollama.model.embedding` | nothing by default — the built-in embedder is used | *(leave empty)* |

Everything Ollama-backed needs the engine named in front of it: `oss llm review 42`,
not `oss review 42`. A daemon that happens to be running is not a request.

#### Every Ollama setting

| config key | what it is | default |
|-----------------------|-------------------------------------|--------------------------|
| `ollama.url` | where the daemon is | `http://localhost:11434` |
| `ollama.model.guidance` | the model that writes | asked during `setup` |
| `ollama.model.triage` | the model that scores a backlog | asked during `setup` |
| `ollama.timeout_seconds` | how long one generation may take | built-in, shown by `doctor` |

There is **no environment variable for the address** — not `OLLAMA_HOST`, which is
Ollama's own and is not read here. It is `oss setup`, or the config key directly.

#### Checking it

```bash
oss doctor
```

reports whether the daemon answers, whether the named model is actually pulled, and
whether it **fits in memory right now**. That last one matters: Ollama does not
refuse a model larger than your free memory — it loads it, the machine swaps, and
everything stops responding for minutes in a way that cannot be read as an error or
cancelled. At most half your free memory is offered to a model.

```bash
ollama list       # what is pulled
ollama ps         # what is loaded right now
```

#### If it will not answer

- **`connection refused`** — `ollama serve` is not running, or `ollama.url` points
  somewhere else. `oss doctor` prints the address it tried.
- **model not found** — `ollama pull <name>`; the name in `setup` must match
  `ollama list` exactly, tag included.
- **it hangs** — the model is probably swapping. `oss doctor` says so in advance;
  pull a smaller one.
- **nothing generated and no error** — check you typed `oss llm <command>`. Without
  a prefix, no model is asked at all, and the layer report at the end says so.

### In the cloud, with a key

Keys are read from the environment first, then the macOS Keychain. They are
never written to the database, which is why `restore` can put a backup back
without touching them.

| Provider  | Environment variable          | Keychain item        |
|-----------|-------------------------------|----------------------|
| Anthropic | `ANTHROPIC_API_KEY`           | `anthropic_api_key`  |
| OpenAI    | `OPENAI_API_KEY`              | `openai_api_key`     |
| Google    | `GEMINI_API_KEY`              | `gemini_api_key`     |
| GitHub    | `GITHUB_TOKEN` or `GH_TOKEN`  | `github_token`       |

### A cloud engine without a key at all

If you already have a provider's own command-line tool installed and signed in, `--cli` reaches
the engine through it and no API key is involved:

```bash
oss claude --cli review 4249     # Claude Code CLI, on your subscription
oss codex --cli review 4249      # codex exec
oss gemini --cli review 4249     # gemini CLI
```

`oss doctor` lists which of the three it can find. This is a separate account from the API key
above — a subscription does not add API credit, and API credit does not power the CLI.

**The order is: environment variable, then macOS Keychain, then an error naming the
key.** Nothing else is consulted, and no key is ever written to the database — `oss
setup` records which *model* to call for each provider, never the credential.

### Putting a key in the environment

Fine for one session, and the thing to reach for when you want *this* run to differ:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export OPENAI_API_KEY=sk-...
export GEMINI_API_KEY=...
export GITHUB_TOKEN=ghp_...        # GH_TOKEN works too — it is what `gh` sets
```

To keep it, put those in `~/.zshrc` (or `~/.bashrc`). A shell profile is readable by
anything you run, which is the trade: convenient, and not a secret store.

### Putting a key in the Keychain (macOS)

Survives new shells, is not in a dotfile, and is what `oss setup` tells you to run:

```bash
security add-generic-password -a "$USER" -s anthropic_api_key -w "sk-ant-..." -U
security add-generic-password -a "$USER" -s openai_api_key    -w "sk-..."     -U
security add-generic-password -a "$USER" -s gemini_api_key    -w "..."        -U
security add-generic-password -a "$USER" -s github_token      -w "ghp_..."    -U
```

`-s` is the service name and **must match the table above exactly** — a key stored
under any other name is a key nothing reads. `-U` updates an existing entry instead
of failing. Omit `-w "..."` to be prompted instead, so the key is not in your shell
history:

```bash
security add-generic-password -a "$USER" -s anthropic_api_key -U -w
```

Read one back, to check what is actually stored:

```bash
security find-generic-password -s anthropic_api_key -w
```

Wrapping punctuation is stripped on the way in: `<sk-ant-…>`, `"sk-ant-…"` and
`'sk-ant-…'` all work, because documentation writes keys in angle brackets and
shells add quotes. Only the wrapping — anything inside the key is left alone.

### Checking it worked

```bash
oss setup       # reports which keys it can see, and where it found each
oss doctor      # checks everything else at the same time
```

### Sending requests somewhere else

For a gateway, a proxy or a compatible endpoint. Environment first, then stored
config, then the provider's own API:

| provider | environment | config key |
|----------|----------------------|-------------------|
| Anthropic | `ANTHROPIC_BASE_URL` | `claude.base_url` |
| OpenAI | `OPENAI_BASE_URL` | `openai.base_url` |
| Google | `GEMINI_BASE_URL` | `gemini.base_url` |

An override in force is printed once per run, because a request going somewhere
other than the provider is worth one line — the alternative is diagnosing a
redirected client as a broken key.

### Which model each provider uses

`oss setup` asks, and stores the answer as `claude.model`, `openai.model` and
`gemini.model`. The key says *who* may answer; this says *which* of their models.

---

Nothing is ever sent because a key exists. The engine is a word you type, in front
of the command:

```bash
oss claude review <n>            # or gemini, or codex
oss claude prompt <n>
```

And naming one is permission, not an instruction: the ask starts on the local rung
and goes out only when that rung falls short, printing the reason.

---

## 6. Daily use

```bash
oss critical                # offline ranking by community signal
oss search "<query>"        # semantic search over the backlog
oss prompt <number>         # local answer, or an expert prompt
oss inspect <number>        # what was retrieved, and will it escalate
oss prompt <n> --copy       # prompt to clipboard
oss prompt <n> --out f.md   # prompt to a file
oss prompt <n> --force-prompt   # skip the local model entirely
oss chat <number>           # talk it through; every turn is saved as it is said
oss chat --continue         # carry on with the most recent conversation
oss history                 # browse saved conversations, enter resumes one
oss backup                  # timestamped archive (keeps last 5)
oss restore <archive.zip>   # restore, preserving local API keys
```

### Conversations, and several terminals

`oss chat` writes every turn to SQLite the moment it is said, so ctrl-c or a
closed terminal is a pause rather than a loss. `oss history` lists what you have
saved — arrow keys, a preview of where each one got to, enter to resume — and
`oss history --search "the flaky test one"` finds one by meaning using the
built-in embedder. Without a terminal, the same list is numbered instead, and
`oss chat --resume <id>` needs no list at all.

Running several terminals at once is expected and supported:

| What you do | What happens |
| --- | --- |
| `sync` in one window, `chat` in another | Both work. The database runs in WAL mode, so readers are never blocked by the writer, and writers wait their turn instead of failing. |
| Resume the same conversation in two terminals | The second one notices and offers to **fork** it — a new conversation carrying the same history — rather than interleaving two people's thinking into one transcript. |
| A terminal is killed mid-conversation | Its turns are already saved. The session releases itself after two minutes; there is no lock file to clean up. |
| Ask about it later, from any terminal | Once a conversation is ended with `exit`, the transcript is filed and embedded, and from then on `search`, `prompt` and `review` can all retrieve it. |

The last row is the one that matters most: while a conversation is open it is
private to itself, and it joins the shared corpus when it ends. That is what
makes the corpus compound instead of filling up with half-finished thoughts.

### How `prompt` decides

```
retrieve context (token-budgeted, similarity-ranked)
        │
        ├─ fits the limit and the local model is confident  ──►  answer shown
        └─ too large, low confidence, or model unavailable  ──►  expert prompt
                                                                 (copy, save,
                                                                  or --send-*)
```

Escalation is the feature. When the local model cannot answer well, you get a
structured prompt containing the assembled context rather than a confident
guess. Use `inspect` to see the decision before committing to it.

---

## 7. Where your data lives, and how not to destroy it

Everything sits under `~/.oss-cli` — database, reports, backups, logs. Set
`OSS_CLI_HOME` and all of it moves as a set:

```bash
OSS_CLI_HOME=~/.oss-cli-dev oss doctor
```

**This matters if you build from source.** A jar you build and the one Homebrew
installed are the same artifact from the same pom, so nothing tells them apart at
runtime — and schema migrations run automatically and only go forwards. Run a
build carrying a schema change against your real database and there is no way
back. So run development builds relocated:

```bash
OSS_CLI_HOME=~/.oss-cli-dev java -jar target/oss-cli-<version>.jar sync --all
```

A relocated run starts from an empty database deliberately. Nothing is copied
across: a sandbox holding a full duplicate of your real data defeats the point of
having one.

To see which build you are running and which store it will write to:

```console
$ oss --version
oss 1.9.0
build     2026-08-13T12:38:36Z
running   /opt/homebrew/Cellar/oss/1.9.0/libexec/lib/oss.jar
data      /Users/you/.oss-cli
```

A jar loaded out of a `target/` directory is additionally marked
`(development build)`. `doctor` reports the same directory as its first check and
*warns* rather than passing silently when `OSS_CLI_HOME` is in effect — a
healthy-looking but empty install is otherwise indistinguishable from data loss.

---

### If oss says the database was written by a newer version

```
This database was written by a newer oss than this one.
  database:    schema 15
  this build:  schema 14  (oss 1.11.0)
```

Two builds of `oss` are pointed at one store and the newer one has already
migrated it. Migrations only run forwards, so the older build cannot understand
it — and it refuses rather than reading tables whose meaning may have changed
and then writing rows in the shape it believes in. **Nothing has been read or
changed** when you see this.

Three ways out:

```bash
brew upgrade oss                       # the usual one
OSS_CLI_HOME=~/other-store oss ...     # work somewhere else meanwhile
oss doctor                             # still works, and reports both versions
```

`doctor` stays available deliberately: taking away the command that explains the
problem would be a poor way to report it.

## 8. Troubleshooting

| Symptom                             | Likely cause                                                                                              |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Everything missing / database empty | `OSS_CLI_HOME` is exported in that shell. `oss doctor` names the store it is using on its first line. |
| Database looks empty after upgrade  | Both old and new data directories exist.                                                                  |
| Everything escalates                | Guidance model not installed, or `context_limit` too low.                                                 |
| Local answers empty                 | Reasoning model returning output in a separate field.                                                     |
| Search matches only shared words    | The embedding model is not fetched — `oss model --fetch`.                                             |
| Fewer similar results than expected | Vectors from the old Ollama embedder are ignored. Re-sync to rebuild them in-process.                     |
| Long notes never retrieved          | Passages not built yet — run `sync --me` once after upgrading.                                            |
| Answers lean on threads you never read | Those notes are ranked as reference already; if one you took part in is still treated that way, re-harvest it and run `sync --me` so the tier is re-read. |
| Spinner frames in a log or CI output | Something is presenting a terminal where you expected none — set `NO_COLOR`.                              |

---

## 9. Backups contain everything

`backup` archives the database — which includes the full text of everything you
ingested. If a secret ever entered, it is in the backups too. Rotating the
credential is the only real remedy; deleting rows is housekeeping.

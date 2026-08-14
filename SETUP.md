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
java -jar target/oss-cli-1.11.3.jar setup
```

The wizard stores credentials, model names and paths in SQLite. For a global
command, put a wrapper on your `PATH`:

```bash
#!/usr/bin/env bash
exec java -jar /absolute/path/to/target/oss-cli-1.11.3.jar "$@"
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

```bash
export ANTHROPIC_API_KEY=sk-...

# or so it survives a new shell:
security add-generic-password -s anthropic_api_key -a "$USER" -w
```

`setup` records which *model* to call for each provider. The key itself stays
outside the database.

Nothing is ever sent because a key exists. Escalation is a flag you type:

```bash
oss prompt <n> --send-claude     # or --send-openai, --send-gemini
oss review <n> --escalate        # uses whichever key is configured
```

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

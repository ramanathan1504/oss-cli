# SETUP.md — installing and connecting OSS-CLI

**Audience:** anyone running OSS-CLI. For internals see
[`DEVELOPING.md`](DEVELOPING.md).

OSS-CLI is a **prompt intelligence workbench**, not a chatbot. It retrieves
from your own history and either answers locally or hands you a fully-assembled
expert prompt to send wherever you like. You choose what leaves your machine.

---

## 1. What you need

|                            | Why                                               |
|----------------------------|---------------------------------------------------|
| Java 17 + Maven            | building and running                              |
| A model server             | embeddings, and local answers. Ollama by default. |
| `gh` CLI or a GitHub token | syncing repositories and your own PR history      |

**Model choice is yours and is config, not code.** Three roles are configured
separately, because they have different demands:

| Role      | Config key               | What it does                           |
|-----------|--------------------------|----------------------------------------|
| embedding | `ollama.model.embedding` | turns notes and questions into vectors |
| triage    | `ollama.model.triage`    | cheap, high-volume classification      |
| guidance  | `ollama.model.guidance`  | writes the local answer                |

The **embedding model matters most**. No corpus of any size fits in a context
window, so every question is answered from retrieved passages — retrieval
quality sets the ceiling, and a larger chat model cannot recover text the
embedder never saw.

Two practical cautions when picking a guidance model:

- **Reasoning models may return an empty answer.** Some emit their output in a
  separate "thinking" field, leaving the normal response empty — which reads
  here as "no answer" and escalates every time. Disable thinking mode or pick a
  non-reasoning model.
- **Match `ollama.context_limit` to reality.** It is the escalation trigger. Set
  it below what retrieval actually assembles and you escalate needlessly.

---

## 2. Install

```bash
mvn clean package
java -jar target/oss-cli-1.8.2.jar setup
```

The wizard stores credentials, model names and paths in SQLite. For a global
command, put a wrapper on your `PATH`:

```bash
#!/usr/bin/env bash
exec java -jar /absolute/path/to/target/oss-cli-1.8.2.jar "$@"
```

Everything lives under `~/.oss-cli/` — database, reports, backups.

**Upgrading from a pre-rename build?** Data in `~/.issue-ai/` is moved across
automatically on first run. It moves only when the new location has no database,
so it can never overwrite live data. If you somehow have both, the new one wins
and the old is left untouched — move the unwanted one aside by hand.

---

## 3. Connecting your knowledge

OSS-CLI ingests a directory tree of notes. Set `drive.paths` to a
comma-separated list of folders and run:

```bash
oss-cli sync --all    # public repository backlogs
oss-cli sync --me     # your PR history + the folders in drive.paths
```

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

## 4. Changing the embedding model

Vectors from different models are not comparable, and the failure is silent:
different models emit different dimensions, and comparing across them yields
plausible-looking nonsense rather than an error.

Every vector is therefore stored with its model and dimension. Change the model
and the next sync detects it, re-embeds, and says so:

```
Embedding model changed (all-minilm -> nomic-embed-text) — re-embedding 'note.md'...
```

Nothing to clean up. Rows written before provenance tracking existed have no
recorded model, are treated as unknown, and get re-embedded once.

---

## 5. Daily use

```bash
oss-cli critical                # offline ranking by community signal
oss-cli search "<query>"        # semantic search over the backlog
oss-cli prompt <number>         # local answer, or an expert prompt
oss-cli inspect <number>        # what was retrieved, and will it escalate
oss-cli prompt <n> --copy       # prompt to clipboard
oss-cli prompt <n> --out f.md   # prompt to a file
oss-cli prompt <n> --force-prompt   # skip the local model entirely
oss-cli backup                  # timestamped archive (keeps last 5)
oss-cli restore <archive.zip>   # restore, preserving local API keys
```

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

## 6. Where your data lives, and how not to destroy it

Everything sits under `~/.oss-cli` — database, reports, backups, logs. Set
`OSS_CLI_HOME` and all of it moves as a set:

```bash
OSS_CLI_HOME=~/.oss-cli-dev oss-cli doctor
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
$ oss-cli --version
oss-cli 1.3.0
build     2026-08-01T19:08:47Z
running   /opt/homebrew/Cellar/oss-cli/1.3.0/libexec/oss-cli.jar
data      /Users/you/.oss-cli
```

A jar loaded out of a `target/` directory is additionally marked
`(development build)`. `doctor` reports the same directory as its first check and
*warns* rather than passing silently when `OSS_CLI_HOME` is in effect — a
healthy-looking but empty install is otherwise indistinguishable from data loss.

---

## 7. Troubleshooting

| Symptom                             | Likely cause                                                                                              |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Everything missing / database empty | `OSS_CLI_HOME` is exported in that shell. `oss-cli doctor` names the store it is using on its first line. |
| Database looks empty after upgrade  | Both old and new data directories exist.                                                                  |
| Everything escalates                | Guidance model not installed, or `context_limit` too low.                                                 |
| Local answers empty                 | Reasoning model returning output in a separate field.                                                     |
| Similarity results nonsensical      | Mixed embedding models. Re-sync to rebuild.                                                               |
| Long notes never retrieved          | Passages not built yet — run `sync --me` once after upgrading.                                            |

---

## 8. Backups contain everything

`backup` archives the database — which includes the full text of everything you
ingested. If a secret ever entered, it is in the backups too. Rotating the
credential is the only real remedy; deleting rows is housekeeping.

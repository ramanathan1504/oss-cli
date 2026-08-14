# Working offline

**Six commands reach the network. Twenty-five never do.**

And of those six, only one is part of ordinary use: `sync`. The rest fetch one
specific thing you asked for by number. (`model --fetch` downloads once in the
life of the install; `run`, `bench`, `memory` and `kb` hand off to whatever
extension you attached, so they are as offline as it is.)

This page is the long version: what actually goes over the network, what a single
sync buys you, and why searching by *meaning* keeps working with the wifi off.

---

## 1. What needs the network

Six, and you can tell which by what they are for — they are the ones that fetch
something from GitHub. Plus one download that happens once in the life of the
install.

| Needs the network | Why |
| --- | --- |
| `sync` | Fetches issues, pull requests and comments. This is the one that matters. |
| `issue <n>` | Pulls one issue you have not synced yet. |
| `pr <n>` | Pulls one pull request you have not synced yet. |
| `hub` | Reads across the repositories you follow. |
| `followup` | Checks whether a branch has moved since you reviewed it. |
| `review <pr>` | Asks GitHub for the pull request's head commit. If it has not changed, the answer comes from cache — but the check itself is a call. |
| `model --fetch` | Downloads the embedding model. **Once, ever.** 22 MB. |

The other twenty-five open no socket at all:

```
search    prompt    inspect    history    chat      critical
duplicates          triage     guide      profile   onboard
report    trend     analyze    backlog    pick      hidden-critical
prs       serve     backup     restore    doctor    alias
ext       setup
```

Two of those deserve a word:

- **`doctor`** *pings* GitHub and Ollama to tell you whether they are reachable.
  That is the check, not a dependency — it reports "not reachable" and carries on.
- **`serve`** starts a web interface on `http://localhost:1504`. Local means local:
  it binds to your own machine and serves the corpus already on your disk.

**The dispatchers are as offline as what you attached.** `run` and `bench` hand
everything after the verb to a bench extension; `memory` and `kb` to a knowledge
extension. Whether they touch a network is that extension's business, not
`oss`'s — `oss ext list` shows what is attached and where it lives.

---

## 2. What one sync gets you

`oss sync --all` does two things, and only the first needs a network:

```
GitHub  ──►  SQLite  ──►  vectors
   ↑           ↑             ↑
 network    your disk    your machine
```

1. **Fetch.** Issues, pull requests, titles, bodies, comments and labels come
   down and go into `~/.oss-cli/data/issue_intelligence.db`.
2. **Embed.** Each one is turned into a 384-number vector by a model running
   **inside the `oss` process**. Nothing is uploaded. Nothing is asked of an API.

After step 2 the network has no further part to play. The corpus is a file. Every
question you ask afterwards is answered from it.

Syncing again later is a *delta* — it asks only for what changed since last time,
so the second sync is small and fast. You choose when.

---

## 3. Why the built-in model is what makes offline real

This is the part that surprises people, so it is worth being exact.

The embedder is **all-MiniLM-L6-v2, in ONNX form, running in this JVM.**

- **It is not Ollama.** Ollama is a separate program you may attach for *writing*
  text. Nothing that indexes or searches goes anywhere near it.
- **It is not an API.** No key, no account, no request.
- **It is not a server.** There is no daemon, nothing listening on a port, nothing
  to start before you can work. It loads, does arithmetic, and stops when the
  command stops.
- **It is 22 MB, fetched once**, with `oss model --fetch`, into
  `~/.oss-cli/models`.

Which is why this works on a plane:

```bash
oss search "rollover compression"
```

and finds an issue titled *"log rotation with zstd"* — no shared words, same
subject. Matching by meaning needs arithmetic, and arithmetic does not need a
network.

**Without the model** search still works, by shared terms instead of meaning.
That is the floor of this tool, not a broken state — for a fresh install it is the
whole product, and it is honest about which mode it is in.

---

## 4. Walkthrough: sync once, then unplug

Follow this literally. It takes about five minutes, most of it the first sync.

### While you have a connection

```bash
# 1. A token. The only thing oss requires of you.
export GITHUB_TOKEN=$(gh auth token)

# 2. Fetch the embedding model — 22 MB, once, ever.
oss model --fetch

# 3. Register a repository you follow.
oss sync --add owner/name

# 4. Pull it down and build the index.
oss sync --all
```

Check what you got:

```bash
oss doctor
```

You are looking for these three lines:

```
[  ok  ] embedding model — all-MiniLM-L6-v2-onnx (built in, in-process)
[  ok  ] embeddings — 1330 vectors, all from all-MiniLM-L6-v2-onnx
[  ok  ] backlog — 1330 issues
```

If `embeddings` says `0 vectors`, step 2 did not happen — run `oss model --fetch`
and then `oss sync --all` again. Anything already on disk gets vectors on the
next sync.

### Now turn the wifi off

Genuinely. Everything below works:

```bash
# Search the whole corpus by meaning
oss search "rollover compression"

# Rank what is waiting, by community signal
oss critical

# See exactly what would be retrieved for an issue, and whether it escalates
oss inspect 812

# Assemble a complete expert prompt from your local context
oss prompt 812

# Find issues that are the same problem described twice
oss duplicates

# What changed, and what is waiting on you
oss report
```

`oss prompt 812` is the one to try. With no model attached to *write* an answer it
does the honest thing: it assembles every relevant piece of context it has — the
issue, the related issues, your notes, past resolutions — into a prompt you can
paste anywhere. The retrieval that built it was entirely local.

### When you are back on a connection

```bash
oss sync --all      # delta: only what changed
```

Nothing is re-downloaded and nothing is re-embedded that has not changed.

---

## 5. Every layer, and what its absence costs

Nothing here is mandatory. Each row is something you can add later, and the
"without it" column is what actually happens — not an error.

| Layer | With it | Without it |
| --- | --- | --- |
| **GitHub token** | `sync` can fetch | Everything already synced still works. `sync` says what is missing. |
| **Embedding model** (22 MB) | Search and rank by meaning | Search by shared terms. Says which mode it used. |
| **Ollama** (or a cloud key) | Local answers, review verdicts, chat | `prompt` assembles the expert prompt instead of answering. `chat` needs it; nothing else does. |
| **Your note folders** | Your own past work ranks in every answer | Answers come from the repository alone. |

The refusals are loud and specific. A command that cannot do the better thing
says so and does the lesser thing, rather than printing a warning and then
claiming success.

---

## 6. What offline does *not* mean

Being straight about the edges:

- **`chat` needs a model that writes**, which means Ollama installed locally. That
  is offline in the sense of "no internet", not in the sense of "nothing
  installed".
- **Cloud escalation obviously needs a connection.** Pressing `y` in `chat`, or
  `prompt --send-gemini`, sends your assembled prompt to somebody's API. That is
  the one moment anything leaves your machine, and it never happens unasked.
- **Your corpus is as fresh as your last sync.** Offline does not mean current. An
  issue closed this morning still looks open if you synced yesterday.
- **`review` wants to check the head commit.** With a cached evaluation for that
  exact commit it answers from cache, but confirming the commit is unchanged is a
  call.

---

## 7. Where it all lives

```
~/.oss-cli/
├── data/issue_intelligence.db   the corpus, the vectors, your config
├── models/                      the 22 MB embedder
├── backups/                     oss backup writes here
└── reports/
```

One directory. `OSS_CLI_HOME` moves all of it as a set. `oss backup` puts it in a
zip you can carry to another machine, and `oss restore` puts it back without
touching that machine's API keys.

---

**See also:** [SETUP.md](SETUP.md) for connecting models and note folders,
[COMMANDS.md](COMMANDS.md) for every command in detail.

# CLI Command Reference (`COMMANDS.md`)

This guide outlines all subcommands available in the `oss-cli` CLI platform.

## Global Options
*   `-r`, `--repo` : Target GitHub repository to analyze (Default: `owner/reponame`).

---

## 🛠 Configuration & Setup

### `setup`
Interactive wizard to configure your secure environment, API keys, Google Drive paths, and AI models.
```bash
oss-cli setup
```
*   **Security:** Checks active environment variables and the macOS Keychain for `GITHUB_TOKEN`, `GEMINI_API_KEY`, `OPENAI_API_KEY`, and `ANTHROPIC_API_KEY`.
*   **Storage:** Saves all configurations to the local SQLite `system_config` table.

---

## 🔄 Data Synchronization

### `sync`
Fetches issues, pull requests, author profiles, and ecosystem dependencies from GitHub into SQLite.
*   `-a`, `--all` : Sequentially synchronize all active repositories in your database registry.
*   `--add <repo>` : Register a new repository to your local database watchlist.
*   `--remove <repo>` : Remove a repository from the database watchlist.
*   `--me` : **Personal Sync.** Fetches your 1-year PR history, creates your Developer Expertise Vector, and recursively crawls your Google Drive (automatically parsing ChatGPT/Claude `.json` exports and `.md` files) to index your conversational memory.

```bash
oss-cli sync --all
oss-cli sync --me
oss-cli sync --add apache/kafka
oss-cli sync --remove apache/camel
```

---

## 🔍 Prompt Intelligence (New)

### `prompt`
Core new command. Runs the full **Retrieve → Ollama → Adaptive Response** pipeline.

**Ollama answers first.** The command retrieves all relevant local context (issues, PRs, stack traces, chat logs, notes, similar fixes), estimates the token count, and passes everything to Ollama.

- **If context fits within Ollama's limit AND confidence is high** → Ollama answers directly from the local database. No external AI needed.
- **If context exceeds Ollama's limit OR confidence is too low** → The platform builds a high-quality expert prompt from all gathered context and presents it for copy/send to your AI of choice.

Retrieves from all local sources:
- Issue body, labels, and comments
- Related issues and linked PRs
- Similar past fixes (vector similarity)
- Extracted stack traces
- Previous AI conversation transcripts
- Personal notes from Google Drive
- Cross-project JIRA dependencies

```bash
oss-cli prompt 1666                   # Ollama answers, or builds expert prompt if too complex
oss-cli prompt 1666 --copy            # Copy generated prompt to clipboard (macOS pbcopy)
oss-cli prompt 1666 --out prompt.md   # Save prompt to a Markdown file
oss-cli prompt 1666 --send-gemini     # Auto-send to Gemini when escalation occurs
oss-cli prompt 1666 --send-openai     # Auto-send to OpenAI when escalation occurs
oss-cli prompt 1666 --send-claude     # Auto-send to Anthropic when escalation occurs
oss-cli prompt 1666 --force-prompt    # Skip Ollama — always build and display the expert prompt
```

*   **Thresholds (configurable via `setup`):**
    *   `ollama.context_limit` — Max tokens Ollama handles locally (default: `4096`)
    *   `ollama.confidence_threshold` — Min confidence to trust local answer (default: `0.70`)
*   **Escalation reasons logged to SQLite:** `context_overflow`, `low_confidence`, `forced`

### `inspect`
Shows the raw context retrieval result for an issue — what documents were found, their sources, relevance scores, and token counts. Critically, it also shows **whether Ollama would answer locally or escalate to a prompt**, so you can understand the decision before running `prompt`.

```bash
oss-cli inspect 1666
oss-cli inspect 1666 -r apache/kafka
```

*   **Output includes:**
    *   Each retrieved document (source type, reference, relevance score, token count)
    *   Documents dropped due to token budget limits (marked `excluded`)
    *   Total token estimate vs. `ollama.context_limit`
    *   **Decision preview:** `✔ Ollama will answer locally` OR `⚠ Context too large — prompt will be built`
    *   Escalation reason if applicable (`context_overflow` / `low_confidence`)

---

## 🤖 The Personal Copilot

### `chat`
Opens a live, interactive REPL (Read-Eval-Print Loop) to act as your pair-programmer.
*   **Context Aware:** Loads your personal SQLite memory (past PR stories, AI Studio chats, and ChatGPT/Claude JSON exports) automatically.
*   **Omni-Cloud Escalation:** Evaluates locally via Ollama. Type `y` to seamlessly escalate a prompt to Google Gemini, OpenAI GPT-4o, or Anthropic Claude for expert cloud resolution.
*   **Real-Time Memory:** Upon typing `exit`, the chat transcript is automatically saved to your Google Drive as a Markdown file and instantly embedded back into SQLite memory.

```bash
oss-cli chat 1666            # Escalates to Gemini (Default)
oss-cli chat 1666 --openai   # Escalates to OpenAI GPT-4o
oss-cli chat 1666 --claude   # Escalates to Anthropic Claude 3.5
```

### `guide`
Generates a structured, step-by-step resolution blueprint for a specific issue using local RAG (Retrieval-Augmented Generation).
*   `--gemini` : Bypass the local model and route immediately to the Gemini API.
```bash
oss-cli guide 1666
```

### `triage`
Executes an automated triage audit on a specific issue.
*   Outputs V1 severity, local AI severity, semantic duplicate overlaps, JIRA-bridge dependencies, and immediate recommended actions.
```bash
oss-cli triage 4088
```

---

## 📊 Analytics & Reporting

### `critical`
Performs a fast, fully **offline** severity ranking of all locally-synced issues using the V1 keyword-score engine. No Ollama or cloud API required. Ideal for an instant triage snapshot.

```bash
oss-cli critical
oss-cli critical -r apache/kafka
```
*   **Output:** Summary count (Critical / High / Medium / Low) followed by ranked issue lists sorted by score descending.
*   **Options:** `-r`, `--repo` — Target repository (defaults to `default.repository` if not provided).

### `search`
Performs an offline semantic vector lookup on your backlog.
*   `-g`, `--global` : Run the search across all synced repositories simultaneously.
```bash
oss-cli search "deadlock in network appender" --global
```

### `report`
Compiles SQLite data into a unified Weekly Health Report in Markdown format.
*   `--me` : Generates a highly personalized roadmap tailored to your Developer Expertise Vector, including Regression Guard alerts and your specific stale PRs.
```bash
oss-cli report --me
oss-cli report -r apache/kafka
```

### `trend`
Tracks project health metrics over time.
*   `-s`, `--save` : Save today's metrics as a new historical snapshot.
```bash
oss-cli trend --save
```

### `analyze`
Performs batch AI Severity Analysis on all open issues using local Ollama.
```bash
oss-cli analyze
```

### `duplicates`
Identifies duplicate issue clusters using local vector embeddings (Cosine Similarity).
```bash
oss-cli duplicates -t 0.85
```

### `hidden-critical`
Cross-references raw metadata against AI evaluations to detect underestimated security threats.
```bash
oss-cli hidden-critical
```

---

## 💾 Backup & Restore

### `backup`
Export your entire AI memory and database into a portable archive, with automatic rotation that keeps the last 5 backups.
```bash
oss-cli backup
```
*   **Archive format:** `sa_brain_backup_yyyyMMdd_HHmmss.zip` (contains `.db` and `.txt` files from `data/`).
*   **Destination:** Uses `backup.path` from `system_config`, or defaults to the global backups folder.
*   **Auto-rotation:** Automatically deletes the oldest archive once more than 5 backups exist.

### `restore`
Import and restore your AI memory and database from a previously created backup archive.
```bash
oss-cli restore /path/to/sa_brain_backup_20260627_104000.zip
```
*   **Parameters:** `<backup-file>` — Path to the `.zip` backup archive.
*   **Safe restore:** Buffers all local `system_config` values (API keys, model paths) before extraction and re-applies them afterward — your credentials are never overwritten.
*   **Security:** Protected against Zip Slip path traversal attacks.

---

## Quick Reference

| Command           | Mode           | AI Required             | Description                                  |
|-------------------|----------------|-------------------------|----------------------------------------------|
| `setup`           | Interactive    | No                      | Configure API keys, paths, models            |
| `sync --all`      | Online         | No                      | Sync all registered repositories             |
| `sync --me`       | Online         | No                      | Sync personal PR profile + Drive logs        |
| `critical`        | Offline        | No                      | Fast keyword-score severity ranking          |
| `analyze`         | Offline        | Ollama                  | AI batch severity scoring                    |
| `duplicates`      | Offline        | Ollama                  | Vector-based duplicate detection             |
| `prompt`          | Offline/Online | Ollama + Optional cloud | **Generate expert prompt from full context** |
| `inspect`         | Offline        | No                      | Show retrieved context for an issue          |
| `search`          | Offline        | No                      | Semantic vector search                       |
| `triage`          | Offline        | Ollama                  | Full triage audit for one issue              |
| `guide`           | Offline        | Ollama                  | Step-by-step resolution blueprint            |
| `chat`            | Online         | Ollama + Cloud          | Live interactive REPL                        |
| `report`          | Offline        | No                      | Weekly health report                         |
| `trend`           | Offline        | No                      | Historical metric snapshots                  |
| `hidden-critical` | Offline        | No                      | Underestimated security threat detection     |
| `backup`          | Offline        | No                      | Export AI memory to zip archive              |
| `restore`         | Offline        | No                      | Restore AI memory from zip archive           |

#!/bin/zsh

# Exit immediately if any command fails
set -e

# ──────────────────────────────────────────────────────────────
# ENVIRONMENT BOOTSTRAP
# ──────────────────────────────────────────────────────────────

# 1. Export standard macOS and Homebrew paths
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

# 2. Dynamically locate JAVA_HOME on macOS
if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME=$(/usr/libexec/java_home)
fi

# 3. Retrieve GITHUB_TOKEN securely from macOS Keychain
if [ -z "$GITHUB_TOKEN" ]; then
  KEYCHAIN_TOKEN="$(security find-generic-password -s github_token -w 2>/dev/null || true)"
  if [ -n "$KEYCHAIN_TOKEN" ]; then
    export GITHUB_TOKEN="$KEYCHAIN_TOKEN"
  fi
fi

if [ -z "$GITHUB_TOKEN" ]; then
  echo "Error: GITHUB_TOKEN is not set and was not found in macOS Keychain." >&2
  exit 1
fi

# Navigate to project directory
cd "$HOME/apache/issue-analyzer"

# ──────────────────────────────────────────────────────────────
# DYNAMIC CONFIGURATION FROM SQLITE
# ──────────────────────────────────────────────────────────────
TARGET_REPO=$(sqlite3 data/issue_intelligence.db "SELECT value FROM system_config WHERE key = 'default.repository';" 2>/dev/null || echo "")
if [ -z "$TARGET_REPO" ]; then
    TARGET_REPO="apache/logging-log4j2"
fi

MY_USERNAME=$(sqlite3 data/issue_intelligence.db "SELECT value FROM system_config WHERE key = 'github.username';" 2>/dev/null || echo "")

echo "=================================================="
echo "Target Repository : $TARGET_REPO"
echo "Started At        : $(date '+%Y-%m-%d %H:%M:%S')"
echo "=================================================="

# ──────────────────────────────────────────────────────────────
# PHASE 1: BUILD
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 1: Compiling Clean Developer Build (Maven)"
echo "=================================================="
mvn clean package -q

# ──────────────────────────────────────────────────────────────
# PHASE 2: DATA SYNCHRONIZATION
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 2: Syncing Repository Backlog (SQLite)"
echo "=================================================="
oss-cli sync --all

echo ""
echo "=================================================="
echo "Phase 2b: Syncing Personal Profile & Google Drive"
echo "=================================================="
oss-cli sync --me

# ──────────────────────────────────────────────────────────────
# PHASE 3: FAST OFFLINE RANKING (no Ollama required)
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 3: Offline Severity Ranking ($TARGET_REPO)"
echo "=================================================="
oss-cli critical -r "$TARGET_REPO"

# ──────────────────────────────────────────────────────────────
# PHASE 4: AI ANALYSIS (Ollama)
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 4: AI Severity Assessment ($TARGET_REPO)"
echo "=================================================="
oss-cli analyze -r "$TARGET_REPO"

echo ""
echo "=================================================="
echo "Phase 4b: Rebuilding Semantic Vector Index ($TARGET_REPO)"
echo "=================================================="
oss-cli duplicates -t 0.85 -r "$TARGET_REPO"

# ──────────────────────────────────────────────────────────────
# PHASE 5: PROMPT INTELLIGENCE — Pre-generate prompts for top issues
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 5: Prompt Intelligence — Adaptive Pre-generation for Top Critical Issues"
echo "=================================================="

# For the most critical issues, the context is always large enough to exceed Ollama's
# limit, so we use --force-prompt to pre-generate the expert prompt for offline review.
# Simpler issues will get direct Ollama answers at query time without this step.
TOP_CRITICAL_ISSUES=$(sqlite3 data/issue_intelligence.db "
  SELECT a.issue_number
  FROM ai_analysis a
  JOIN issues i ON a.issue_number = i.number AND a.repository = i.repository
  WHERE a.repository = '$TARGET_REPO' AND a.severity = 'Critical'
  ORDER BY a.confidence DESC
  LIMIT 3;
" 2>/dev/null || echo "")

if [ -n "$TOP_CRITICAL_ISSUES" ]; then
    mkdir -p data/prompts
    for ISSUE_NUM in $TOP_CRITICAL_ISSUES; do
        echo "  ↳ Pre-generating expert prompt for critical issue #$ISSUE_NUM ..."
        PROMPT_FILE="data/prompts/prompt_${TARGET_REPO//\//_}_${ISSUE_NUM}.md"
        # --force-prompt: skip Ollama, build full expert prompt (context always too large for criticals)
        oss-cli prompt "$ISSUE_NUM" -r "$TARGET_REPO" --force-prompt --out "$PROMPT_FILE" 2>/dev/null \
            && echo "    ✔ Expert prompt saved → $PROMPT_FILE" \
            || echo "    ⚠ Skipped #$ISSUE_NUM (prompt generation unavailable)"
    done
else
    echo "  ↳ No critical issues found — skipping prompt pre-generation."
fi

# ──────────────────────────────────────────────────────────────
# PHASE 6: REPORTING
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 6: Generating Weekly Health Reports ($TARGET_REPO)"
echo "=================================================="
oss-cli report -r "$TARGET_REPO"
oss-cli report --me -r "$TARGET_REPO"
oss-cli trend --save -r "$TARGET_REPO"

# ──────────────────────────────────────────────────────────────
# PHASE 7: ALERT ENGINE (macOS Native Notifications)
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 7: macOS Alert & Notification Engine"
echo "=================================================="

# Send a macOS notification with sound
function send_mac_alert() {
    local icon="$1"
    local title="$2"
    local subtitle="$3"
    local message="$4"
    osascript -e "tell application \"System Events\" to display notification \"$message\" with title \"$icon $title\" subtitle \"$subtitle\" sound name \"Glass\""
}

# Only notify if new items appeared since the last run
function check_and_notify() {
    local state_file="$1"
    local current_data="$2"
    local icon="$3"
    local title="$4"
    local subtitle="$5"

    OLD_DATA=""
    [ -f "$state_file" ] && OLD_DATA=$(cat "$state_file")

    NEW_ITEMS=""
    for item in $current_data; do
        if [[ ! " $OLD_DATA " =~ " $item " ]]; then
            NEW_ITEMS="$NEW_ITEMS #$item"
        fi
    done

    echo "$current_data" > "$state_file"

    if [ -n "$NEW_ITEMS" ]; then
        TRIMMED=$(echo "$NEW_ITEMS" | xargs)
        send_mac_alert "$icon" "$title" "$subtitle" "$TRIMMED"
        echo "  ↳ [$title] Alert sent for: $TRIMMED"
    else
        echo "  ↳ [$title] No new updates since last run."
    fi
}

# Alert 1: Hidden Critical issues (AI-Critical but not labeled security)
CURRENT_HIDDEN=$(sqlite3 data/issue_intelligence.db "
  SELECT i.number FROM issues i
  JOIN ai_analysis a ON i.number = a.issue_number AND i.repository = a.repository
  WHERE i.repository = '$TARGET_REPO' AND a.severity = 'Critical'
  AND i.number NOT IN (
    SELECT issue_number FROM labels
    WHERE label_name LIKE '%security%' AND repository = '$TARGET_REPO'
  )
  ORDER BY i.number ASC;" 2>/dev/null || echo "")

check_and_notify "data/state_hidden.txt" "$CURRENT_HIDDEN" "🚨" "$TARGET_REPO Security Alert" "New Hidden Criticals Found!"

# Alert 2: Brand new Critical issues (reported in last 24 hours)
NEW_CRITICALS=$(sqlite3 data/issue_intelligence.db "
  SELECT i.number FROM issues i
  JOIN ai_analysis a ON i.number = a.issue_number AND i.repository = a.repository
  WHERE i.repository = '$TARGET_REPO' AND a.severity = 'Critical'
  AND julianday('now') - julianday(i.created_at) <= 1
  ORDER BY i.number ASC;" 2>/dev/null || echo "")

check_and_notify "data/state_new_criticals.txt" "$NEW_CRITICALS" "🛡️" "$TARGET_REPO Triage Alert" "New Critical Bugs Reported Today!"

# Alert 3: My personal stale PRs (no activity > 30 days)
if [ -n "$MY_USERNAME" ]; then
    MY_STALE_PRS=$(sqlite3 data/issue_intelligence.db "
      SELECT number FROM issues
      WHERE repository = '$TARGET_REPO' AND is_pull_request = 1
      AND author = '$MY_USERNAME'
      AND julianday('now') - julianday(updated_at) > 30
      ORDER BY number ASC;" 2>/dev/null || echo "")

    check_and_notify "data/state_my_stale.txt" "$MY_STALE_PRS" "👤" "Personal Developer Alert" "Your PRs are going stale!"
else
    echo "  ↳ [Personal PR Alert] github.username not configured — skipping."
fi

# Alert 4: New pre-generated prompts are ready
PROMPT_COUNT=$(ls data/prompts/*.md 2>/dev/null | wc -l | xargs)
if [ "$PROMPT_COUNT" -gt "0" ]; then
    send_mac_alert "🧠" "Prompt Intelligence" "Expert prompts ready" "$PROMPT_COUNT critical issue prompts saved to data/prompts/"
    echo "  ↳ [Prompt Intelligence] $PROMPT_COUNT expert prompt(s) ready in data/prompts/"
fi

# ──────────────────────────────────────────────────────────────
# PHASE 8: AUTOMATED VAULT BACKUP
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "Phase 8: Automated Vault Backup & Preservation"
echo "=================================================="
oss-cli backup

# ──────────────────────────────────────────────────────────────
# DONE
# ──────────────────────────────────────────────────────────────
echo ""
echo "=================================================="
echo "✔ Master pipeline completed successfully!"
echo "  Finished At: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=================================================="

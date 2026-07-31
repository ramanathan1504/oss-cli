# 🍏 Advanced macOS Automation & Notifications

The `oss-cli` platform includes a powerful, zero-overhead background automation engine for macOS. By combining `launchd` with local SQLite queries, your Mac will silently update your issue databases in the background and trigger **Native UI Desktop Notifications** (with custom icons, subtitles, and system sounds) whenever high-priority events occur.

This guide explains how to set up the daemon and how to customize the alerts for your personal developer workflow.

---

## 1. The Master Runner Script (`osscli-master.sh`)

This script acts as the orchestrator. It compiles your latest code, runs the synchronization across your repositories, executes the AI analysis, and finally queries the database to trigger desktop alerts.

Save the following script as `osscli-master.sh` in the root of your project directory:

```bash
#!/bin/zsh

# Exit immediately if any compilation or execution command fails
set -e

# 1. Export standard macOS paths (Launchd runs in a minimal environment)
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

# 2. Dynamically locate JAVA_HOME
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

# Navigate to the project directory
cd "$HOME/apache/issue-analyzer"

echo "=================================================="
echo "Phase 1: Compiling Clean Developer Build (Maven)"
echo "=================================================="
mvn clean package

echo ""
echo "=================================================="
echo "Phase 2: Updating Knowledge Base"
echo "=================================================="
oss-cli sync --all
oss-cli sync --me
oss-cli analyze -r apache/logging-log4j2
oss-cli duplicates -t 0.85 -r apache/logging-log4j2
oss-cli report -r apache/logging-log4j2
oss-cli report --me -r apache/logging-log4j2
oss-cli trend --save -r apache/logging-log4j2

echo ""
echo "=================================================="
echo "Phase 3: macOS Alert & Notification Engine"
echo "=================================================="

# Helper function for native macOS notifications
function send_mac_alert() {
    local icon="$1"
    local title="$2"
    local subtitle="$3"
    local message="$4"
    # Triggers a native alert with the "Glass" system chime
    osascript -e "display notification \"$message\" with title \"$icon $title\" subtitle \"$subtitle\" sound name \"Glass\""
}

# Helper function to track state (only notifies if the issue is NEW since last run)
function check_and_notify() {
    local state_file="$1"
    local current_data="$2"
    local icon="$3"
    local title="$4"
    local subtitle="$5"

    if [ -f "$state_file" ]; then
        OLD_DATA=$(cat "$state_file")
    else
        OLD_DATA=""
    fi

    NEW_ITEMS=""
    for item in $current_data; do
        if [[ ! " $OLD_DATA " =~ " $item " ]]; then
            NEW_ITEMS="$NEW_ITEMS #$item"
        fi
    done

    echo "$current_data" > "$state_file"

    if [ ! -z "$NEW_ITEMS" ]; then
        TRIMMED_ITEMS=$(echo "$NEW_ITEMS" | xargs)
        send_mac_alert "$icon" "$title" "$subtitle" "$TRIMMED_ITEMS"
        echo "  ↳ [$title] Alert sent for: $TRIMMED_ITEMS"
    else
        echo "  ↳ [$title] No new updates."
    fi
}

# --- CUSTOM DEVELOPER QUERIES ---
# Developers: Customize these SQLite queries to track whatever matters to you!

# QUERY 1: Hidden Criticals (Security Risks)
CURRENT_HIDDEN=$(sqlite3 data/issue_intelligence.db "
SELECT i.number FROM issues i JOIN ai_analysis a ON i.number = a.issue_number AND i.repository = a.repository
WHERE i.repository = 'apache/logging-log4j2' AND a.severity = 'Critical' 
AND i.number NOT IN (SELECT issue_number FROM labels WHERE label_name LIKE '%security%') ORDER BY i.number ASC;")

check_and_notify "data/state_hidden.txt" "$CURRENT_HIDDEN" "🚨" "Log4j Security Alert" "New Hidden Criticals Found!"

# QUERY 2: Brand New Critical Issues (Reported in the last 24 Hours)
NEW_CRITICALS=$(sqlite3 data/issue_intelligence.db "
SELECT i.number FROM issues i JOIN ai_analysis a ON i.number = a.issue_number AND i.repository = a.repository
WHERE i.repository = 'apache/logging-log4j2' AND a.severity = 'Critical' 
AND julianday('now') - julianday(i.created_at) <= 1 ORDER BY i.number ASC;")

check_and_notify "data/state_new_criticals.txt" "$NEW_CRITICALS" "🛡️" "Log4j Triage Alert" "New Critical Bugs Reported Today!"

# QUERY 3: My Personal Stale PRs (Inactivity > 30 Days)
MY_USERNAME=$(sqlite3 data/issue_intelligence.db "SELECT value FROM system_config WHERE key = 'github.username';")
MY_STALE_PRS=$(sqlite3 data/issue_intelligence.db "
SELECT number FROM issues 
WHERE repository = 'apache/logging-log4j2' AND is_pull_request = 1 
AND author = '$MY_USERNAME' AND julianday('now') - julianday(updated_at) > 30 ORDER BY number ASC;")

check_and_notify "data/state_my_stale.txt" "$MY_STALE_PRS" "👤" "Personal Developer Alert" "Your PRs are going stale!"

echo "Master pipeline execution completed successfully!"
```

Don't forget to make the script executable:
```bash
chmod +x osscli-master.sh
```

---

## 2. Registering the macOS `launchd` Service

Apple's `launchd` is superior to `cron` because it handles Mac sleep/wake cycles gracefully. If your laptop is asleep when the timer triggers, it will run instantly upon waking.

Create a `.plist` file at `~/Library/LaunchAgents/org.apache.osscli.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>org.apache.osscli</string>

    <key>ProgramArguments</key>
    <array>
        <!-- Ensure this path points to your absolute script location! -->
        <string>/Users/YOUR_USERNAME/apache/issue-analyzer/osscli-master.sh</string>
    </array>

    <!-- Run every 1 hour (3600 seconds) -->
    <key>StartInterval</key>
    <integer>3600</integer>

    <!-- Run once immediately as soon as you turn on or wake up your Mac -->
    <key>RunAtLoad</key>
    <true/>

    <!-- Output Logs for debugging -->
    <key>StandardOutPath</key>
    <string>/Users/YOUR_USERNAME/apache/issue-analyzer/osscli_run.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/YOUR_USERNAME/apache/issue-analyzer/osscli_run.log</string>
</dict>
</plist>
```
*(Note: Replace `YOUR_USERNAME` with your actual macOS short username in the 3 path strings).*

---

## 3. Service Management Commands

Use these native macOS terminal commands to control your automation daemon:

*   **Start the Background Automation:**
    ```bash
    launchctl load ~/Library/LaunchAgents/org.apache.osscli.plist
    ```
*   **Stop / Pause the Automation:**
    ```bash
    launchctl unload ~/Library/LaunchAgents/org.apache.osscli.plist
    ```
*   **Force an Immediate Background Run:**
    ```bash
    launchctl start org.apache.osscli
    ```
*   **Monitor the Live Execution Logs:**
    ```bash
    tail -f ~/apache/issue-analyzer/osscli_run.log
    ```

---

## 🛠 Customizing Your Alerts (Developer Guide)

Because the notification engine uses pure SQLite commands, you can easily tweak `osscli-master.sh` to track whatever matters to your specific workflow.

**Example 1: Track a different repository**
Change `WHERE repository = 'apache/logging-log4j2'` to `WHERE repository = 'apache/kafka'`.

**Example 2: Alert me when a new PR mentions a specific keyword**
```bash
KAFKA_DEADLOCKS=$(sqlite3 data/issue_intelligence.db "
SELECT number FROM issues WHERE repository = 'apache/kafka' 
AND is_pull_request = 1 AND body LIKE '%deadlock%';")

check_and_notify "data/state_deadlocks.txt" "$KAFKA_DEADLOCKS" "⚙️" "Kafka Copilot" "New PR mentions a deadlock!"
```
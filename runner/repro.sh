#!/usr/bin/env bash
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# repro.sh — turn a bench scenario into a standalone, attachable reproduction.
#
#   ./bench repro 4143 --scenario exceptions --log4j 2.24.1 --log4j 2.26.0
#   ./bench repro 4133 --pr --scenario exceptions --config xml/layout-jsontemplate
#
# Produces repros/issue-<id>/ containing a self-contained Maven project that
# depends on nothing in this workspace, runs it against every requested Log4j
# version to build a verification matrix, writes a README with the results, and
# zips the project for attaching to the GitHub issue.
#
# The bench itself is never mutated: generation happens in a scratch directory
# and only repros/<id>/ is written. Nothing needs reverting afterwards.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m▸\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[32m✓\033[0m %s\n' "$*" >&2; }

# ── Arguments ───────────────────────────────────────────────────────────────
ID=""
KIND="issue"
SCENARIO="exceptions"
CONFIG="xml/baseline-console.xml"
TITLE=""
VERSIONS=()

[[ $# -gt 0 ]] || die "usage: ./bench repro <issue-or-pr-number> [--pr] [--scenario NAME] [--config NAME] [--log4j VERSION]..."
ID="$1"; shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pr)       KIND="pr";        shift ;;
    --scenario) SCENARIO="$2";    shift 2 ;;
    --config)   CONFIG="$2";      shift 2 ;;
    --title)    TITLE="$2";       shift 2 ;;
    --log4j)    VERSIONS+=("$2"); shift 2 ;;
    *) die "unexpected argument '$1'" ;;
  esac
done

# Default matrix: the versions that matter for a regression hunt — a known-good
# baseline, the current releases, and the development snapshot.
if [[ ${#VERSIONS[@]} -eq 0 ]]; then
  VERSIONS=(2.24.1 2.25.4 2.26.1 2.27.0-SNAPSHOT)
fi

OUT="$ROOT/repros/${KIND}-${ID}"
PROJECT="$OUT/log4j-${KIND}-${ID}-repro"
ARTIFACT="log4j-${KIND}-${ID}-repro"
[[ -n "$TITLE" ]] || TITLE="Log4j ${KIND} #${ID} reproduction"

# ── Locate the scenario source to embed ─────────────────────────────────────
SCENARIO_DIR="$ROOT/apps/core-java/src/main/java/org/apache/logging/bench/scenario"
scenario_class() {
  case "$1" in
    exceptions)   echo "ExceptionScenario" ;;
    messages)     echo "MessageScenario" ;;
    lookups)      echo "LookupScenario" ;;
    context)      echo "ThreadContextScenario" ;;
    rollover)     echo "RolloverScenario" ;;
    programmatic) echo "ProgrammaticScenario" ;;
    *) die "unknown scenario '$1' (see: ./bench run core-java --list)" ;;
  esac
}
CLASS="$(scenario_class "$SCENARIO")"
[[ -f "$SCENARIO_DIR/$CLASS.java" ]] || die "scenario source not found: $SCENARIO_DIR/$CLASS.java"

CONFIG_FILE=""
for candidate in "$CONFIG" "$ROOT/$CONFIG" "$ROOT/configs/$CONFIG" \
                 "$ROOT/configs/$CONFIG.xml" "$ROOT/configs/xml/$CONFIG.xml"; do
  [[ -f "$candidate" ]] && { CONFIG_FILE="$candidate"; break; }
done
[[ -n "$CONFIG_FILE" ]] || die "no such config: $CONFIG"

# ── Derive extra dependencies from what the config actually uses ────────────
# A repro that ships a JsonTemplateLayout config but only api+core will silently
# fall back to the default configuration and reproduce nothing. Scan the config
# and add whatever it needs.
dep() {
  EXTRA_DEPS+="    <dependency>
      <groupId>$1</groupId>
      <artifactId>$2</artifactId>
      <version>$3</version>
    </dependency>
"
  EXTRA_NOTES+="  - \`$2\` — required by the shipped configuration"$'\n'
}

EXTRA_DEPS=""
EXTRA_NOTES=""
grep -q 'JsonTemplateLayout'                  "$CONFIG_FILE" && dep org.apache.logging.log4j log4j-layout-template-json '${log4j.version}'
grep -qE '<(Json|Xml|Yaml)Layout'             "$CONFIG_FILE" && dep com.fasterxml.jackson.core jackson-databind 2.18.2
grep -q '<XmlLayout'                          "$CONFIG_FILE" && dep com.fasterxml.jackson.dataformat jackson-dataformat-xml 2.18.2
grep -q '<YamlLayout'                         "$CONFIG_FILE" && dep com.fasterxml.jackson.dataformat jackson-dataformat-yaml 2.18.2
grep -q 'co.elastic\|EcsLayout xmlns\|<EcsLayout' "$CONFIG_FILE" && dep co.elastic.logging log4j2-ecs-layout 1.8.0
grep -q '<Kafka'                              "$CONFIG_FILE" && dep org.apache.kafka kafka-clients 3.9.0
grep -q '<JDBC\|PoolingDriver\|DataSource'    "$CONFIG_FILE" && { dep org.apache.logging.log4j log4j-jdbc-dbcp2 '${log4j.version}'; dep com.h2database h2 2.3.232; }
grep -q '<MongoDb'                            "$CONFIG_FILE" && dep org.apache.logging.log4j log4j-mongodb '${log4j.version}'
grep -q '<SMTP'                               "$CONFIG_FILE" && dep com.sun.mail jakarta.mail 1.6.7
grep -q '<JeroMQ'                             "$CONFIG_FILE" && dep org.zeromq jeromq 0.6.0
grep -qE '<(Script|ScriptFilter|ScriptRef)'   "$CONFIG_FILE" && dep org.codehaus.groovy groovy-jsr223 3.0.23
grep -qE 'AsyncLogger|AsyncRoot'              "$CONFIG_FILE" && dep com.lmax disruptor 4.0.0
grep -qE '\.(zst|xz|bz2)"'                    "$CONFIG_FILE" && dep org.apache.commons commons-compress 1.27.1
# commons-compress dispatches to a codec backend, and only bzip2 and deflate are
# pure Java inside it. zstd and xz need a separate artifact at RUNTIME, and it is
# the same trap as commons-csv below, one layer further out: the appender builds
# fine, the run exits 0, and the compression fails on the rollover thread with
# NoClassDefFoundError: com/github/luben/zstd/ZstdOutputStream. RollingFileManager
# reports that as a WARN from an async action, so the "no StatusLogger error"
# check below still says PASS while nothing was ever compressed. Versions match
# apps/core-java/pom.xml so the zip behaves like the bench it came from.
grep -qE '\.zst"'                             "$CONFIG_FILE" && dep com.github.luben zstd-jni 1.5.6-9
grep -qE '\.xz"'                              "$CONFIG_FILE" && dep org.tukaani xz 1.10
# CsvLogEventLayout and CsvParameterLayout live in log4j-core on the 2.x line but
# need commons-csv at RUNTIME. Without it the plugin cannot be created at all -
# NoClassDefFoundError: org/apache/commons/csv/QuoteMode - so the appender is
# silently absent and a reproduction of a CSV bug reproduces nothing. That is
# exactly the failure this derivation exists to prevent, and it was missing.
grep -qE '<Csv(LogEvent|Parameter)Layout'     "$CONFIG_FILE" && dep org.apache.commons commons-csv 1.12.0
grep -q '<Cassandra'                          "$CONFIG_FILE" && dep org.apache.logging.log4j log4j-cassandra '${log4j.version}'
grep -q '<CouchDB'                            "$CONFIG_FILE" && dep org.apache.logging.log4j log4j-couchdb '${log4j.version}'
grep -q '<JPA'                                "$CONFIG_FILE" && dep org.apache.logging.log4j log4j-jpa '${log4j.version}'
grep -qE '<Rfc5424Layout|<SyslogLayout'       "$CONFIG_FILE" && true   # log4j-core, no extra artifact

# ── Scaffold the standalone project ─────────────────────────────────────────
info "scaffolding $PROJECT"
rm -rf "$OUT"
SRC="$PROJECT/src/main/java/org/apache/logging/repro"
mkdir -p "$SRC" "$PROJECT/src/main/resources"

cp "$SCENARIO_DIR/$CLASS.java" "$SRC/$CLASS.java"
cp "$ROOT/apps/core-java/src/main/java/org/apache/logging/bench/Scenario.java" "$SRC/Scenario.java"
cp "$CONFIG_FILE" "$PROJECT/src/main/resources/log4j2.xml"

# Re-point the copied sources at the standalone package
for f in "$SRC/$CLASS.java" "$SRC/Scenario.java"; do
  perl -i -pe 's{^package .*;}{package org.apache.logging.repro;};
               s{^import org\.apache\.logging\.bench\.Scenario;\n}{};' "$f"
done

cat > "$SRC/Main.java" <<JAVA
package org.apache.logging.repro;

/**
 * Standalone reproduction for ${KIND} #${ID}.
 *
 * <p>Self-contained: depends only on Log4j itself. Run with
 * {@code mvn -Dlog4j.version=<version> compile exec:java}.
 */
public final class Main {

    public static void main(final String[] args) throws Exception {
        System.out.println("log4j-api  " + versionOf("org.apache.logging.log4j.LogManager"));
        System.out.println("log4j-core " + versionOf("org.apache.logging.log4j.core.LoggerContext"));
        System.out.println("java       " + System.getProperty("java.version"));
        System.out.println();

        new ${CLASS}().run();

        System.out.println();
        System.out.println("Completed without error.");
    }

    private static String versionOf(final String className) {
        try {
            final Package pkg = Class.forName(className).getPackage();
            final String v = pkg == null ? null : pkg.getImplementationVersion();
            return v == null ? "<unknown>" : v;
        } catch (final ClassNotFoundException e) {
            return "<absent>";
        }
    }

    private Main() {}
}
JAVA

cat > "$PROJECT/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.apache.logging.repro</groupId>
  <artifactId>${ARTIFACT}</artifactId>
  <version>1.0-SNAPSHOT</version>
  <name>${TITLE}</name>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- Override on the command line: -Dlog4j.version=2.26.1 -->
    <log4j.version>${VERSIONS[${#VERSIONS[@]}-1]}</log4j.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-api</artifactId>
      <version>\${log4j.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-core</artifactId>
      <version>\${log4j.version}</version>
    </dependency>
${EXTRA_DEPS}  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.5.0</version>
        <configuration>
          <mainClass>org.apache.logging.repro.Main</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
POM

cat > "$PROJECT/run.sh" <<'RUN'
#!/usr/bin/env bash
# Run this reproduction against one Log4j version.
#   ./run.sh              # the version pinned in pom.xml
#   ./run.sh 2.26.1       # any other version
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${1:-}"
if [[ -n "$VERSION" ]]; then
  mvn -q -Dlog4j.version="$VERSION" compile exec:java
else
  mvn -q compile exec:java
fi
RUN
chmod +x "$PROJECT/run.sh"

# ── Verification matrix ─────────────────────────────────────────────────────
info "verifying against ${#VERSIONS[@]} version(s)"
mkdir -p "$OUT/output"
MATRIX=""
for version in "${VERSIONS[@]}"; do
  log="$OUT/output/${version}.log"
  printf '  %-18s ' "$version" >&2

  exit_ok=true
  ( cd "$PROJECT" && mvn -q -Dlog4j.version="$version" compile exec:java ) >"$log" 2>&1 || exit_ok=false

  # A non-zero exit is not the only failure mode, and usually not the interesting
  # one: Log4j catches appender exceptions and reports them via StatusLogger, so
  # the JVM still exits 0 while the bug has plainly occurred. Scan for that too.
  status_error="$(grep -m1 -E 'ERROR (An exception occurred|Unable to write|Error processing)' "$log" 2>/dev/null || true)"

  if [[ "$exit_ok" == true && -z "$status_error" ]]; then
    printf '\033[32mPASS\033[0m\n' >&2
    MATRIX+="| \`${version}\` | ✅ PASS | clean run, no StatusLogger error |"$'\n'
  else
    if [[ -n "$status_error" ]]; then
      # || true is not optional: under `set -o pipefail` a grep that matches
      # nothing returns 1 and kills the script - which happened exactly when a
      # reproduction reproduced something the regex did not anticipate, i.e. the
      # case this tool exists for. The README and zip were never written.
      detail="$(printf '%s' "$status_error" | grep -oE '(java|org)\.[A-Za-z0-9_.$]*(Exception|Error)(: [^\"]{0,70})?' | head -1 || true)"
      [[ -n "$detail" ]] || detail="StatusLogger reported an appender error"
      kind="swallowed by StatusLogger"
    else
      detail="$(grep -m1 -oE '(java|org)\.[A-Za-z0-9_.$]*(Exception|Error)(: [^\"]{0,70})?' "$log" 2>/dev/null || true)"
      [[ -n "$detail" ]] || detail="non-zero exit"
      kind="process failed"
    fi
    printf '\033[31mFAIL\033[0m  %s\n' "$detail" >&2
    MATRIX+="| \`${version}\` | ❌ FAIL | \`${detail}\` <br/><sub>${kind}</sub> |"$'\n'
  fi
done

# ── README ──────────────────────────────────────────────────────────────────
LINK_BASE="https://github.com/apache/logging-log4j2"
[[ "$KIND" == "pr" ]] && LINK="$LINK_BASE/pull/$ID" || LINK="$LINK_BASE/issues/$ID"

cat > "$OUT/README.md" <<MD
# ${TITLE}

$LINK

Generated by \`./bench repro ${ID}\` from the Log4j feature bench.

## Reproduction

\`\`\`bash
unzip ${ARTIFACT}.zip
cd ${ARTIFACT}
./run.sh 2.26.1     # or any version
\`\`\`

The project is standalone — it depends only on \`log4j-api\` and \`log4j-core\`,
with no parent POM and no reference to the bench it came from.

## Verification matrix

| Log4j version | Result | Detail |
|---|:---:|---|
${MATRIX}
Full output per version is under \`output/\`.

## What it does

- Scenario: \`${SCENARIO}\` (\`${CLASS}.java\`)
- Configuration: \`${CONFIG}\` → shipped as \`src/main/resources/log4j2.xml\`
- Entry point: \`org.apache.logging.repro.Main\`

Dependencies beyond \`log4j-api\` / \`log4j-core\`:

${EXTRA_NOTES:-  - none}

## Environment

- Generated: $(date -u '+%Y-%m-%d %H:%M:%SZ')
- JDK: $(java -version 2>&1 | head -1)
- Maven: $(mvn -v 2>&1 | head -1)
MD

# ── Package ─────────────────────────────────────────────────────────────────
( cd "$OUT" && rm -rf "$ARTIFACT/target" && zip -qr "${ARTIFACT}.zip" "$ARTIFACT" )

ok "reproduction ready"
printf '\n  %s\n' "$OUT"
printf '    README.md            verification matrix, ready to paste into the issue\n'
printf '    %s.zip%s attach this\n' "$ARTIFACT" ""
printf '    output/              per-version run logs\n\n'
printf '  Bench workspace unchanged — nothing to revert.\n\n'

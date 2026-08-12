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
# pr-review.sh — mechanical review harness for a Log4j pull request.
#
#   ./bench review 4218
#   ./bench review 4153 --full           force a full reactor build
#   ./bench review 4218 --no-build       metadata + diff only
#   ./bench review 4218 --offline --keep fast, and keep the worktree
#   ./bench review 4301 --3x             against the 3.x clone
#
# Gathers the evidence a reviewer needs into one directory, and runs the two
# checks that a human cannot do by reading: whether the PR's tests actually fail
# without its fix, and whether the tests dirty the source tree.
#
# It does NOT judge the code. It produces facts you then read. Judging is
# Reference/reviewing-a-contributor-pull-request in the knowledge base §2, and no script gets there.
#
# Everything happens in a throwaway git worktree, so your clone stays on its
# branch and ~/.m2 is never overwritten by a PR build.
#
# Merged from knowledge-creator's log4j-pr-review.sh (steps 1-9), which owned
# this job first. Steps 10-11 are the part it never had.

set -uo pipefail

# ---------------------------------------------------------------- defaults ---
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="apache/logging-log4j2"
CLONE="${BENCH_LOG4J_CLONE:-$HOME/apache/logging-log4j2}"
PR=""
FORCE_FULL=0
NO_BUILD=0
KEEP=0
OFFLINE=""
OUT=""
JDK="${BENCH_JDK:-17}"
FILE_IT=0
KB="${BENCH_KB_DIR:-$HOME/own repo/knowledge-creator}"

# Changes under these paths force a full reactor build: they can break any
# module, so a scoped build proves nothing.
FULL_TRIGGERS="log4j-plugin-processor log4j-plugins log4j-kit log4j-parent log4j-bom pom.xml .mvn"

die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
say()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*" >&2; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*" >&2; }
warn() { printf '    \033[33m!\033[0m %s\n' "$*" >&2; }
bad()  { printf '    \033[31m✗\033[0m %s\n' "$*" >&2; }

usage() {
  awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "${BASH_SOURCE[0]}"
  cat <<'EOF'

Options:
  --repo OWNER/NAME   default: apache/logging-log4j2
  --full              force a full reactor build
  --no-build          metadata + diff only, skip everything that compiles
  --offline           pass -o to maven (fast; needs a warm ~/.m2)
  --keep              keep the worktree for manual poking
  --jdk N             default 17; Log4j 2.x enforces [17,18)
  --file              file ~/.oss-cli/reviews/<PR>-*.md into the knowledge base
                      (knowledge-creator's pr-review-file.py; $BENCH_KB_DIR)
  --out DIR           default: .bench/reviews/<PR>
  --3x                use the 3.x clone (~/apache/log4j-main)
  --clone PATH        local clone to work in
  -h, --help          this
EOF
}

# ------------------------------------------------------------------- args ---
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)     REPO="$2"; shift 2 ;;
    --full)     FORCE_FULL=1; shift ;;
    --no-build) NO_BUILD=1; shift ;;
    --offline)  OFFLINE="-o"; shift ;;
    --keep)     KEEP=1; shift ;;
    --jdk)      JDK="$2"; shift 2 ;;
    --out)      OUT="$2"; shift 2 ;;
    --file)     FILE_IT=1; shift ;;
    --3x)       CLONE="${BENCH_LOG4J_CLONE:-$HOME/apache/log4j-main}"; shift ;;
    --clone)    CLONE="$2"; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    -*)         die "unknown option: $1" ;;
    *)          PR="$1"; shift ;;
  esac
done

[ -n "$PR" ] || { usage; exit 2; }
[[ "$PR" =~ ^[0-9]+$ ]] || die "'$PR' is not a pull request number"

# --------------------------------------------------------------- preflight ---
command -v gh      >/dev/null || die "gh CLI not found"
command -v git     >/dev/null || die "git not found"
command -v python3 >/dev/null || die "python3 not found (used to parse GraphQL)"
gh auth status >/dev/null 2>&1 || die "gh not authenticated — run: gh auth login"
[ -d "$CLONE/.git" ] || die "not a git clone: $CLONE (set --clone or \$BENCH_LOG4J_CLONE)"
[ -x "$CLONE/mvnw" ] || die "no mvnw in $CLONE"

if [ "$(uname -s)" = "Darwin" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v "$JDK" 2>/dev/null)" \
    || die "no JDK $JDK installed (/usr/libexec/java_home -V lists them)"
  export JAVA_HOME
fi
# java_home answers an unknown version with the newest JDK and exit 0, so ask the
# JVM what it actually is. Log4j's enforcer pins [17,18) and fails in log4j-bom
# before compiling anything — which would otherwise read as a legitimate RED.
GOT="$("${JAVA_HOME:-/usr}/bin/java" -XshowSettings:properties -version 2>&1 \
        | sed -n 's/.*java\.specification\.version = //p')"
[ "$GOT" = "$JDK" ] || die "asked for JDK $JDK, got $GOT (${JAVA_HOME:-inherited})"

# MUST be absolute: $WT is handed to `git -C "$CLONE" worktree add`, which
# resolves relative paths against the repo, not against $PWD. A relative --out
# would silently create the worktree inside the clone.
OUT="${OUT:-$ROOT/.bench/reviews/$PR}"
mkdir -p "$OUT" || die "cannot create $OUT"
OUT="$(cd "$OUT" && pwd)" || die "cannot resolve --out path"

BRANCH="pr-${PR}-review-$$"
WT="$OUT/worktree"

cleanup() {
  if [ "$KEEP" -eq 1 ]; then
    warn "worktree kept at $WT (branch $BRANCH)"
    return
  fi
  git -C "$CLONE" worktree remove --force "$WT" >/dev/null 2>&1
  git -C "$CLONE" worktree prune                >/dev/null 2>&1
  git -C "$CLONE" branch -D "$BRANCH"           >/dev/null 2>&1
}
trap cleanup EXIT

printf 'PR      : %s#%s\nclone   : %s\nJDK     : %s\noutput  : %s\n' \
  "$REPO" "$PR" "$CLONE" "$GOT" "$OUT" >&2

# ------------------------------------------------------- 1. PR metadata -----
say "1/11  PR metadata"
gh pr view "$PR" --repo "$REPO" \
   --json title,body,author,state,createdAt,updatedAt,headRefName,baseRefName,\
additions,deletions,changedFiles,commits,mergeable \
   > "$OUT/01-metadata.json" 2>"$OUT/01-metadata.err" \
   || die "could not fetch PR (see $OUT/01-metadata.err)"

python3 - "$OUT/01-metadata.json" > "$OUT/01-metadata.md" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"# {d['title']}\n")
print(f"- author: @{d['author']['login']}   state: {d['state']}   mergeable: {d.get('mergeable')}")
print(f"- {d['headRefName']} -> {d['baseRefName']}")
print(f"- +{d['additions']} -{d['deletions']} across {d['changedFiles']} files")
print(f"- created {d['createdAt']}  updated {d['updatedAt']}\n")
print("## Commits (check authorship on ports!)\n")
for c in d["commits"]:
    who = ", ".join(f"{a['name']} <{a['email']}>" for a in c["authors"])
    print(f"- `{c['oid'][:10]}` **{who}** — {c['messageHeadline']}")
print("\n## Description\n")
print(d["body"] or "_(empty)_")
PY
ok "$OUT/01-metadata.md"
grep -E '^- (author|\+)' "$OUT/01-metadata.md" | sed 's/^/    /' >&2

TITLE=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["title"])' "$OUT/01-metadata.json")
BASE=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["baseRefName"])' "$OUT/01-metadata.json")

# ------------------------------- 2. feedback: comments + ALL threads --------
# REST /pulls/N/comments misses threads that were resolved or marked outdated.
# GraphQL is the only way to see everything, which matters when you are
# checking "did they address my earlier review".
say "2/11  Review feedback (incl. resolved + outdated threads)"
{
    echo "# Feedback on $REPO#$PR"
    echo
    echo "## Issue comments"
    echo
    gh api "repos/$REPO/issues/$PR/comments" --paginate \
       -q '.[] | "### @\(.user.login) — \(.created_at)\n\n\(.body)\n"' 2>/dev/null
    echo
    echo "## Review threads"
    echo
    gh api graphql -f query="
    { repository(owner:\"${REPO%%/*}\", name:\"${REPO##*/}\") {
        pullRequest(number: $PR) {
          reviewThreads(first:100){ nodes { isResolved isOutdated path line
            comments(first:50){ nodes { author{login} body } } } }
          reviews(first:100){ nodes { author{login} state body } } } } }" 2>/dev/null \
    | python3 -c '
import json,sys
try: d=json.load(sys.stdin)["data"]["repository"]["pullRequest"]
except Exception: print("_(none)_"); raise SystemExit
th=d["reviewThreads"]["nodes"]
if not th: print("_(no inline review threads)_\n")
for t in th:
    flags=[]
    if t["isResolved"]: flags.append("RESOLVED")
    if t["isOutdated"]: flags.append("OUTDATED")
    f=(" **["+", ".join(flags)+"]**") if flags else ""
    print(f"### `{t[\"path\"]}`:{t[\"line\"]}{f}\n")
    for c in t["comments"]["nodes"]:
        print(f"- **@{c[\"author\"][\"login\"]}**: {c[\"body\"]}\n")
print("## Formal reviews\n")
rv=[r for r in d["reviews"]["nodes"] if (r["body"] or "").strip() or r["state"]!="COMMENTED"]
if not rv: print("_(none)_")
for r in rv:
    print(f"- **@{r[\"author\"][\"login\"]}** {r[\"state\"]}: {(r[\"body\"] or \"\").strip()[:800]}")
'
} > "$OUT/02-feedback.md" 2>/dev/null
ok "$OUT/02-feedback.md"

# ------------------------------------------------- 3. diff + changed files --
say "3/11  Diff"
gh pr diff "$PR" --repo "$REPO" > "$OUT/03-diff.patch" 2>/dev/null
gh pr view "$PR" --repo "$REPO" --json files -q '.files[].path' > "$OUT/04-files.txt" 2>/dev/null
ok "$(wc -l < "$OUT/04-files.txt" | tr -d ' ') files changed -> $OUT/04-files.txt"

# Split the diff once, here: step 8 needs it to tell this PR's own test failures
# apart from the module's, and steps 10-11 need it to stage red and green.
TESTS=""; MAINS=""; TESTCLASSES=""
while IFS= read -r f; do
    case "$f" in
        */src/test/*) TESTS="$TESTS $f"
            case "$f" in */src/test/java/*.java) b="${f##*/}"; TESTCLASSES="$TESTCLASSES,${b%.java}" ;; esac ;;
        */src/main/*) MAINS="$MAINS $f" ;;
    esac
done < "$OUT/04-files.txt"
TESTCLASSES="${TESTCLASSES#,}"

# ------------------------------------- 4. is this a port? changelog check ---
# House rule (verified against #4152/#4154/#4157/#4128): 2.x -> main ports do
# NOT get a changelog entry in main. The fix already has one on 2.x and the bug
# never shipped in a released 3.x. Entries in .3.x.x are for 3.x-only changes.
say "4/11  Port / changelog sanity"
IS_PORT=0
echo "$TITLE" | grep -qiE '\bport\b|\[main\]' && IS_PORT=1
# grep -c prints 0 and exits 1 on no match; don't add a second "0" with `|| echo 0`
CHANGELOG_ADDED=$(grep -c '^src/changelog/' "$OUT/04-files.txt" 2>/dev/null)
CHANGELOG_ADDED=${CHANGELOG_ADDED:-0}
{
    echo "# Port / changelog"
    echo
    echo "- title looks like a port: $([ $IS_PORT -eq 1 ] && echo YES || echo no)"
    echo "- changelog files touched: $CHANGELOG_ADDED"
    echo
    if [ "$IS_PORT" -eq 1 ] && [ "$CHANGELOG_ADDED" -gt 0 ]; then
        echo "> **CHECK**: this looks like a port *and* adds a changelog entry."
        echo "> Recent main ports added none (#4152, #4154, #4157, #4128)."
        echo "> An entry is only warranted if the port needed extra, user-visible"
        echo "> 3.x-only work beyond a faithful port — and then it should describe"
        echo "> *that*, not the ported fix."
        echo
    fi
    echo "## Recent main ports, for precedent"
    echo '```'
    git -C "$CLONE" log --oneline -25 origin/main 2>/dev/null \
      | grep -iE 'port|\[main\]' | head -10
    echo '```'
    echo
    echo "## Files each of those touched under src/changelog"
    for c in $(git -C "$CLONE" log --format=%h -40 origin/main 2>/dev/null | head -40); do
        subj=$(git -C "$CLONE" log -1 --format=%s "$c")
        case "$subj" in
            *[Pp]ort*|*"[main]"*)
                n=$(git -C "$CLONE" show --stat --format="" "$c" \
                    | grep -c 'src/changelog' || true)
                echo "- \`$c\` changelog files: ${n:-0} — $subj" ;;
        esac
    done
} > "$OUT/05-changelog.md"
ok "$OUT/05-changelog.md"
[ "$IS_PORT" -eq 1 ] && [ "$CHANGELOG_ADDED" -gt 0 ] \
    && warn "port PR adds a changelog entry — verify against precedent"

# ------------------------ 5. upstream 2.x comparison hints (ports only) -----
say "5/11  2.x comparison hints"
{
    echo "# 2.x comparison"
    echo
    echo "Most review findings on a port come from diffing against what actually"
    echo "landed on 2.x. Look for: things 2.x deliberately KEPT (deprecated"
    echo "aliases, constants) that the port silently drops, and behaviour the"
    echo "port changes without saying so."
    echo
    git -C "$CLONE" fetch origin 2.x --quiet 2>/dev/null
    while IFS= read -r f; do
        case "$f" in *.java) ;; *) continue ;; esac
        base=$(basename "$f")
        hits=$(git -C "$CLONE" ls-tree -r --name-only origin/2.x \
               | grep -F "/$base" | head -3)
        [ -n "$hits" ] || continue
        echo "## \`$f\`"
        echo
        echo "2.x counterpart(s):"
        echo "$hits" | sed 's/^/  - /'
        echo
        echo "  git show origin/2.x:$(echo "$hits" | head -1) | less"
        echo
    done < "$OUT/04-files.txt"
} > "$OUT/06-2x-comparison.md"
ok "$OUT/06-2x-comparison.md"

if [ "$NO_BUILD" -eq 1 ]; then
    say "skipping everything that compiles (--no-build)"
    printf '\nOutput in %s\n' "$OUT" >&2
    exit 0
fi

# --------------------------------------------------------- 6. worktree -----
say "6/11  Checking out PR into a worktree"
git -C "$CLONE" fetch origin "pull/$PR/head:$BRANCH" --force --quiet \
    || die "could not fetch PR head"
git -C "$CLONE" fetch origin "$BASE" --quiet 2>/dev/null
BASE_SHA="$(git -C "$CLONE" rev-parse FETCH_HEAD 2>/dev/null)"
git -C "$CLONE" worktree add --quiet "$WT" "$BRANCH" || die "could not create worktree"
# in a linked worktree .git is a *file* pointing at the real gitdir, not a dir
[ -e "$WT/.git" ] || die "worktree missing at $WT — refusing to continue"
MERGE_BASE="$(git -C "$CLONE" merge-base "$BASE_SHA" "$BRANCH" 2>/dev/null)"
ok "worktree at $WT   (base $BASE @ ${MERGE_BASE:0:10})"

# --------------------------------------------- 7. decide build scope -------
MODULES=""
NEED_FULL=$FORCE_FULL
while IFS= read -r f; do
    top="${f%%/*}"
    for t in $FULL_TRIGGERS; do
        [ "$top" = "$t" ] && NEED_FULL=1
    done
    case "$top" in
        log4j-*) case " $MODULES " in *" $top "*) ;; *) MODULES="$MODULES $top" ;; esac ;;
    esac
done < "$OUT/04-files.txt"

# a change in log4j-X is only really exercised by log4j-X-test
EXTRA=""
for m in $MODULES; do
    case "$m" in *-test) continue ;; esac
    [ -d "$CLONE/$m-test" ] && EXTRA="$EXTRA $m-test"
done
MODULES="$MODULES$EXTRA"
MODULES=$(echo "$MODULES" | tr ' ' '\n' | grep -v '^$' | sort -u | paste -sd, -)

MVN_COMMON="$OFFLINE -Dbnd.baseline.skip=true -Denforcer.skip=true \
-Drat.skip=true -Dmaven.javadoc.skip=true -Dspotbugs.skip=true -Dcyclonedx.skip=true"

if [ "$NEED_FULL" -eq 1 ]; then
    SCOPE="FULL REACTOR"
    BUILD_ARGS=""
else
    SCOPE="scoped: $MODULES"
    BUILD_ARGS="-pl $MODULES -am"
fi

say "7/11  Build ($SCOPE)"
[ "$NEED_FULL" -eq 1 ] && warn "full build forced: change touches build-wide code"
( cd "$WT" && ./mvnw $MVN_COMMON $BUILD_ARGS -DskipTests -Dspotless.skip=true install ) \
    > "$OUT/07-build.log" 2>&1
BUILD_RC=$?
if [ $BUILD_RC -eq 0 ]; then ok "BUILD SUCCESS"; else bad "BUILD FAILED (rc=$BUILD_RC)"; fi
grep -E '^\[INFO\] (BUILD|Apache Log4j).*(SUCCESS|FAILURE|SKIPPED)' "$OUT/07-build.log" \
    | tail -40 > "$OUT/07-build-summary.txt"
grep -E '^\[ERROR\]' "$OUT/07-build.log" | head -30 >> "$OUT/07-build-summary.txt"

# ------------------------------------------------- 8. tests + spotless -----
say "8/11  Tests + spotless"
( cd "$WT" && ./mvnw $MVN_COMMON ${BUILD_ARGS:+-pl $MODULES} -Dspotless.skip=true test ) \
    > "$OUT/08-test.log" 2>&1
TEST_RC=$?
# per-module rollups are the "Tests run:" lines WITHOUT "-- in <class>"
{
    echo "# failures / errors (if any)"
    grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$OUT/08-test.log" || echo "(none)"
    echo
    echo "# totals"
    grep -E 'Tests run:' "$OUT/08-test.log" | grep -v -- '-- in' \
      | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6; s+=$8}
                      END{printf "run=%d failures=%d errors=%d skipped=%d\n",t,f,e,s}'
} > "$OUT/08-test-summary.txt"

# A failing class in this module is not the same claim as a failing PR. The
# suite here is the whole module — 8729 tests for log4j-core-test — and it has
# its own failures on any given machine. Split them, and do not call the ones
# this PR never touched "pre-existing": that needs a run against the base, which
# this step has not done.
FAIL_CLASSES=$(grep -oE '<<< (FAILURE|ERROR)! -- in [A-Za-z0-9_.]+' "$OUT/08-test.log" 2>/dev/null \
                 | sed 's/.* -- in //' | sort -u)
MINE=""; OTHERS=""
for fq in $FAIL_CLASSES; do
    simple="${fq##*.}"
    case ",$TESTCLASSES," in
        *",$simple,"*) MINE="$MINE $fq" ;;
        *)             OTHERS="$OTHERS $fq" ;;
    esac
done
N_MINE=$(printf '%s\n' $MINE   | grep -c . 2>/dev/null); N_MINE=${N_MINE:-0}
N_OTHERS=$(printf '%s\n' $OTHERS | grep -c . 2>/dev/null); N_OTHERS=${N_OTHERS:-0}
{
    echo "# Failing test classes — $REPO#$PR"
    echo
    echo "## In test classes this PR touches ($N_MINE)"
    echo
    if [ "$N_MINE" -eq 0 ]; then echo "_(none)_"; else printf '%s\n' $MINE | sed 's/^/- /'; fi
    echo
    echo "## In classes this PR does not touch ($N_OTHERS)"
    echo
    if [ "$N_OTHERS" -eq 0 ]; then echo "_(none)_"; else printf '%s\n' $OTHERS | sed 's/^/- /'; fi
    echo
    echo "These may well be pre-existing on this machine, but nothing here proves"
    echo "it. To tell pre-existing from caused, run the same suite on \`$BASE\`:"
    echo
    echo '```'
    echo "git -C \"$WT\" checkout --detach ${MERGE_BASE:-$BASE}"
    echo "( cd \"$WT\" && ./mvnw -pl $MODULES -am test )"
    echo '```'
} > "$OUT/08-test-failures.md"

if [ $TEST_RC -eq 0 ]; then
    ok "tests passed"
elif [ "$N_MINE" -gt 0 ]; then
    bad "tests FAILED, including $N_MINE class(es) this PR touches"
    printf '%s\n' $MINE | sed 's/^/      /' >&2
else
    warn "tests FAILED in $N_OTHERS class(es), none of them touched by this PR"
    warn "see 08-test-failures.md — a base run is what settles 'pre-existing'"
fi

( cd "$WT" && ./mvnw $OFFLINE -q spotless:check ) > "$OUT/09-spotless.log" 2>&1
SPOT_RC=$?
if [ $SPOT_RC -eq 0 ]; then ok "spotless clean"; else bad "spotless VIOLATIONS — see $OUT/09-spotless.log"; fi

# --------------------------- 9. did the tests dirty the source tree? -------
# Annotation-processor and codegen tests love to write into the module dir.
# A clean tree here is a real, checkable property — and it has to be measured
# NOW, before red/green start checking files in and out of the tree themselves.
say "9/11  Source-tree pollution check"
POLLUTION=$(git -C "$WT" status --porcelain 2>/dev/null)
if [ ! -e "$WT/.git" ]; then
    POLLUTION="(worktree gone — pollution check did NOT run)"
    warn "$POLLUTION"
elif [ -z "$POLLUTION" ]; then
    ok "tree clean after tests"
else
    warn "tests left files behind:"
    echo "$POLLUTION" | sed 's/^/      /' >&2
fi
echo "$POLLUTION" > "$OUT/10-pollution.txt"

# ------------------------------------- 10/11. does the test test the fix? ---
# The check a human cannot do by reading: put the PR's tests on the base commit
# WITHOUT its production change, and see whether anything actually goes red.
# A test that passes here asserts that the code loads, not that it was fixed.
RG_ARGS="$MVN_COMMON ${BUILD_ARGS:+-pl $MODULES -am} -Dspotless.skip=true -Dsurefire.failIfNoSpecifiedTests=false"
[ -n "$TESTCLASSES" ] && RG_ARGS="$RG_ARGS -Dtest=$TESTCLASSES"

RED_KIND=""; GREEN_RC=1
if [ $BUILD_RC -ne 0 ]; then
    RED_KIND="skipped-build"
    warn "10/11  RED skipped — the branch does not build, so red/green prove nothing"
elif [ -z "$MERGE_BASE" ]; then
    RED_KIND="skipped-base"
    warn "10/11  RED skipped — could not resolve the merge base against $BASE"
elif [ -z "$TESTS" ]; then
    RED_KIND="no-tests"
    warn "10/11  RED skipped — the PR ships no test files. That is itself the finding."
elif [ -z "$MAINS" ]; then
    RED_KIND="no-fix"
    warn "10/11  RED skipped — the PR changes no main sources (test- or docs-only)."
else
    say "10/11  RED — base + the PR's test files only, which must FAIL"
    ( cd "$WT" && git checkout --quiet --detach "$MERGE_BASE" && git checkout --quiet "$BRANCH" -- $TESTS ) \
        || die "could not stage the test-only tree"
    ( cd "$WT" && ./mvnw $RG_ARGS test ) > "$OUT/11-red.log" 2>&1
    RED_RC=$?
    # A non-zero exit is not yet a red. Insist on evidence the tests ran and
    # failed, or that they could not compile against the unfixed sources.
    if [ $RED_RC -eq 0 ]; then
        RED_KIND="passed"
        bad "the tests PASS without the fix — they do not test this change"
    elif grep -qE 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$OUT/11-red.log"; then
        RED_KIND="assert"
        ok "tests fail without the fix, as they should"
        grep -m2 -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$OUT/11-red.log" | sed 's/^/      /' >&2
    elif grep -q 'COMPILATION ERROR' "$OUT/11-red.log" && grep -q '/src/test/' "$OUT/11-red.log"; then
        RED_KIND="compile"
        ok "the test does not compile without the fix (valid red, but it pins an API, not a behaviour)"
    else
        RED_KIND="inconclusive"
        bad "build failed before the tests ran — not a red; read 11-red.log"
        grep -m3 -E '^\[ERROR\]' "$OUT/11-red.log" | sed 's/^/      /' >&2
    fi

    say "11/11  GREEN — the fix added back, which must PASS"
    ( cd "$WT" && git checkout --quiet "$BRANCH" -- $MAINS ) || die "could not stage the fix"
    ( cd "$WT" && ./mvnw $RG_ARGS test ) > "$OUT/12-green.log" 2>&1
    GREEN_RC=$?
    if [ $GREEN_RC -eq 0 ]; then ok "GREEN passed"; else bad "GREEN failed — see 12-green.log"; fi
fi

# --------------------------------------------------------------- summary ---
red_cell() {
  case "$RED_KIND" in
    assert)        echo 'fail, as required' ;;
    compile)       echo '**compile error** — valid, but pins an API not a behaviour' ;;
    passed)        echo '**PASS — the tests do not test the fix**' ;;
    inconclusive)  echo '**inconclusive** — build broke before the tests ran' ;;
    no-tests)      echo '**skipped — the PR ships no tests**' ;;
    no-fix)        echo 'skipped — no main-source change' ;;
    skipped-build) echo 'skipped — the branch does not build' ;;
    skipped-base)  echo 'skipped — no merge base' ;;
  esac
}
{
    echo "# Review evidence — $REPO#$PR"
    echo
    echo "\`$TITLE\`"
    echo
    echo "| check | result |"
    echo "|---|---|"
    echo "| build ($SCOPE) | $([ $BUILD_RC -eq 0 ] && echo 'PASS' || echo '**FAIL**') |"
    if [ $TEST_RC -eq 0 ]; then
        echo "| tests | PASS |"
    elif [ "$N_MINE" -gt 0 ]; then
        echo "| tests | **FAIL — $N_MINE class(es) this PR touches**$([ "$N_OTHERS" -gt 0 ] && echo ", plus $N_OTHERS elsewhere") |"
    elif [ "$N_OTHERS" -gt 0 ]; then
        echo "| tests | FAIL in $N_OTHERS class(es), **none touched by this PR** — see 08-test-failures.md |"
    else
        echo "| tests | **FAIL** — no per-class failures parsed; read 08-test.log |"
    fi
    echo "| spotless | $([ $SPOT_RC -eq 0 ] && echo 'clean' || echo '**violations**') |"
    echo "| source tree clean after tests | $([ -z "$POLLUTION" ] && echo 'yes' || echo '**no — see 10-pollution.txt**') |"
    echo "| **RED** — tests without the fix | $(red_cell) |"
    case "$RED_KIND" in
      assert|compile) echo "| **GREEN** — tests with the fix | $([ $GREEN_RC -eq 0 ] && echo 'PASS' || echo '**FAIL**') |" ;;
    esac
    echo "| port? | $([ $IS_PORT -eq 1 ] && echo yes || echo no) |"
    echo "| changelog files touched | $CHANGELOG_ADDED |"
    echo
    echo "## Test totals"
    echo '```'
    cat "$OUT/08-test-summary.txt" 2>/dev/null
    echo '```'
    echo
    echo "## Now read, in order"
    echo
    echo "1. \`02-feedback.md\` — what was actually asked for. Tick each item off."
    echo "2. \`06-2x-comparison.md\` — for ports, diff against 2.x. Highest-yield step."
    echo "3. \`03-diff.patch\` — separate *required 3.x adaptation* from *extra work*."
    echo "4. \`05-changelog.md\` — ports should not add an entry."
    echo "5. \`08-test-failures.md\` — which failures are this PR's and which are the module's."
    echo "6. \`07-build-summary.txt\`, \`10-pollution.txt\`, \`11-red.log\` — the mechanical facts."
} > "$OUT/00-SUMMARY.md"

say "Done"
cat "$OUT/00-SUMMARY.md"

# ------------------------------------------------ hand it to the archive ---
# The evidence above is disposable — .bench/ is cleared by `./bench clean`. The
# write-up is not, and it is the one artefact no harvester can reconstruct:
# public threads record what was said, not the reasoning that got there.
if [ "$FILE_IT" -eq 1 ]; then
    say "Filing the write-up into the knowledge base"
    # Write-ups live beside the ledger `oss followup` reads, not in this
    # repository. They outlive every checkout that produced them, and a home
    # directory is where the whole workflow now keeps them.
    REVIEWS="${OSS_CLI_HOME:-$HOME/.oss-cli}/reviews"
    WRITEUP=$(ls "$REVIEWS"/"$PR"-*.md 2>/dev/null | head -1)
    if [ -z "$WRITEUP" ]; then
        warn "no $REVIEWS/$PR-*.md yet — nothing to file"
        warn "write it first; the evidence in $OUT is what you write it from"
    elif [ ! -x "$KB/pr-review-file.py" ]; then
        warn "no pr-review-file.py in $KB (set \$BENCH_KB_DIR)"
    else
        "$KB/pr-review-file.py" "$WRITEUP" --pr "$PR" --repo "$REPO" --apply \
            && ok "filed $(basename "$WRITEUP") — indexed under Projects/<topic>/pr-reviews/" \
            || warn "pr-review-file.py failed; the write-up is untouched at $WRITEUP"
    fi
fi

# ------------------------------------------------ the half this cannot do ---
say "these facts are necessary, not sufficient — next, by hand:"
cat >&2 <<NEXT
    Reference/reviewing-a-contributor-pull-request §2   fix vs overshoot?
    ./bench coverage                        does any app put that module on a classpath
    ./bench repro $PR --pr --config <cfg> --scenario <s>
    ~/.oss-cli/reviews/$PR-<slug>.md        write it up, paste-ready block last
NEXT
printf '\nAll output: %s\n' "$OUT" >&2

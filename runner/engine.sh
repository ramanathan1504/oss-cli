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
# The matrix engine — walk a pack across its version × config × app matrix.
#
# Every line below is what you type. This block IS `oss run help`: usage() reads
# it, so an example here that nobody can paste is a wrong answer given to the
# person who came looking. It said `./bench ...` for a while after the engine
# moved into oss and the script stopped being a program you could call directly.
#
# `oss run run` is not a typo. The first word is the dispatcher, the second is
# this engine's verb for running one app.
#
#   oss run list                                    what exists
#   oss run run consumer --config xml/layout-ecs    run scenarios under a config
#   oss run run consumer --version 3.8.1 startup    run one scenario on one version
#   oss run matrix --scenario startup               same scenario, every version
#   oss run matrix --apps consumer,db --javas 17,21
#   oss run matrix --all                            every valid cell — hours
#   oss run coverage                                what is reached, what is not
#   oss run repro 4143 --version 3.8.1              build a standalone repro zip
#   oss run pr 4133 --checkout --install            read a PR, and run it here
#   oss run review 4133                             every mechanical fact about a PR
#   oss run hub                                     every repo you follow, one local page
#   oss run hub --pr 4133                           write and send the review, on the page
#
# The application names and versions above are placeholders: they come from YOUR
# pack, and there is no list of them here because this engine does not know what
# it is walking. Run `oss run list` in a pack to see its own.
#
# `--log4j` was accepted as an alias for `--version` until 4.0, because the first
# pack this engine ever ran called it that. It is gone: a flag named after one
# project, in an engine whose whole design is not knowing what it walks.
#
# All of them need a pack — the applications, configurations and versions being
# walked. Either stand in one, or name it:
#
#   cd ~/my-project && oss run list
#   oss run --pack ~/my-project list
#
# Every run forks a real JVM with an explicit classpath rather than running
# inside Maven's, so what executes here is exactly what a repro zip would ship.

set -euo pipefail

# Colour only when somebody is there to see it.
#
# Fourteen printf calls in this file emitted their escapes unconditionally, so
# every error, every PASS/FAIL row and every coverage heading went into a
# redirected log, a CI transcript and a cron mail carrying raw control bytes. The
# Java half of this tool has been careful about exactly this for releases --
# `Live` checks for a terminal and honours NO_COLOR, and its comment explains why
# -- while this half, the one that runs the builds and therefore the one whose
# output actually gets redirected, never did.
#
# Gated on stderr, because that is where the messages go, and a run whose stdout
# is piped to a file while stderr is still the terminal should stay readable.
# NO_COLOR is honoured for the same reason the Java side honours it: somebody has
# said, once, for every tool on their machine.
if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_CYAN=$'\033[36m'; C_BOLD=$'\033[1m';  C_OFF=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""; C_BOLD=""; C_OFF=""
fi

# Resolve the symlink chain before taking a directory. Installed on PATH as a
# symlink, $BASH_SOURCE is ~/.local/bin/bench and a naive dirname makes ROOT
# ~/.local/bin — where `configs/` and `scripts/` do not exist. `list --apps`
# still passes from there, because APPS is a literal array in this file, so the
# breakage looks like a working install until a command touches disk.
# macOS ships bash 3.2, which has no `readlink -f`; walk it by hand.
SELF="${BASH_SOURCE[0]}"
while [[ -L "$SELF" ]]; do
  LINK="$(readlink "$SELF")"
  case "$LINK" in
    /*) SELF="$LINK" ;;
    *)  SELF="$(cd "$(dirname "$SELF")" && pwd)/$LINK" ;;
  esac
done
# Where the ENGINE lives: its own directory, beside the verbs it dispatches to.
ENGINE_DIR="$(cd "$(dirname "$SELF")" && pwd)"

# Where the PACK lives: a directory somebody else owns, holding pack.sh and the
# applications and configurations it names. It is passed in, because the engine
# no longer ships with a project to test -- that is the whole point of the split.
#
# Falling back to the current directory keeps `cd my-pack && engine list` working
# and makes the error, when there is no pack, name a path the caller recognises.
ROOT="${OSS_PACK_DIR:-$PWD}"
CACHE="${OSS_PACK_CACHE:-$ROOT/.bench}"
mkdir -p "$CACHE"

# ── The pack: what this bench tests ─────────────────────────────────────────
# Everything above this line is the engine -- forking JVMs, walking a matrix,
# caching classpaths -- and none of it is specific to any one project. What IS specific
# lives in a pack, so the same engine can be pointed at another project by
# writing one file rather than by editing this one.
#
#   BENCH_PACK=mine oss run list      loads packs/mine/pack.sh
#
# Sourced, not executed, because it defines arrays and a function this script
# then uses directly. `set -u` is already on, so a pack that forgets a variable
# fails at the check below with its name, rather than as an empty axis that
# silently sweeps nothing.
# A repository that IS a pack puts pack.sh at its root. A repository that
# CARRIES packs keeps them under packs/<name>/. Both are supported, and the
# root spelling is preferred because it is what a pack repository looks like.
#
# A pack that describes itself is rendered by oss into this shape and handed over
# by path. It is checked first and returns immediately: written as a plain branch
# above the others it set PACK_FILE and was then overwritten two lines later by
# the BENCH_PACK selection, which is the kind of bug that shows up as "my pack was
# ignored" rather than as an error.
BENCH_PACK="${BENCH_PACK:-}"
if [[ -n "${OSS_PACK_FILE:-}" && -f "${OSS_PACK_FILE}" ]]; then
  PACK_FILE="$OSS_PACK_FILE"
  BENCH_PACK="${BENCH_PACK:-rendered}"
elif [[ -n "$BENCH_PACK" ]]; then
  PACK_FILE="$ROOT/packs/$BENCH_PACK/pack.sh"
elif [[ -f "$ROOT/pack.sh" ]]; then
  PACK_FILE="$ROOT/pack.sh"
  BENCH_PACK="$(basename "$ROOT")"
else
  # No pack here, and none named. There is deliberately no default: this used to
  # fall back to packs/log4j, which is one project's pack, so a user standing in
  # the wrong directory got "no pack in ..." naming a project they had never
  # heard of. The check below prints what a pack is and both ways to point at one.
  PACK_FILE="$ROOT/pack.sh"
fi
[[ -f $PACK_FILE ]] || {
  printf '%serror%s no pack in %s\n\n' "$C_RED" "$C_OFF" "$ROOT" >&2
  printf '  A pack is a directory with a pack.json in it — your applications, your\n' >&2
  printf '  configurations, your versions. Point at one:\n\n' >&2
  printf '    oss run --pack /path/to/your/pack <verb> ...\n' >&2
  printf '    cd /path/to/your/pack && oss run <verb> ...\n\n' >&2
  _avail=""
  # `|| true` is load-bearing. With no packs/ the glob stays literal, `[[ -f ]]`
  # is false, and the for loop's status IS that failed test -- so the assignment
  # returns 1 and set -e kills the script before it finishes explaining itself.
  # Every form a pack can take, not just the oldest. Globbing */pack.sh alone made a directory
  # holding a pack.json invisible in this list -- the declarative form the documentation tells
  # people to write, absent from the hint that tells them what is already here.
  _avail="$(for d in "$ROOT"/packs/*/pack.sh "$ROOT"/packs/*/pack.json "$ROOT"/packs/*/pack.md; do [[ -f $d ]] && printf ' %s' "$(basename "$(dirname "$d")")"; done | tr ' ' '\n' | sort -u | tr '\n' ' ' || true)"
  # An `[[ ]] && printf` here returns 1 when there are no packs, and under set -e
  # that kills the script before the line below ever prints. Same trap as every
  # other top-level && in this file.
  if [[ -n "$_avail" ]]; then
    printf '  this directory carries packs:%s (BENCH_PACK=<name>)\n' "$_avail" >&2
  fi
  # The declarative one first: it is what the documentation tells people to save, and it was the
  # one format this repository shipped no example of at all.
  printf '  A worked example, one file: %s\n' "$ENGINE_DIR/packs/example-json/pack.json" >&2
  printf '  The older shell form, if you prefer it: %s\n' "$ENGINE_DIR/packs/example/pack.sh" >&2
  exit 1
}
# ── What a pack may leave out ───────────────────────────────────────────────
#
# Defined before the pack is sourced, so the pack's own definitions replace
# these simply by existing. Bash has no interface to implement and no way to
# declare a hook optional, so "optional" has to mean "there is already one".
#
# Without this block, a pack that defined only the four required declarations
# and pack_module_path -- which is exactly what the shipped example does, and
# what the documentation tells people to write -- produced five
# `command not found` lines PER CELL on stderr, went on to report FAIL for
# every one of them, and exited 0. A run that never ran anything looked like a
# run that ran and failed.
#
# Each default is the permissive answer: nothing is excluded, nothing is
# required, no extra flags. A pack says otherwise by saying so.
pack_skip_reason()          { :; }   # why this cell cannot run. Nothing: it can
pack_min_java_for()         { echo 0; }
pack_min_version_for()      { :; }
pack_requires_config_for()  { :; }
pack_requires_app_for()     { :; }
pack_build_flags()          { :; }
pack_jvm_args()             { :; }
pack_always_jvm_args()      { :; }
pack_config_args()          { :; }
pack_gradle_version_flag()  { :; }
pack_main_class_for()       { :; }
pack_modules()              { :; }
pack_modules_on_classpath() { cat >/dev/null; }
pack_upstream_repo()        { :; }
pack_source_clone()         { :; }
pack_source_clone_hint()    { :; }

# What has to be installed before a Gradle app in this pack can build. The one
# pack this engine grew up with builds a Maven module first and installs it to
# ~/.m2, because Gradle has no view of a Maven reactor -- which is a fact about
# that pack's layout and was written into the engine as `mvn -pl apps/core-java`.
# Any other pack reaching this line built somebody else's module.
pack_gradle_prereq()        { :; }

# shellcheck source=packs/example/pack.sh
. "$PACK_FILE"

# A pack that loads but declares nothing would produce an empty matrix, and an
# empty matrix reports "0 cells, 0 failures" -- which reads exactly like a pass.
for _required in PACK_NAME VERSIONS DEFAULT_VERSION APPS; do
  if ! declare -p "$_required" >/dev/null 2>&1; then
    printf '%serror%s pack %s does not set %s\n' "$C_RED" "$C_OFF" "$BENCH_PACK" "$_required" >&2
    exit 1
  fi
done
declare -F pack_module_path >/dev/null 2>&1 || {
  printf '%serror%s pack %s does not define pack_module_path()\n' "$C_RED" "$C_OFF" "$BENCH_PACK" >&2
  exit 1
}
# Optional, with the historic layout as the default so an older pack still works.
PACK_CONFIGS_DIR="${PACK_CONFIGS_DIR:-configs}"
PACK_APPS_DIR="${PACK_APPS_DIR:-apps}"
# Only defaulted when the pack did not set it. `("${APPS_2X_ONLY[@]:-}")` looks
# like an empty-array default and is not: for an UNSET array it produces one
# element containing the empty string, so a pack declaring no exclusions reported
# one, and pack_is_2x_only matched an empty app name.
[[ ${APPS_2X_ONLY+x} ]] || APPS_2X_ONLY=()


# ── The JDK axis ────────────────────────────────────────────────────────────
# Major versions of every JDK installed, oldest first. Derived rather than
# hard-coded, so the axis reflects this machine.
# Every JDK major version this machine can run, for the --all matrix axis.
# Same three sources as java_home_for, for the same reason.
installed_javas() {
  {
    if [[ -x /usr/libexec/java_home ]]; then
      /usr/libexec/java_home -V 2>&1 \
        | sed -n 's/^ *\([0-9][0-9.]*[0-9_]*\).*/\1/p' \
        | sed -e 's/^1\.\([0-9][0-9]*\).*/\1/' -e 's/^\([0-9][0-9]*\)\..*/\1/'
    fi

    # actions/setup-java exports one of these per JDK it installed.
    env | sed -n 's/^JAVA_HOME_\([0-9][0-9]*\)_[A-Z0-9]*=.*/\1/p'

    # Conventional Linux layout: java-17-openjdk-amd64, jdk-21, temurin-8-jdk...
    local d
    for d in /usr/lib/jvm/*; do
      [[ -x "$d/bin/java" ]] || continue
      basename "$d" | sed -n 's/.*[^0-9]\([0-9][0-9]*\).*/\1/p'
    done
  } 2>/dev/null | grep -E '^[0-9]+$' | sort -un
}






# True when $1 is an older version than $2. sort -V orders SNAPSHOTs after
# their release, which is what we want: 2.27.0-SNAPSHOT is not "older than"
# 2.25.0.
version_lt() {
  [[ "$1" == "$2" ]] && return 1
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]
}

# ── Cell validity ───────────────────────────────────────────────────────────
# Prints why a (app, config, java, version) cell cannot run, or nothing if it can.
# Every exclusion here is a fact about the pack or the app, not a convenience: a
# cell that is skipped for a stated reason is information, a cell that fails for
# an unstated one is noise.
cell_skip_reason() {
  local app="$1" config="$2" java="$3" version="$4"

  # The pack goes first: only it knows what its own versions and formats mean.
  local packsays; packsays="$(pack_skip_reason "$app" "$config" "$java" "$version")"
  [[ -n "$packsays" ]] && { echo "$packsays"; return; }

  local min; min="$(pack_min_java_for "$app")"
  [[ "$java" -lt "$min" ]] && { echo "$app is compiled at release $min"; return; }

  # A server app cannot finish a cell. Say so instead of timing it out.
  # ${arr[@]+"${arr[@]}"}: the list is empty now, and bash 3.2 under `set -u`
  # treats an empty array as unbound.
  local ia
  for ia in ${PACK_INTERACTIVE_APPS[@]+"${PACK_INTERACTIVE_APPS[@]}"}; do
    if [[ "$app" == "$ia" ]]; then
      echo "$app serves until interrupted — drive it by hand, or give it a self-test like SelfTestRunner"
      return
    fi
  done

  # An app that asserts on a specific appender, paired with a config that has
  # no such destination. The cell cannot pass however correct the library is.
  local needs; needs="$(pack_requires_config_for "$app")"
  if [[ -n "$needs" && "$config" != *"$needs"* ]]; then
    echo "$app asserts on $needs; $config cannot satisfy it"
    return
  fi

  # The mirror: a config whose destinations only one app provides.
  local owner; owner="$(pack_requires_app_for "$config")"
  if [[ -n "$owner" && "$app" != "$owner" ]]; then
    echo "$config needs the in-process infrastructure only $owner starts"
    return
  fi

  # An app whose module is younger than the version under test.
  #
  # The `!= 3.*` that used to be here exempted one project's 3.x line, where the
  # module had been reinstated -- a fact about that project, in the engine, that
  # silently un-skipped cells for every other pack whose versions happen to start
  # with a 3. A pack that needs an exemption says so in pack_skip_reason, which
  # runs first and can say anything.
  local minv; minv="$(pack_min_version_for "$app")"
  if [[ -n "$minv" ]] && version_lt "$version" "$minv"; then
    echo "$app needs $PACK_NAME >= $minv (its module did not exist at $version)"
    return
  fi

  # Reproductions are hand-run against one named PR or issue, and some of them
  # are built to FAIL — repro-jpa-failed-startup names a persistence unit that
  # does not exist, because a manager whose startup failed is the thing under
  # test. In a sweep that is a failure with no stated reason, which is the exact
  # noise every rule above exists to remove. They stay selectable by name for
  # `oss run run` and `oss run repro`; only the cross product skips them.
  if [[ "$config" == */repro-* ]]; then
    echo "$config is a hand-run reproduction, not a sweep cell (see Reference/reviewing-a-contributor-pull-request)"
    return
  fi


  # An old-JDK module is usually deliberately small: it has the core artifacts and the
  # config formats, and nothing that would drag in a Java 17 dependency.
  if [[ "$app" == java8-baseline ]]; then
    case "$config" in
      */appender-jdbc|*/layout-jsontemplate) echo "java8-baseline omits this module's dependency"; return ;;
    esac
  fi
}

die()  { printf '%serror:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }
info() { printf '%s▸%s %s\n' "$C_CYAN" "$C_OFF" "$*" >&2; }

# ── JDK selection ───────────────────────────────────────────────────────────
# Resolve a JDK home for a major version. macOS has java_home; Linux does not,
# and CI needs to work there — GitHub's ubuntu runners are a tenth the cost of
# macOS ones, and a maintainer tool that only runs on one OS is half a tool.
#
# Three sources, in order of how specific they are: java_home on macOS,
# the JAVA_HOME_<major>_<arch> variables actions/setup-java exports, and the
# conventional /usr/lib/jvm layout.
java_home_for() {
  local want="$1"
  if [[ -z "$want" ]]; then
    echo "${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
    return
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    local home
    if home="$(/usr/libexec/java_home -v "$want" 2>/dev/null)"; then
      echo "$home"; return
    fi
  fi

  local var
  for var in "JAVA_HOME_${want}_X64" "JAVA_HOME_${want}_ARM64" "JAVA_HOME_${want}_AARCH64"; do
    if [[ -n "${!var:-}" && -x "${!var}/bin/java" ]]; then
      echo "${!var}"; return
    fi
  done

  local d
  for d in /usr/lib/jvm/*"${want}"*; do
    if [[ -x "$d/bin/java" ]]; then
      echo "$d"; return
    fi
  done

  die "no JDK $want found (looked at java_home, JAVA_HOME_${want}_*, /usr/lib/jvm)"
}

# ── Maven flags for a given version ─────────────────────────────────────────
# What a major version needs -- a profile, a pinned API line, a property that
# suppresses modules that do not exist yet -- is a fact about the project being
# walked, so it comes from the pack. Sets the global MVN_FLAGS array: a global
# rather than a return value because macOS ships bash 3.2, which has no
# mapfile/readarray to capture one.
# Expanded at the use site as ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"}, never bare.
# macOS ships bash 3.2, where an EMPTY array is "unbound" under `set -u` -- so a
# pack that adds no build flags, which is the ordinary case and what the default
# pack_build_flags does, crashed the build with `MVN_FLAGS[@]: unbound variable`
# instead of running Maven with no extra flags. The same rule already cost this
# file a bug once, in PACK_INTERACTIVE_APPS.
MVN_FLAGS=()
set_mvn_flags() {
  # How a version reaches the build is the pack's business: the property name,
  # the profile, whether a major needs either. An engine that hardcoded any one
  # project's version property could only ever build that project.
  MVN_FLAGS=()
  local f
  while IFS= read -r f; do
    [[ -n "$f" ]] && MVN_FLAGS+=("$f")
  done < <(pack_build_flags "$1")
}

# The pack's first configuration, in the same order `list` prints them, or
# nothing when the pack has none. A pack with no configs is legitimate -- the
# shipped example has none -- and an empty config is what the run path already
# treats as "the app's own defaults".
first_config() {
  local dir="$ROOT/$PACK_CONFIGS_DIR" f
  [[ -d "$dir" ]] || return 0
  for f in $(cd "$dir" && ls 2>/dev/null); do
    if [[ -d "$dir/$f" ]]; then
      local inner
      for inner in $(cd "$dir/$f" && ls 2>/dev/null); do
        printf '%s/%s\n' "$f" "${inner%.*}"
        return 0
      done
    else
      printf '%s\n' "${f%.*}"
      return 0
    fi
  done
}

# The mapping itself is pack content; the error is the engine's, so that every
# pack gets the same "unknown app, here are the known ones" message without
# having to write it.
module_path_for() {
  pack_module_path "$1" || die "unknown app '$1' (known: ${APPS[*]})"
}

# Apps built by Gradle rather than Maven.
is_gradle() { [[ "$1" == spring-boot-gradle ]]; }


# JVM flags an app needs regardless of configuration. Sets the global
# EXTRA_JVM_ARGS, because bash 3.2 -- what macOS ships -- has no mapfile to
# capture an array from a function.
#
# WHICH flags is entirely the pack's business: a --add-opens for a reflective
# static initialiser, a self-test switch so a server app can finish a cell, a
# per-subsystem JNDI flag. The engine knows only that some app needs some flags.
EXTRA_JVM_ARGS=()
extra_jvm_args_for() {
  EXTRA_JVM_ARGS=()
  local f
  while IFS= read -r f; do
    [[ -n "$f" ]] && EXTRA_JVM_ARGS+=("$f")
  done < <(pack_jvm_args "$1")
}

# ── Classpath resolution, cached per (app, version) ─────────────────────────
classpath_for() {
  local app="$1" version="$2"
  local module; module="$(module_path_for "$app")"
  local key="$CACHE/cp-${app}-${version}.txt"

  # Always build. The build tools are incremental, so this is cheap when nothing
  # changed, and skipping it on a source-only edit would silently run stale
  # classes — the worst possible failure mode for a bench you are using to judge
  # whether a change in the library altered behaviour.
  #
  # The one exception is a matrix sweep, where the same (app, version) pair is
  # rebuilt for every config and JDK even though nothing between cells can have
  # changed the sources. Maven's own startup then dominates the run: at roughly
  # 10s a cell it is the difference between a sweep taking hours and taking
  # days. BENCH_REUSE_BUILDS=1 reuses the cached classpath whenever one exists
  # for the pair — which may have been written by an earlier run, not just this
  # one. Never set it while editing app sources: that is precisely the case the
  # always-build policy protects against.
  if [[ "${BENCH_REUSE_BUILDS:-0}" == 1 && -s "$key" ]]; then
    if is_gradle "$app"; then
      cat "$key"
    else
      printf '%s:%s/%s/target/classes' "$(cat "$key")" "$ROOT" "$module"
    fi
    return
  fi

  set_mvn_flags "$version"

  # The pack said where this app lives; check it is there before handing the
  # path to Maven. Without this the failure was a forty-line Maven stack ending
  # in MavenExecutionException, for what is one sentence: the pack points at a
  # directory that does not exist. Somebody writing their first pack meets this
  # error, and it should tell them about their pack rather than about Maven.
  if [[ ! -d "$ROOT/$module" ]]; then
    die "pack $PACK_NAME maps app '$app' to '$module', and there is no directory at $ROOT/$module
       pack_module_path() is what decides this. Either create the module, or point it somewhere real."
  fi

  info "building: $app @ $PACK_NAME $version"

  if is_gradle "$app"; then
    # Gradle has no view of a Maven reactor, so a Gradle app that depends on a
    # module in the same pack needs that module installed first. WHICH module is
    # the pack's business: this was `mvn -pl apps/core-java`, a path out of one
    # project's tree, so any other pack's Gradle app tried to build somebody
    # else's module and died saying so.
    pack_gradle_prereq "$app" "$version" || die "gradle prerequisites failed for $app"

    command -v gradle >/dev/null || die "gradle not on PATH (needed by $app)"
    ( cd "$ROOT/$module" && gradle -q --console=plain \
        "$(pack_gradle_version_flag "$version")" \
        -PbenchClasspathFile="$key" \
        benchClasspath >&2 ) || die "gradle build failed for $app @ $version"

    # Gradle's task already put the module's own classes on the list.
    cat "$key"
    return
  fi

  ( cd "$ROOT" && mvn -q -pl "$module" -am \
      ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} \
      -DskipTests install \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
      -Dmdep.outputFile="$key" >&2 ) || die "build failed for $app @ $version"

  printf '%s:%s/%s/target/classes' "$(cat "$key")" "$ROOT" "$module"
}

# Accepts a full path, a repo-relative path, or a bare name like
# "xml/layout-ecs" / "layout-ecs". Always emits an absolute path, because a
# framework given a relative configuration file resolves it against the JVM's
# cwd, and the JVM's cwd is not where the user typed the command.
resolve_config() {
  local cfg="$1"
  [[ -z "$cfg" ]] && return 0

  # The extension is inferred from the directory, so the same config name works
  # across formats: `--config json/filter-all` and `--config yaml/filter-all`
  # both resolve, and a bare `filter-all` still means the XML one.
  local candidate
  for candidate in \
      "$cfg" \
      "$ROOT/$cfg" \
      "$ROOT/configs/$cfg" \
      "$ROOT/configs/$cfg.xml" \
      "$ROOT/configs/$cfg.json" \
      "$ROOT/configs/$cfg.yaml" \
      "$ROOT/configs/$cfg.properties" \
      "$ROOT/configs/xml/$cfg.xml"; do
    if [[ -f "$candidate" ]]; then
      [[ "$candidate" = /* ]] && printf '%s\n' "$candidate" || printf '%s/%s\n' "$PWD" "$candidate"
      return 0
    fi
  done
  die "no such config: $cfg (try: oss run list --configs)"
}

# ── Commands ────────────────────────────────────────────────────────────────
cmd_list() {
  case "${1:-all}" in
    --versions|versions)
      printf '%s\n' "${VERSIONS[@]}" ;;
    --apps|apps)
      printf '%s\n' "${APPS[@]}" ;;
    --configs|configs)
      # A pack without a configs directory is a pack that has not got there yet, not an error --
      # and the worked example this repository ships is one, so `oss run --pack <example> list`
      # answered with a raw `cd: .../configs: No such file or directory` from this line. The first
      # thing a newcomer copies, printing a shell error at them.
      if [ -d "$ROOT/$PACK_CONFIGS_DIR" ]; then
        ( cd "$ROOT/$PACK_CONFIGS_DIR" && find . -type f \( -name '*.xml' -o -name '*.json' -o -name '*.yaml' -o -name '*.properties' \) \
            | sed 's|^\./||' | sort )
      else
        echo "(none yet — add files under $PACK_CONFIGS_DIR/)"
      fi ;;
    *)
      echo "Apps:";     printf '  %s\n' "${APPS[@]}"
      echo; echo "$PACK_NAME versions:"; printf '  %s\n' "${VERSIONS[@]}"
      echo; echo "Configs:"; cmd_list --configs | sed 's/^/  /'
      echo; echo "Scenarios:"
      cmd_run "${APPS[0]:-}" --quiet-banner -- --list 2>/dev/null | sed 's/^/  /' || true
      ;;
  esac
}

cmd_run() {
  local app="${1:-${APPS[0]:-}}"; shift || true
  local version="$DEFAULT_VERSION" config="" javaver="" args=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --version)          version="$2"; shift 2 ;;
      --config) config="$2";  shift 2 ;;
      --java)   javaver="$2"; shift 2 ;;
      --quiet-banner) shift ;;
      --) shift; args+=("$@"); break ;;
      *) args+=("$1"); shift ;;
    esac
  done

  local cp;   cp="$(classpath_for "$app" "$version")"
  local jhome; jhome="$(java_home_for "$javaver")"
  extra_jvm_args_for "$app"
  local jvm_args=(${EXTRA_JVM_ARGS[@]+"${EXTRA_JVM_ARGS[@]}"})

  # Runtime properties the pack always wants set. Which ones is its business.
  local pjf
  while IFS= read -r pjf; do
    [[ -n "$pjf" ]] && jvm_args+=("$pjf")
  done < <(pack_always_jvm_args)

  # Ad-hoc JVM flags, because unrecognised CLI arguments are passed to the app
  # rather than the JVM. Mainly for turning the StatusLogger all the way up
  # when an appender fails quietly:
  #   BENCH_JVM_ARGS='-Dyour.framework.debug=true' oss run run <app> ...
  if [[ -n "${BENCH_JVM_ARGS:-}" ]]; then
    # Deliberately unquoted: the variable holds several space-separated flags.
    jvm_args+=(${BENCH_JVM_ARGS})
  fi

  if [[ -n "$config" ]]; then
    # A comma-separated list is a COMPOSITE configuration: the framework reads every
    # file and merges them, later files overriding earlier ones per the
    # MergeStrategy. Each element is resolved separately so the usual short
    # names still work: --config xml/baseline-console,xml/filter-all
    local resolved
    if [[ "$config" == *,* ]]; then
      local part parts=()
      local IFS=,
      for part in $config; do
        parts+=("$(resolve_config "$part")")
      done
      unset IFS
      resolved="$(printf '%s,' "${parts[@]}")"
      resolved="${resolved%,}"
    else
      resolved="$(resolve_config "$config")"
    fi

    # HOW a configuration is selected is the pack's deepest secret. Get it wrong
    # and nothing fails -- the framework falls back to its default and logs
    # happily, which looks enough like success that a whole column can pass while
    # testing nothing. One flag per line; bash 3.2 has no mapfile.
    local cfg_flag
    while IFS= read -r cfg_flag; do
      [[ -n "$cfg_flag" ]] && jvm_args+=("$cfg_flag")
    done < <(pack_config_args "$app" "$resolved" "$version")
  fi

  # Run from the repo root so relative log paths in configs land in ./logs
  cd "$ROOT"
  # ${arr[@]+"${arr[@]}"} rather than "${arr[@]}": under `set -u`, bash 3.2
  # (which is what macOS ships) treats an empty array as an unbound variable.
  exec "$jhome/bin/java" \
    ${jvm_args[@]+"${jvm_args[@]}"} \
    -cp "$cp" \
    "$(pack_main_class_for "$app")" \
    ${args[@]+"${args[@]}"}
}

# Sweep app × config × JDK × version.
#
# Every axis defaults to a single representative value rather than to "all",
# because the full cross product is thousands of forked JVMs and most of its
# cells are invalid. --all opens every axis at once; anything narrower is
# expressed by listing values.
cmd_matrix() {
  local scenario="" all=0
  local apps_arg="" configs_arg="" javas_arg="" versions_arg=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --scenario) scenario="$2";     shift 2 ;;
      --app|--apps)       apps_arg="$2";     shift 2 ;;
      --config|--configs) configs_arg="$2";  shift 2 ;;
      --java|--javas)     javas_arg="$2";    shift 2 ;;
      --version)          versions_arg="$2"; shift 2 ;;
      --all)      all=1; shift ;;
      # Build each (app, version) once instead of once per cell. Safe for a
      # sweep, where nothing between cells changes the sources; see the note in
      # classpath_for for when not to use it.
      --reuse-builds) export BENCH_REUSE_BUILDS=1; shift ;;
      *) die "matrix: unexpected argument '$1'" ;;
    esac
  done

  local apps configs javas versions
  if [[ $all -eq 1 ]]; then
    apps="$(printf '%s\n' "${APPS[@]}" | tr '\n' ',')"
    # Strip the extension only where doing so stays unambiguous. A config
    # directory holding both name.xml and name.properties has stripping collapse them
    # into one name that resolve_config always answered with the XML — so the
    # 1.x properties format was never tested and the XML one ran twice.
    configs="$(cmd_list --configs | awk '
      { full[NR] = $0; b = $0; sub(/\.[a-z]+$/, "", b); base[NR] = b; seen[b]++ }
      END { for (i = 1; i <= NR; i++) print (seen[base[i]] > 1 ? full[i] : base[i]) }
    ' | tr '\n' ',')"
    javas="$(installed_javas | tr '\n' ',')"
    versions="$(printf '%s\n' "${VERSIONS[@]}" | tr '\n' ',')"
  else
    # Defaults: one app, one config, this machine's default JDK, every
    # line — the version axis is the one you almost always want swept.
    # The pack's own first app and first config. It used to be `core-java` and
    # `xml/baseline-console`, which are two names out of one project's pack: any
    # other pack got a matrix over apps it does not have, and every cell failed
    # for a reason that was never its fault.
    apps="${apps_arg:-${APPS[0]:-}}"
    configs="${configs_arg:-$(first_config)}"
    javas="${javas_arg:-$(java_major_of "$(java_home_for '')")}"
    versions="${versions_arg:-$(printf '%s\n' "${VERSIONS[@]}" | tr '\n' ',')}"
  fi
  [[ -n "$apps_arg"     ]] && apps="$apps_arg"
  [[ -n "$configs_arg"  ]] && configs="$configs_arg"
  [[ -n "$javas_arg"    ]] && javas="$javas_arg"
  [[ -n "$versions_arg" ]] && versions="$versions_arg"

  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
  local results="$CACHE/matrix-$stamp.log"
  local ledger="$CACHE/coverage.tsv"

  # Per-cell wall-clock bound. GNU timeout is `timeout` on Linux and `gtimeout`
  # from coreutils on macOS; if neither is present the sweep still runs, because
  # refusing to run at all would be a worse answer than running unbounded — but
  # say so, since an unbounded sweep can hang on a single cell.
  CELL_RUNNER=()
  local secs="${BENCH_CELL_TIMEOUT:-300}"
  if command -v timeout >/dev/null 2>&1; then
    CELL_RUNNER=(timeout -k 10 "$secs")
  elif command -v gtimeout >/dev/null 2>&1; then
    CELL_RUNNER=(gtimeout -k 10 "$secs")
  else
    printf '  %snote%s  no timeout(1) found — cells run unbounded; an app that does not exit will stall the sweep\n' "$C_YELLOW" "$C_OFF"
  fi

  local pass=0 fail=0 skip=0
  printf '%s matrix — scenario=%s\n' "$PACK_NAME" "${scenario:-<all>}" | tee "$results"
  printf '  apps     %s\n  configs  %s\n  javas    %s\n  versions %s\n\n' \
    "$(csv_pretty "$apps")" "$(csv_pretty "$configs")" \
    "$(csv_pretty "$javas")" "$(csv_pretty "$versions")" | tee -a "$results"

  # A pack with no configs at all is legitimate -- the shipped example has none,
  # and an app run with no configuration file uses its own defaults. csv_split of
  # an empty string yields nothing, so the config loop never entered, every
  # product was empty, and the sweep reported "0 pass, 0 fail, 0 skip" and exited
  # 0. This file's own comment says an empty matrix "reads exactly like a pass",
  # and here it was doing precisely that for every pack but one.
  #
  # The sentinel is the empty string, which is what the run path already treats
  # as "no --config".
  local config_axis; config_axis="$(csv_split "$configs")"
  [[ -z "$config_axis" ]] && config_axis=""

  local app config java version reason
  for app in $(csv_split "$apps"); do
    for config in ${config_axis:-""}; do
      for java in $(csv_split "$javas"); do
        for version in $(csv_split "$versions"); do

          reason="$(cell_skip_reason "$app" "$config" "$java" "$version")"
          if [[ -n "$reason" ]]; then
            printf '  %sSKIP%s  %-18s %-30s java%-3s %-16s  %s\n' "$C_YELLOW" "$C_OFF" \
              "$app" "$config" "$java" "$version" "$reason"
            printf 'SKIP\t%s\t%s\t%s\t%s\t%s\n' "$app" "$config" "$java" "$version" "$reason" >>"$results"
            skip=$((skip + 1))
            continue
          fi

          local run_args=("$app" --version "$version" --config "$config" --java "$java")
          [[ -n "$scenario" ]] && run_args+=("$scenario")

          printf '\n═══ %s | %s | java %s | %s %s ═══\n' \
            "$app" "$config" "$java" "$PACK_NAME" "$version" >>"$results"

          # Bound every cell. Without this one app that does not terminate stalls
          # the whole sweep silently — spring-boot-maven is a real Spring Boot web
          # app whose main() is SpringApplication.run, so it serves until killed
          # and its cell never returns. A sweep sat on it for two hours looking
          # exactly like slow progress. A cell that outruns the limit is a FAIL
          # with a stated cause, which is information; a sweep that hangs is not.
          if ( ${CELL_RUNNER[@]+"${CELL_RUNNER[@]}"} "$0" run "${run_args[@]}" ) >>"$results" 2>&1; then
            printf '  %sPASS%s  %-18s %-30s java%-3s %s\n' "$C_GREEN" "$C_OFF" \
              "$app" "$config" "$java" "$version"
            printf '%s\tPASS\t%s\t%s\t%s\t%s\n' "$stamp" "$app" "$config" "$java" "$version" >>"$ledger"
            pass=$((pass + 1))
          else
            printf '  %sFAIL%s  %-18s %-30s java%-3s %s\n' "$C_RED" "$C_OFF" \
              "$app" "$config" "$java" "$version"
            printf '%s\tFAIL\t%s\t%s\t%s\t%s\n' "$stamp" "$app" "$config" "$java" "$version" >>"$ledger"
            fail=$((fail + 1))
          fi
        done
      done
    done
  done

  if (( pass + fail + skip == 0 )); then
    # Nothing ran. This printed "0 pass, 0 fail, 0 skip" and exited 0, which is
    # the shape of a clean sweep -- and it is what every pack but one got, for
    # releases, because the config axis was empty and the loops never entered.
    # A sweep that produced no cells has not passed; it has not started.
    printf '\n%sno cells%s — the axes produced nothing to run.\n' "$C_RED" "$C_OFF" >&2
    printf '  apps: %s\n  configs: %s\n  javas: %s\n  versions: %s\n' \
      "$(csv_pretty "$apps")" "$(csv_pretty "$configs")" \
      "$(csv_pretty "$javas")" "$(csv_pretty "$versions")" >&2
    printf '  An empty axis is usually a pack that declares no APPS or no VERSIONS,\n' >&2
    printf '  or a --apps/--versions naming something the pack does not have.\n' >&2
    return 1
  fi
  printf '\n%d pass, %d fail, %d skip\nFull output: %s\n' "$pass" "$fail" "$skip" "$results"

  # Exit non-zero when any cell failed. Without this a sweep is green whatever
  # happens, which is fine to read by eye and useless to a CI job — the whole
  # point of running it on a pull request is that a failure stops the merge.
  # A SKIP is not a failure: it is a cell that cannot exist, with a reason.
  [[ $fail -eq 0 ]]
}

csv_split()  { printf '%s' "$1" | tr ',' ' '; }
csv_pretty() { printf '%s' "$1" | tr ',' ' ' | sed 's/  */ /g; s/ $//'; }

# Major version of the JDK at a given JAVA_HOME, e.g. 17.
java_major_of() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || { echo 17; return; }
  "$home/bin/java" -version 2>&1 \
    | sed -n '1s/.*version "\([0-9][0-9.]*\).*/\1/p' \
    | sed -e 's/^1\.\([0-9][0-9]*\).*/\1/' -e 's/^\([0-9][0-9]*\)\..*/\1/'
}

cmd_repro() { exec "$ENGINE_DIR/repro.sh" "$@"; }
cmd_pr()    { exec "$ENGINE_DIR/gh-pr.sh" "$@"; }
cmd_review() { exec "$ENGINE_DIR/pr-review.sh" "$@"; }
cmd_hub() {
  # The hub stayed with the pack that owns its review composer, because the half
  # of it that can POST is not coming into the core. `oss hub` is the read-only
  # answer and needs nothing from here.
  if [[ -x "$ROOT/hub.py" || -f "$ROOT/hub.py" ]]; then
    exec python3 "$ROOT/hub.py" "$@"
  fi
  printf 'moved: the read-only hub is in the core.\n\n  oss hub\n' >&2
  exit 2
}

# `.bench` stopped being purely disposable when the hub started keeping state
# there. Two things in it cannot be rebuilt: the daily reports, which are the
# only record of days whose commits have since been squashed away, and
# `.bench/reviews`, which is evidence a written finding cites. Everything else —
# classpath caches, sweep logs, the cell ledger — is derived and costs only time.
# So `clean` takes the derived half and leaves those two; `--all` takes the lot.
cmd_clean() {
  if [ "${1:-}" = "--all" ]; then
    rm -rf "$CACHE"
    info "cleared $CACHE entirely — daily reports and review evidence included"
    return
  fi
  [ -n "${1:-}" ] && die "unknown flag '$1' (clean takes --all, or nothing)"
  [ -d "$CACHE" ] || { info "nothing to clear — no $CACHE"; return; }
  local was; was=$(du -sh "$CACHE" 2>/dev/null | cut -f1)
  find "$CACHE" -mindepth 1 -maxdepth 1 ! -name hub ! -name reviews \
       -exec rm -rf {} + 2>/dev/null
  local days; days=$(ls "$CACHE/hub/report" 2>/dev/null | wc -l | tr -d ' ')
  info "cleared $CACHE ($was → $(du -sh "$CACHE" 2>/dev/null | cut -f1))"
  info "kept hub/ (${days} daily reports) and reviews/ — 'oss run clean --all' takes those too"
}

# Where the bench actually reaches, as opposed to where it is meant to reach.
#
# Two different questions, answered separately because they fail differently:
#   * which of the library's modules are on some app's classpath at all — a gap here means
#     a module the bench cannot exercise however it is invoked;
#   * which axis cells have actually been run — a gap here means a combination
#     nobody has tried yet, on a classpath that could have tried it.

cmd_coverage() {
  # WHERE the source is, and WHAT counts as a module, are the pack's answers.
  # The engine only knows how to compare two lists.
  local which="2x"
  case "${1:-}" in
    --3x) which="3x"; shift ;;
    --2x) shift ;;
  esac
  local clone; clone="$(pack_source_clone "$which")"

  [[ -d "$clone" ]] || die "no source clone at $clone ($(pack_source_clone_hint))"

  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  pack_modules "$clone" > "$tmp/all"

  # Modules resolved onto any app's classpath. Requires that the classpaths have
  # been resolved at least once, which any `oss run run <app>` does.
  cat "$CACHE"/cp-*.txt 2>/dev/null | pack_modules_on_classpath > "$tmp/on-classpath"

  local total covered
  total="$(wc -l < "$tmp/all" | tr -d ' ')"
  covered="$(comm -12 "$tmp/all" "$tmp/on-classpath" | wc -l | tr -d ' ')"

  printf '%sModule coverage%s  (%s)\n' "$C_BOLD" "$C_OFF" "$clone"
  printf '  %s of %s shippable modules are on some app classpath\n\n' "$covered" "$total"

  printf '  %snot on any classpath:%s\n' "$C_RED" "$C_OFF"
  comm -23 "$tmp/all" "$tmp/on-classpath" | sed 's/^/    /'

  printf '\n  %son a classpath:%s\n' "$C_GREEN" "$C_OFF"
  comm -12 "$tmp/all" "$tmp/on-classpath" | tr '\n' ' ' | fold -s -w 76 | sed 's/^/    /'
  printf '\n'

  # Note: a module being present says the bench *can* reach it. Whether any
  # config actually drives it is a separate question the ledger cannot answer,
  # since a classpath entry is not proof a plugin was instantiated.
  printf '\n%sAxis coverage%s  (from %s)\n' "$C_BOLD" "$C_OFF" "$CACHE/coverage.tsv"
  if [[ ! -s "$CACHE/coverage.tsv" ]]; then
    printf '  no matrix runs recorded yet — run oss run matrix\n'
    return
  fi

  printf '  %s cells run, %s distinct\n' \
    "$(wc -l < "$CACHE/coverage.tsv" | tr -d ' ')" \
    "$(cut -f3-6 "$CACHE/coverage.tsv" | sort -u | wc -l | tr -d ' ')"
  printf '  %s pass, %s fail\n\n' \
    "$(cut -f2 "$CACHE/coverage.tsv" | grep -c PASS)" \
    "$(cut -f2 "$CACHE/coverage.tsv" | grep -c FAIL)"

  # Count distinct (app, config, java, version) cells per axis value. Deduping
  # on the whole cell matters: re-running the same slice must not look like
  # wider coverage than it is.
  printf '  by %s version:\n' "$PACK_NAME"
  cut -f3-6 "$CACHE/coverage.tsv" | sort -u | cut -f4 | sort | uniq -c \
    | awk '{printf "    %-18s %s cell(s)\n", $2, $1}'
  printf '  by JDK:\n'
  cut -f3-6 "$CACHE/coverage.tsv" | sort -u | cut -f3 | sort -n | uniq -c \
    | awk '{printf "    java %-13s %s cell(s)\n", $2, $1}'
  printf '  by app:\n'
  cut -f3-6 "$CACHE/coverage.tsv" | sort -u | cut -f1 | sort | uniq -c \
    | awk '{printf "    %-18s %s cell(s)\n", $2, $1}'

  local failed
  failed="$(awk -F'\t' '$2=="FAIL" {printf "    %s %s java%s %s\n", $3, $4, $5, $6}' \
    "$CACHE/coverage.tsv" | sort -u)"
  if [[ -n "$failed" ]]; then
    printf '\n  %sfailing cells:%s\n%s\n' "$C_RED" "$C_OFF" "$failed"
  fi
}

usage() {
  # The header block, however long it grows -- a fixed line range silently
  # starts printing the script once someone adds a command to it.
  #
  # It also has to step over the licence block, which is comments too: printing
  # from line 1 gave `bench help` fourteen lines of Apache 2.0 before the first
  # command. Anchored to the end of the licence instead, so neither growing the
  # header nor re-wording the licence can break it.
  sed -n '/limitations under the License\./,$p' "${BASH_SOURCE[0]}" \
    | awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}'
}

case "${1:-help}" in
  list)     shift; cmd_list "$@" ;;
  run)      shift; cmd_run "$@" ;;
  matrix)   shift; cmd_matrix "$@" ;;
  coverage) shift; cmd_coverage "$@" ;;
  repro)    shift; cmd_repro "$@" ;;
  issue)
    # Reading an issue needs an API call and nothing else, so it belongs in the
    # core rather than in a bench somebody has to attach first.
    shift
    printf 'moved: reading an issue needs no bench.\n\n  oss issue %s --repo %s\n' \
      "${1:-<n>}" "$(pack_upstream_repo)" >&2
    exit 2 ;;
  pr)       shift; cmd_pr "$@" ;;
  review)   shift; cmd_review "$@" ;;
  followup)
    # Following a pull request needs one API read and a record, and nothing that
    # forks a JVM -- so it belongs in the core, where it works against any
    # repository rather than only the one this bench is pointed at. The ledger
    # and every write-up moved with it, to ~/.oss-cli/reviews/.
    shift
    {
      printf 'moved: following a pull request needs no bench.\n\n'
      printf '  oss followup %s\n' "${1:-<pr>}"
      printf '  oss followup --since %s   what the author pushed since you reviewed\n' "${1:-<pr>}"
      printf '  oss hub                     is anyone waiting on you\n'
    } >&2
    exit 2 ;;
  hub)      shift; cmd_hub "$@" ;;
  clean)  shift; cmd_clean "$@" ;;
  help|--help|-h) usage ;;
  *) die "unknown command '$1' (try: oss run help)" ;;
esac

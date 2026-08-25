#!/bin/bash
# Do the two web surfaces still describe this build?
#
# There are two, and only one of them lives in this repository:
#
#   site/index.html   the landing page. ubuos.com serves THIS file -- the site
#                     repository fetches it at deploy time and folds it in as
#                     the root.
#
#   ubuos.com/docs    a separate Astro project in ramanathan1504/ubuos-site.
#                     Nothing here can fail a build over another repository.
#
# The landing page's COUNTING is not done here. `DocumentedCommandsTest` already
# derives every figure on that page from release-surface.json -- the board's two
# halves partition the whole command set, and each stated total is the length of
# its own list. A second counter in shell would be a second answer to the same
# question, and two implementations of one thing is how this repository got two
# embedders, two reference parsers and two copies of a web page.
#
# What is left for this script is the half a unit test cannot reach:
#
#   * a command that is SHOWN in --help and never named anywhere in the page's
#     prose. The board can list it while every paragraph still describes the
#     tool as it was two releases ago -- which is what happened to `ask`: on the
#     board, and mentioned nowhere a reader would meet it.
#   * the other repository, over the network.
#
# Run by release.sh before the release pull request is opened, and by hand:
#     tools/check-site.sh              both halves
#     tools/check-site.sh --local      skip the network half
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
LOCAL_ONLY=0
[[ "${1:-}" == "--local" ]] && LOCAL_ONLY=1

JAR=$(ls target/oss-cli-*.jar 2>/dev/null | grep -v original | head -1)
if [[ -z "$JAR" ]]; then
  echo "  (no jar built yet — mvn package first)" >&2
  exit 0
fi

# Only the commands `oss --help` SHOWS. The hidden ones still work, and a page
# that never mentions `hidden-critical` is not out of date, it is uncluttered.
# Four spaces, not two. `oss --help` groups its commands now: the group heading
# sits at two spaces and the commands under it at four. Reading two meant this
# collected "find", "one", "remember", "run", "start", "teach", "what", "when"
# and "who" -- the first word of each heading -- and then checked the page
# explained commands by those names. It would have reported the whole surface
# undocumented, on a page that was fine.
#
# A command row is a name padded to a column, so it is followed by at least two
# spaces. The prose lines under a group -- "put one in front of a command", "cd
# <your-pack> && oss run list" -- are ordinary sentences at the same indent, and
# matching them made "put" a command this page was failing to explain.
#
# The engine prefixes are listed on one line under "who answers" and are not
# commands to document, so they are dropped by name.
SHOWN=$(java -jar "$JAR" --help 2>/dev/null \
  | grep -oE "^    [a-z][a-z-]+ {2,}" \
  | awk '{print $1}' \
  | grep -vE '^(llm|claude|gemini|codex|junie|cd)$' \
  | sort -u)
[[ -z "$SHOWN" ]] && { echo "  (could not read --help)" >&2; exit 0; }

fatal=0

# ─────────────────────────────────────────────────────────────────────────────
# 1. The landing page's prose. In this repository, so fatal.
# ─────────────────────────────────────────────────────────────────────────────
PAGE=site/index.html
if [[ -f "$PAGE" ]]; then
  # The board is excluded on purpose: appearing there is what the unit test
  # already guarantees, and it is not the same as being explained.
  PROSE=$(grep -v '<li class="cmd' "$PAGE")
  unexplained=""
  for cmd in $SHOWN; do
    grep -qE "oss $cmd|<code>$cmd" <<<"$PROSE" || unexplained="$unexplained $cmd"
  done

  if [[ -n "$unexplained" ]]; then
    echo ""
    echo "  ✘ site/index.html lists these commands and never explains them:$unexplained"
    echo ""
    echo "    Being on the board is not being described. ubuos.com serves this exact"
    echo "    file — it is the first thing anybody reads about oss, and it is source"
    echo "    in this repository, so it is fixed here before the release."
    echo ""
    fatal=1
  else
    echo "  site/index.html explains every command --help shows."
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. The documentation site, in the other repository. Reported, never fatal:
#    a network hiccup must not stop a release, and that repo is not this one's
#    to fix in the middle of one.
# ─────────────────────────────────────────────────────────────────────────────
if [[ "$LOCAL_ONLY" == 0 ]]; then
  # The index alone is not the site. Checking only ubuos.com/docs reported
  # seventeen commands "missing" that are documented one page deeper, which is
  # the kind of false alarm that gets a check ignored within a week.
  SITE=""
  for page in /docs /docs/reference/commands /docs/conversations /docs/skills \
              /docs/search /docs/connect /docs/what-it-is; do
    SITE="$SITE$(curl -sL --max-time 20 "https://ubuos.com$page" 2>/dev/null)"
  done
  if [[ -z "$SITE" ]]; then
    echo "  docs check skipped — ubuos.com did not answer" >&2
  else
    # What comes back is RENDERED HTML, so there are no backticks in it at all --
    # the original pattern looked for a markdown form that cannot appear in the
    # thing being searched, and `>triage<` missed the same page because the cell
    # reads `<code>triage &lt;n&gt;</code>`. So `triage` was reported missing from
    # a page that documents it in a table. A check that reports a correct page is
    # a check that gets ignored, which is worse than not having one.
    #
    # Two forms, both anchored: the command written out after `oss`, or the name
    # opening a <code> element. Deliberately not a bare word match -- "run" and
    # "search" appear in ordinary prose on every page, and matching those would
    # make the check pass whatever the site said.
    missing=""
    for cmd in $SHOWN; do
      grep -qiE "oss $cmd([^a-z]|$)|<code[^>]*>$cmd([^a-z]|$)" <<<"$SITE" || missing="$missing $cmd"
    done
    if [[ -z "$missing" ]]; then
      echo "  ubuos.com/docs names every shown command."
    else
      echo ""
      echo "  ⚠ ubuos.com/docs does not mention:$missing"
      echo "    That site is a second repository — ramanathan1504/ubuos-site — and it"
      echo "    does not follow this one automatically. Open a pull request there:"
      echo "      docs/src/content/docs/reference/commands.md"
      echo "      docs/src/content/docs/index.mdx"
      echo ""
    fi
  fi
fi

exit $fatal

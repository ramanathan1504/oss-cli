#!/bin/bash
# Does the documentation site still describe this build?
#
# The site is a second repository (ramanathan1504/ubuos-site) serving ubuos.com/docs,
# and nothing in this one can fail a build over it. So this reports rather than
# refuses -- but it reports by NAME, because "the site may be stale" is a sentence
# nobody acts on and "the site never mentions `ask`" is one somebody fixes.
#
# Run by release.sh before a tag is cut, and by hand any time:
#     tools/check-site.sh
set -uo pipefail

JAR=$(ls target/oss-cli-*.jar 2>/dev/null | grep -v original | head -1)
if [[ -z "$JAR" ]]; then
  echo "  (no jar built yet — mvn package first)" >&2
  exit 0
fi

# Only the commands `oss --help` shows. The hidden ones still work and a site that
# never mentions `hidden-critical` is not out of date, it is uncluttered.
SHOWN=$(java -jar "$JAR" --help 2>/dev/null | grep -oE "^  [a-z][a-z-]+" | awk '{print $1}' | sort -u)
[[ -z "$SHOWN" ]] && { echo "  (could not read --help)" >&2; exit 0; }

# The index alone is not the site. Checking only ubuos.com/docs reported seventeen
# commands "missing" that are documented one page deeper, which is the kind of
# false alarm that gets a check ignored within a week.
SITE=""
for page in /docs /docs/reference/commands /docs/conversations /docs/search /docs/connect; do
  SITE="$SITE$(curl -sL --max-time 20 "https://ubuos.com$page" 2>/dev/null)"
done
if [[ -z "$SITE" ]]; then
  echo "  site check skipped — ubuos.com did not answer" >&2
  exit 0
fi

missing=""
for cmd in $SHOWN; do
  grep -qi "oss $cmd\|\`$cmd\`\|>$cmd<" <<<"$SITE" || missing="$missing $cmd"
done

if [[ -z "$missing" ]]; then
  echo "  site names every shown command."
else
  echo ""
  echo "  ⚠ ubuos.com/docs does not mention:$missing"
  echo "    The site is a second repository — ramanathan1504/ubuos-site — and it does"
  echo "    not follow this one automatically. A reader arriving there is being taught"
  echo "    a surface that no longer exists."
  echo ""
fi
exit 0

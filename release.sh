#!/bin/zsh
#
# release.sh — cut a release.
#
#     ./release.sh 1.3.2
#
# Everything that touches this repository happens here: version bump, docs,
# changelog, build, commit, tag, push. Everything that touches the outside world
# -- publishing the release, uploading the jar, updating the Homebrew tap --
# happens in .github/workflows/release.yml, triggered by the tag this pushes.
#
# The split matters. The tag ends up pointing at the exact tree that was built
# and reviewed, and CI rebuilds from that tag rather than trusting an artifact
# uploaded from a laptop.
#
# main is branch-protected, so the release commit cannot be pushed to it
# directly: it goes up on a release branch, through a pull request, and is
# squash-merged once CI passes. The squash commit carries the identical tree
# (the branch is cut from main's head), so tagging it keeps the guarantee
# above. This script drives that whole loop and only then pushes the tag.

set -e

VERSION=$1

if [ -z "$VERSION" ]; then
    echo "Usage: ./release.sh <version>      e.g. ./release.sh 1.3.2"
    exit 1
fi

if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "❌ '$VERSION' is not a three-part version like 1.3.2."
    exit 1
fi

echo "========================================"
echo "🚀 Releasing v$VERSION"
echo "========================================"

# Everything present when the jar is built gets committed below, so anything
# half-finished would ship.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Working tree is not clean. Commit or stash first:"
    git status --short
    exit 1
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "main" ]; then
    echo "❌ On branch '$BRANCH'. Releases are cut from main."
    exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "❌ Tag v$VERSION already exists. Pick the next version."
    exit 1
fi

echo "→ Fetching, so the release is cut from what is actually on origin..."
git fetch origin main --tags --quiet
BEHIND=$(git rev-list --count HEAD..origin/main)
if [ "$BEHIND" != "0" ]; then
    echo "❌ main is $BEHIND commit(s) behind origin. Run 'git pull' first."
    exit 1
fi

PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

# ---------------------------------------------------------------------------
# Is this number big enough for what changed?
#
# Everything above checks the version's *shape* and that the tag is free.
# Nothing checked that it was *right*, and the number is a promise: 1.10.2
# would have been accepted for the release that added `oss history`,
# `oss chat --resume` and a schema migration, telling every reader "a fix,
# nothing new to learn".
#
# The comparison is against release-surface.json as it stood at the previous
# tag: commands and flags read out of picocli's own model, plus the schema
# version -- because what actually breaks for a user is not a Java signature
# but an older binary meeting a store a newer one migrated.
#
# It runs before the version is set or anything is written, so a refusal
# leaves the tree exactly as it was found.
# ---------------------------------------------------------------------------
if [ -n "$PREV_TAG" ]; then
    echo "→ Checking v$VERSION is a large enough bump since $PREV_TAG..."
    PREV_SURFACE=$(mktemp)
    git show "$PREV_TAG:release-surface.json" > "$PREV_SURFACE" 2>/dev/null || true

    if ! mvn -q test -Dtest=ReleaseGuardTest \
            -Dguard.version="$VERSION" \
            -Dguard.prevTag="$PREV_TAG" \
            -Dguard.prev="$PREV_SURFACE"; then
        rm -f "$PREV_SURFACE"
        echo ""
        echo "❌ Refusing to release v$VERSION. See the reason above."
        echo "   The version number is a promise to whoever reads it."
        exit 1
    fi
    rm -f "$PREV_SURFACE"
    echo "   ✓ bump is large enough"
fi

echo "→ Setting the project version to $VERSION..."
mvn -q versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false

echo "→ Updating version references in the docs..."
# Docs drift because nothing checks them -- README and SETUP still named a 1.1.0
# jar while 1.3.1 was the current release. Anything naming a jar by version is
# rewritten here so a reader never copies a command for a build that is gone.
for f in README.md SETUP.md DEVELOPING.md COMMANDS.md; do
    [ -f "$f" ] && sed -i '' -E "s/oss-cli-[0-9]+\.[0-9]+\.[0-9]+\.jar/oss-cli-${VERSION}.jar/g" "$f"
done

echo "→ Writing the changelog entry..."
if [ -n "$PREV_TAG" ]; then
    RANGE="${PREV_TAG}..HEAD"
    echo "   (changes since $PREV_TAG)"
else
    RANGE="HEAD"
fi

# Subjects only, merge commits dropped: the body of a squash-merged PR repeats
# what the subject already says, and a changelog nobody can skim is not read.
ENTRIES=$(git log "$RANGE" --no-merges --pretty=format:'- %s' | grep -v '^- Release v' || true)
if [ -z "$ENTRIES" ]; then
    ENTRIES="- No changes recorded."
fi

TMP=$(mktemp)
{
    echo "# Changelog"
    echo
    echo "## $VERSION"
    echo
    # UTC, because the website dates the same release from GitHub's published_at
    # and that is UTC. A local date is a day ahead for anything cut after
    # midnight local but before midnight UTC -- which is most evening releases
    # from this timezone. Three in a row read 2026-08-16 here and 2026-08-15
    # there: the same release, two dates, and nothing to say which was right.
    echo "_$(date -u +%Y-%m-%d)_"
    echo
    echo "$ENTRIES"
    echo
    if [ -f CHANGELOG.md ]; then
        tail -n +2 CHANGELOG.md
    fi
} > "$TMP"
mv "$TMP" CHANGELOG.md

echo "→ Formatting..."
mvn -q spotless:apply

echo "→ Building..."
mvn -q clean package -DskipTests

RELEASE_JAR="target/oss-cli-${VERSION}.jar"
if [ ! -f "$RELEASE_JAR" ]; then
    echo "❌ Expected $RELEASE_JAR and it is not there."
    exit 1
fi

echo "→ Committing the released tree..."
RELEASE_BRANCH="release-v$VERSION"
git switch -c "$RELEASE_BRANCH"
git add -A
git commit -q -m "Release v$VERSION"

# The jar was built from exactly this tree; anything left over means it was not.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Tree still dirty after commit -- the jar would not match the tag:"
    git status --short
    exit 1
fi

echo "→ main is protected: sending the release commit through a pull request..."
git push -u origin "$RELEASE_BRANCH"
gh pr create --title "Release v$VERSION" \
    --body "Version bump, changelog and doc references for v$VERSION. Cut by release.sh; the tag is applied to this commit once it lands on main."
PR_NUMBER=$(gh pr view "$RELEASE_BRANCH" --json number -q .number)

echo "→ Waiting for CI on PR #$PR_NUMBER (this gates the merge)..."
# Checks are scheduled a few seconds after the PR is created. Asking before
# they exist prints "no checks reported" and exits nonzero, which set -e
# turns into an aborted release with the PR left open -- v1.8.3 hit exactly
# this. Wait for them to appear, then watch.
for _ in $(seq 1 30); do
    if gh pr checks "$PR_NUMBER" 2>&1 | grep -q "no checks reported"; then
        sleep 10
    else
        break
    fi
done
gh pr checks "$PR_NUMBER" --watch --fail-fast

echo "→ Merging..."
gh pr merge "$PR_NUMBER" --squash --delete-branch

echo "→ Tagging the merged commit..."
git switch main
git pull --ff-only origin main
git tag -a "v$VERSION" -m "OSS-CLI v$VERSION"
git push origin "v$VERSION"

echo "→ Waiting for every asset the tap and the site read..."
# Waiting for a COUNT was wrong, and v2.2.1 proved it within the minute. The four
# archives arrive from Distributions; the .deb arrives later from Packages, which
# runs after it. Nudged on the count of four, the site deployed against a release
# that had no oss_amd64.deb yet -- and the site's own link check failed the deploy
# with "dead link (404)". A guard catching this is the system working; asking it
# to catch it every release is not.
#
# So: wait for the last one to land, by name. oss_amd64.deb is the version-free
# link the download page uses, and it is the final asset either consumer needs.
#
# Best effort. If this times out the release is still published, the polls below
# still catch it, and nothing has been nudged too early.
for _ in $(seq 1 60); do
    HAVE=$(gh release view "v$VERSION" --json assets --jq '[.assets[].name] | join(" ")' 2>/dev/null || echo "")
    case "$HAVE" in
        *oss-macos-arm64.tar.gz*oss_amd64.deb* | *oss_amd64.deb*oss-macos-arm64.tar.gz*) break ;;
    esac
    sleep 20
done

# Push, do not wait to be polled -- and with no stored credential.
#
# Both consumers poll on a schedule, because a cross-repo push once needed a
# personal access token, that token expired, and a release silently shipped a
# formula pointing at the previous version: `brew upgrade` said "already
# installed" while a newer release existed. Removing the credential removed that
# whole class of failure and cost a few hours of staleness.
#
# This gets the immediacy back without putting the credential back. The person
# cutting a release is already authenticated -- `gh` has been used a dozen times
# above -- so the nudge comes from THEM, at the moment they release. Nothing is
# stored, nothing expires, and the polls stay exactly where they are as the
# safety net for a release cut any other way.
nudge() {
    if gh workflow run "$2" --repo "$1" >/dev/null 2>&1; then
        echo "   ✔ $1 — $2"
    else
        # Loud, because the alternative is a stale formula nobody notices. Not
        # fatal: the release itself is out, and the poll will catch it.
        echo "   ⚠ could not start \"$2\" in $1 — it will be picked up by its own schedule"
        echo "     to publish sooner:  gh workflow run \"$2\" --repo $1"
    fi
}

echo "→ Publishing now rather than at the next poll..."
nudge ramanathan1504/homebrew-oss-cli "Bump formula"
nudge ramanathan1504/ubuos-site "Sync release"
nudge ramanathan1504/ubuos-site "Deploy"

echo "========================================"
echo "✅ v$VERSION merged, tagged and published."
echo
echo "CI took it from here, in order:"
echo "   Release        publishes the GitHub release and the jar"
echo "   Distributions  builds the self-contained archives"
echo "   Packages       builds the .deb and the choco .nupkg (runs after Distributions)"
echo "   Bump formula   the tap, started above — brew upgrade oss"
echo "   Deploy         ubuos.com, started above"
echo
echo "   gh run watch"
echo "   https://github.com/ramanathan1504/oss-cli/releases"
echo "========================================"

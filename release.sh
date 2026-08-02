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
    echo "_$(date +%Y-%m-%d)_"
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
git add -A
git commit -q -m "Release v$VERSION"

# The jar was built from exactly this tree; anything left over means it was not.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Tree still dirty after commit -- the jar would not match the tag:"
    git status --short
    exit 1
fi

echo "→ Tagging and pushing..."
git tag -a "v$VERSION" -m "OSS-CLI v$VERSION"
git push origin main
git push origin "v$VERSION"

echo "========================================"
echo "✅ v$VERSION tagged and pushed."
echo
echo "CI takes it from here: build, GitHub release, Homebrew tap."
echo "   gh run watch"
echo "   https://github.com/ramanathan1504/oss-cli/releases"
echo "========================================"

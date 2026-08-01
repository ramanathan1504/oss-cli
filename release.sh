#!/bin/zsh

# Exit immediately if a command fails
set -e

VERSION=$1

if [ -z "$VERSION" ]; then
    echo "❌ Error: Please provide a version number."
    echo "Usage: ./release.sh 1.0.1"
    exit 1
fi

echo "========================================"
echo "🚀 Starting Automated Release for v$VERSION"
echo "========================================"

# Refuse to release from a tree carrying unrelated work. Everything present when
# the jar is built gets committed below, so anything half-finished would ship.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Error: working tree is not clean. Commit or stash first:"
    git status --short
    exit 1
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "main" ]; then
    echo "❌ Error: on branch '$BRANCH'. Releases are cut from main."
    exit 1
fi

echo "1. Bumping version in pom.xml to $VERSION..."
# This Maven command automatically updates the <version> tag in your pom.xml
mvn versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false

echo "1.5 Formatting code with Spotless..."
mvn spotless:apply

echo "2. Compiling fresh project..."
mvn clean package -DskipTests

# The Shade plugin outputs the fat JAR directly to this name
RELEASE_JAR="target/oss-cli-${VERSION}.jar"

if [ ! -f "$RELEASE_JAR" ]; then
    echo "❌ Error: Could not find $RELEASE_JAR!"
    exit 1
fi

echo "3. Committing the released tree to GitHub..."
# -A, not just pom.xml. spotless:apply above rewrites source files, and staging
# only the pom published a jar built from code that existed in no commit -- so
# the tag could not rebuild the artifact it was supposed to describe.
# target/ and *.jar are gitignored, so the build output is not swept in.
git add -A
# Note: || true prevents failing if there are no changes (e.g. running the same version twice)
git commit -m "Bump project version to v$VERSION" || true

# The jar was built from exactly this tree; anything left over means it was not.
if [ -n "$(git status --porcelain)" ]; then
    echo "❌ Error: tree still dirty after commit -- the jar would not match the tag:"
    git status --short
    exit 1
fi

git push origin main

echo "4. Uploading to GitHub Releases..."
# Automatically creates the release and uploads the JAR
gh release create "v$VERSION" "$RELEASE_JAR" --title "OSS-CLI v$VERSION" --generate-notes

echo "5. Calculating SHA-256 Hash..."
JAR_SHA=$(shasum -a 256 "$RELEASE_JAR" | awk '{print $1}')
echo "   ↳ SHA256: $JAR_SHA"

echo "6. Updating Homebrew Tap..."
# Clone the tap into a temporary hidden folder
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"

git clone https://github.com/ramanathan1504/homebrew-oss-cli.git
cd homebrew-oss-cli

# Safely replace the old URL and SHA with the brand new ones
sed -i '' -e "s|url \".*\"|url \"https://github.com/ramanathan1504/oss-cli/releases/download/v${VERSION}/oss-cli-${VERSION}.jar\"|" oss-cli.rb
sed -i '' -e "s|sha256 \".*\"|sha256 \"${JAR_SHA}\"|" oss-cli.rb

# Commit and push the updated formula back to GitHub
git add oss-cli.rb
git commit -m "Bump Homebrew version to v$VERSION"
git push origin main

# Cleanup
rm -rf "$TEMP_DIR"

echo "========================================"
echo "✅ Release v$VERSION Published Successfully!"
echo "Global users can now run: brew upgrade oss-cli"
echo "========================================"
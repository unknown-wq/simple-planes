#!/usr/bin/env bash
# Reassemble and install the vendored Gradle 9.6.1 distribution.
# Needed because the Gradle wrapper cannot download through this environment's
# egress proxy (GitHub release assets return 403).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${GRADLE_DEST:-/opt/gradle-9.6.1}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v unrar >/dev/null 2>&1 || { echo "unrar not found: sudo apt-get install -y unrar"; exit 1; }

# unrar needs volumes named *.part1.rar, *.part2.rar, ... side by side.
cp "$DIR"/gradle-9.6.1-bin.part*.rar "$WORK"/
( cd "$WORK" && unrar x -o+ gradle-9.6.1-bin.part1.rar >/dev/null )

ZIP="$WORK/gradle-9.6.1-bin.zip"
[ -f "$ZIP" ] || { echo "extraction failed: $ZIP missing"; exit 1; }

if [ -d "$DEST" ]; then
  echo "Already installed: $DEST"
else
  sudo unzip -q "$ZIP" -d "$(dirname "$DEST")"
fi

echo "Gradle installed at: $DEST"
echo "Add to PATH:  export PATH=$DEST/bin:\$PATH"

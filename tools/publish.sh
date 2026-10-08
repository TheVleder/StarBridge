#!/usr/bin/env bash
# Publishes the current commit of this (private, full-history) repository to the public one as
# a single new commit: "StarBridge <version>". Private files (EXCLUDE) never leave this repo.
#   tools/publish.sh            # prepare ../starbridge-public and commit, without pushing
#   tools/publish.sh --push     # ...and push to the public repository
set -euo pipefail

PUBLIC_URL="https://github.com/TheVleder/StarBridge.git"
EXCLUDE=(CLAUDE.md)

here="$(cd "$(dirname "$0")/.." && pwd)"
out="$(dirname "$here")/starbridge-public"
version="$(grep '^starbridgeVersion=' "$here/gradle.properties" | cut -d= -f2)"

cd "$here"
if [ -n "$(git status --porcelain)" ]; then
  echo "Commit or stash your changes first: only committed work is published." >&2
  exit 1
fi

if [ ! -d "$out/.git" ]; then
  mkdir -p "$out"
  git -C "$out" init -q -b main
  git -C "$out" remote add origin "$PUBLIC_URL"
  git -C "$out" fetch -q origin 2>/dev/null && git -C "$out" reset -q origin/main 2>/dev/null || true
fi

# Replace the whole tree with this commit's files (deleted files disappear too).
find "$out" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git archive HEAD | tar -x -C "$out"
for f in "${EXCLUDE[@]}"; do rm -rf "${out:?}/$f"; done

cd "$out"
git add -A
if git diff --cached --quiet; then
  echo "Nothing new to publish."
else
  git commit -q -m "StarBridge $version"
  echo "Committed \"StarBridge $version\" in $out"
fi
if [ "${1:-}" = "--push" ]; then
  git push -u origin main
fi

#!/usr/bin/env bash
# Re-vendor the Aegires classes from a pinned commit (only the package line changes),
# then apply the one AndroidMedia change (HttpURLConnection transport).
# Usage: tools/sync-aegires.sh <aegires-clone> <commit>
set -euo pipefail
A=${1:-}; P=${2:-}
[ -d "$A/.git" ] && [ -n "$P" ] || { echo "usage: $0 <aegires-clone> <commit>"; exit 1; }
B=app/src/main/java/a/b/util
D=plugins/E2EE/src/main/java/com/e2ee/plugin
FILES="Crypto Argon2id Blake2b ForcedKeys FileNames MediaCache AndroidMedia ImageLoader SuperProperties"
for f in $FILES; do
  git -C "$A" show "$P:$B/$f.java" | sed '1s/^package a\.b\.util;$/package com.e2ee.plugin;/' > "$D/$f.java.tmp" && mv "$D/$f.java.tmp" "$D/$f.java"
done
for f in $FILES; do
  n=$(diff <(git -C "$A" show "$P:$B/$f.java") "$D/$f.java" | grep -c '^[<>]' || true)
  [ "$n" = 2 ] || echo "WARNING: $f differs from upstream in $n lines (expected 2)"
done
git apply tools/transport.patch
git --no-pager diff --stat

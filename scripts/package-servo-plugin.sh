#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./gradlew :servo-engine-plugin:assembleRelease
APK="$ROOT/servo-engine-plugin/build/outputs/apk/release/servo-engine-plugin-release-unsigned.apk"
OUT="$ROOT/dist/servo-engine"
mkdir -p "$OUT"
cp "$APK" "$OUT/servo-engine.apk"
sha256sum "$OUT/servo-engine.apk" > "$OUT/servo-engine.apk.sha256"
echo "Plugin: $OUT/servo-engine.apk"
echo "SHA256: $(cut -d' ' -f1 "$OUT/servo-engine.apk.sha256")"
echo "Publish both servo-engine.apk and servo-engine.apk.sha256 to the Mula GitHub Release."

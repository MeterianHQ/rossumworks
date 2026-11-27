#!/usr/bin/env bash
# Tests collect-native-programs.sh with a temporary folder of downloaded artifacts.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/collect-native-programs.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
platforms=(linux-x64 linux-arm64 macos-arm64)
fail() { echo "FAIL: $1"; exit 1; }

for platform in "${platforms[@]}"; do
    mkdir -p "$work/artifacts/rossumworks-$platform"
    echo "program $platform" > "$work/artifacts/rossumworks-$platform/rossumworks"
done

bash "$script" "$work/artifacts" "$work/release" 0.6.0 || fail "the collection fails"
for platform in "${platforms[@]}"; do
    release_file="$work/release/rossumworks-0.6.0-$platform"
    [ -x "$release_file" ] || fail "rossumworks-0.6.0-$platform is missing or not executable"
    [ "$(cat "$release_file")" = "program $platform" ] || fail "rossumworks-0.6.0-$platform has other content"
done

rm "$work/artifacts/rossumworks-macos-arm64/rossumworks"
if output=$(bash "$script" "$work/artifacts" "$work/incomplete" 0.6.0 2>&1); then
    fail "a missing program does not stop the collection"
fi
[[ "$output" == *"The native program of macos-arm64 is missing."* ]] || fail "the error does not name the platform"
[ ! -e "$work/incomplete" ] || fail "a missing program leaves a target folder"

echo "PASS: collect-native-programs.sh"

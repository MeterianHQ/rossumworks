#!/usr/bin/env bash
# Copies the native programs of the downloaded artifacts to one folder, with their release file names.
# Usage: collect-native-programs.sh <artifact folder> <target folder> <version>
# Each artifact folder rossumworks-<platform> has the program rossumworks.
set -euo pipefail

artifact_folder="$1"
target_folder="$2"
version="$3"
platforms=(linux-x64 linux-arm64 macos-arm64)

for platform in "${platforms[@]}"; do
    if [ ! -f "$artifact_folder/rossumworks-$platform/rossumworks" ]; then
        echo "::error::The native program of $platform is missing."
        exit 1
    fi
done

mkdir -p "$target_folder"
for platform in "${platforms[@]}"; do
    release_file="$target_folder/rossumworks-$version-$platform"
    cp "$artifact_folder/rossumworks-$platform/rossumworks" "$release_file"
    # upload-artifact does not keep the executable bit.
    chmod +x "$release_file"
done

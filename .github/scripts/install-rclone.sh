#!/usr/bin/env bash
set -euo pipefail
version=1.75.2
sha=349ac8fba6ff65d6247043f1750cdcb518ec5d500ef91463a10d37c0ccdf3702
root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/trufflecel-rclone.XXXXXX")
curl --fail --silent --show-error --location "https://downloads.rclone.org/v$version/rclone-v$version-linux-amd64.zip" -o "$root/rclone.zip"
printf '%s  %s\n' "$sha" "$root/rclone.zip" | sha256sum --check --status
unzip -q "$root/rclone.zip" -d "$root"
printf '%s\n' "$root/rclone-v$version-linux-amd64" >> "${GITHUB_PATH:?}"

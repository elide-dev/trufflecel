#!/usr/bin/env bash
# Puts bazelisk on PATH as `bazel` where the runner has none (self-hosted runners; GitHub's hosted images ship it).
# bazelisk then runs the release in .bazelversion. Each binary is pinned by sha256: a new release must update both.
set -euo pipefail
if command -v bazel > /dev/null; then
  echo "bazel: $(command -v bazel)"
  exit 0
fi
directory="$RUNNER_TEMP/bazelisk"
mkdir -p "$directory"
# Some dedicated runners provide only `bazelisk`; workflows also need the `bazel` name.
if command -v bazelisk > /dev/null; then
  ln -sf "$(command -v bazelisk)" "$directory/bazel"
  echo "$directory" >> "$GITHUB_PATH"
  exit 0
fi
version=1.28.1
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) platform=linux-amd64; sha=22e7d3a188699982f661cf4687137ee52d1f24fec1ec893d91a6c4d791a75de8 ;;
  Linux/aarch64|Linux/arm64) platform=linux-arm64; sha=8ded44b58a0d9425a4178af26cf17693feac3b87bdcfef0a2a0898fcd1afc9f2 ;;
  *) echo "Unsupported platform: $(uname -s)/$(uname -m)" >&2; exit 1 ;;
esac
curl --fail --location --retry 3 --output "$directory/bazel" \
  "https://github.com/bazelbuild/bazelisk/releases/download/v${version}/bazelisk-${platform}"
echo "$sha  $directory/bazel" | sha256sum --check
chmod +x "$directory/bazel"
echo "$directory" >> "$GITHUB_PATH"

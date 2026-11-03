#!/usr/bin/env bash
set -euo pipefail
version=1.7.12
sha=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/trufflecel-actionlint.XXXXXX")
curl --fail --silent --show-error --location "https://github.com/rhysd/actionlint/releases/download/v$version/actionlint_${version}_linux_amd64.tar.gz" -o "$root/actionlint.tar.gz"
printf '%s  %s\n' "$sha" "$root/actionlint.tar.gz" | sha256sum --check --status
tar -xzf "$root/actionlint.tar.gz" -C "$root" actionlint
"$root/actionlint" .github/workflows/*.yml

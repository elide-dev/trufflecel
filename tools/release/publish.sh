#!/usr/bin/env bash
# Stage by default. --publish uploads an immutable release using Komodo's R2 remote.
set -euo pipefail
cd "$(dirname "$0")/../.."
version=${1:?usage: publish.sh VERSION [--publish]}
mode=${2:---stage}
[[ $# -le 2 && ( $mode == --stage || $mode == --publish ) ]] || { echo 'usage: publish.sh VERSION [--publish]' >&2; exit 1; }
python3 - "$version" <<'PY'
import sys
sys.path.insert(0, 'tools/release')
from maven_repo import validate_version
validate_version(sys.argv[1])
if 'SNAPSHOT' in sys.argv[1].upper():
    sys.exit('Use an explicit immutable release version, for example 0.1.0-alpha.1')
PY
if [[ $mode == --publish ]]; then
  python3 tools/release/dependency_gate.py --version "$version"
fi
bucket=${MAVEN_BUCKET:-elide:elide-maven}
public=https://maven.elide.dev
modules=(core protobuf json processor)
bazel=${BAZEL:-$(command -v bazelisk || command -v bazel)}
mkdir -p build
repo=$(mktemp -d "$PWD/build/release-repo.XXXXXX")
if [[ $mode == --publish ]]; then
  git rev-parse --verify HEAD > /dev/null
  [[ -z $(git status --porcelain) ]] || { echo 'Commit the release inputs before publishing.' >&2; exit 1; }
  # Listing failures (including auth/network failures) must never imply an absent version.
  artifacts=$(rclone lsf "$bucket/dev/elide/" --dirs-only)
  for module in "${modules[@]}"; do
    artifact=trufflecel-$module
    if printf '%s\n' "$artifacts" | grep -Fxq "$artifact/"; then
      versions=$(rclone lsf "$bucket/dev/elide/$artifact/" --dirs-only)
      if printf '%s\n' "$versions" | grep -Fxq "$version/"; then
        echo "$artifact:$version already exists; versions are immutable." >&2; exit 1
      fi
      # Existing artifacts must have valid metadata; don't silently lose history.
      mkdir -p "$repo/dev/elide/$artifact"
      rclone copyto "$bucket/dev/elide/$artifact/maven-metadata.xml" "$repo/dev/elide/$artifact/maven-metadata.xml"
    fi
  done
fi
"$bazel" test //... "--//tools/release:version=$version"
"$bazel" build //tools/release:publications "--//tools/release:version=$version"
# Resolve the physical configuration path; another Bazel command may change bazel-bin.
release_bin=$("$bazel" info bazel-bin "--//tools/release:version=$version")
python3 tools/release/maven_repo.py "$release_bin/tools/release" "$repo" "$version"
echo "Staged Maven repository: $repo"
[[ $mode == --publish ]] || exit 0
# Run only one publisher at a time: R2/rclone has no multi-object transaction.
# Upload and verify all immutable payloads before making the version discoverable in metadata.
for module in "${modules[@]}"; do
  artifact=trufflecel-$module
  path=dev/elide/$artifact/$version
  rclone copy "$repo/$path" "$bucket/$path" --immutable --checksum
  rclone check "$repo/$path" "$bucket/$path" --one-way
  for suffix in .pom .jar -sources.jar; do
    file=$artifact-$version$suffix
    curl --fail --silent --show-error "$public/$path/$file" -o "$repo/public-check"
    cmp "$repo/$path/$file" "$repo/public-check"
  done
done
for module in "${modules[@]}"; do
  path=dev/elide/trufflecel-$module
  rclone copy "$repo/$path" "$bucket/$path" --include 'maven-metadata.xml*' --checksum
  rclone check "$repo/$path" "$bucket/$path" --include 'maven-metadata.xml*' --one-way
done
rm -f "$repo/public-check"
echo "Published dev.elide:trufflecel-{core,protobuf,json,processor}:$version at $public"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
  printf 'repository=%s\n' "$repo" >> "$GITHUB_OUTPUT"
fi

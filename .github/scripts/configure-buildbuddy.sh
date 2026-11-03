#!/usr/bin/env bash
# The repository defaults to Linux amd64 RBE. CI writes only the credential and role to untracked user.bazelrc
# (0600), removed at job end. Trusted same-repository CI gets the read-write key. Fork PRs have no secrets and
# explicitly use the local profile on a hosted Linux runner instead.
set -euo pipefail
umask 077
# Also protect an existing file (umask alone only applies when creating it).
: > user.bazelrc
chmod 600 user.bazelrc
if [ -z "${BUILDBUDDY_API_KEY:-}" ]; then
  printf 'common --config=local\n' > user.bazelrc
  echo "No BuildBuddy key: explicit local profile (fork / uncredentialed CI)"
  exit 0
fi
printf 'common --remote_header=x-buildbuddy-api-key=%s\n' "$BUILDBUDDY_API_KEY" > user.bazelrc
printf 'common --config=ci\n' >> user.bazelrc
# Bazel attaches this to each invocation in BuildBuddy, before the next command overwrites it.
# Keep credentials out of the execution log: they are RPC headers, never action environment variables.
if [ -n "${RUNNER_TEMP:-}" ]; then
  printf 'build --execution_log_compact_file=%s/trufflecel-execution.zst\n' "$RUNNER_TEMP" >> user.bazelrc
fi
echo "BuildBuddy RBE enabled"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "rbe=true" >> "$GITHUB_OUTPUT"
fi

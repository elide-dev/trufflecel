#!/usr/bin/env bash
# Volatile metadata does not invalidate action keys. Never emit credentials in remote URLs.
set -eo pipefail
repo_url=$(git config --get remote.origin.url || true)
repo_url=$(printf '%s' "$repo_url" | sed -E 's#(https?://)[^/@]*@#\1#')
printf 'REPO_URL %s\n' "$repo_url"
printf 'COMMIT_SHA %s\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
printf 'GIT_BRANCH %s\n' "${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-$(git branch --show-current)}}"
if [ -z "$(git status --porcelain)" ]; then echo 'GIT_TREE_STATUS Clean'; else echo 'GIT_TREE_STATUS Modified'; fi

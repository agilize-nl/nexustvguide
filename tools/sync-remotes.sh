#!/usr/bin/env bash
# Synchroniseer main van deze checkout naar GitHub (leidend) en Forgejo (spiegel).
# Dit script gebruikt nooit force-pushes en stopt bij afwijkende historie.

set -euo pipefail

readonly PRIMARY_REMOTE="github"
readonly MIRROR_REMOTE="origin"
readonly BRANCH="main"

usage() {
  cat <<'EOF'
Gebruik: ./tools/sync-remotes.sh [--check]

Zonder opties pusht het script de lokale main eerst naar GitHub en daarna naar Forgejo.
Met --check worden alleen de versies vergeleken; er wordt niets gepusht.
EOF
}

case "${1:-}" in
  "") ;;
  --check) check_only=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

git rev-parse --is-inside-work-tree >/dev/null

current_branch="$(git branch --show-current)"
if [[ "$current_branch" != "$BRANCH" ]]; then
  echo "Stop: dit script mag alleen vanaf '$BRANCH' draaien (huidig: '${current_branch:-detached HEAD}')." >&2
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Stop: commit of stash eerst je lokale wijzigingen." >&2
  exit 1
fi

git fetch --quiet "$PRIMARY_REMOTE" "$BRANCH"
git fetch --quiet "$MIRROR_REMOTE" "$BRANCH"

local_ref="HEAD"
primary_ref="refs/remotes/$PRIMARY_REMOTE/$BRANCH"
mirror_ref="refs/remotes/$MIRROR_REMOTE/$BRANCH"

assert_no_divergence() {
  local remote_name="$1"
  local remote_ref="$2"

  if git merge-base --is-ancestor "$local_ref" "$remote_ref" &&
     ! git merge-base --is-ancestor "$remote_ref" "$local_ref"; then
    echo "Stop: $remote_name/$BRANCH bevat nieuwere commits. Voer eerst 'git pull --ff-only $PRIMARY_REMOTE $BRANCH' uit." >&2
    exit 1
  fi

  if ! git merge-base --is-ancestor "$remote_ref" "$local_ref"; then
    echo "Stop: lokale $BRANCH en $remote_name/$BRANCH zijn uiteen gelopen. Los de historie eerst handmatig op." >&2
    exit 1
  fi
}

assert_no_divergence "$PRIMARY_REMOTE" "$primary_ref"
assert_no_divergence "$MIRROR_REMOTE" "$mirror_ref"

local_sha="$(git rev-parse --short=12 "$local_ref")"
primary_sha="$(git rev-parse --short=12 "$primary_ref")"
mirror_sha="$(git rev-parse --short=12 "$mirror_ref")"

echo "Lokale $BRANCH : $local_sha"
echo "GitHub $BRANCH : $primary_sha"
echo "Forgejo $BRANCH: $mirror_sha"

if [[ "${check_only:-false}" == true ]]; then
  exit 0
fi

git push "$PRIMARY_REMOTE" "HEAD:refs/heads/$BRANCH"
git push "$MIRROR_REMOTE" "HEAD:refs/heads/$BRANCH"

echo "Klaar: GitHub en Forgejo staan op $(git rev-parse --short=12 HEAD)."

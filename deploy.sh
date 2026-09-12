#!/usr/bin/env bash
# Publiceer één NexusTVGuide-release naar GitHub én de LAN-updateserver (.171).
#
# Gebruik:
#   ./deploy.sh [release notes]
#
# Het script verhoogt altijd VERSION_CODE en de patchversie, commit alle huidige
# wijzigingen als releasecommit, pusht die commit naar GitHub en publiceert daarna
# de ondertekende APK en version.json naar de server op .171.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly VERSION_FILE="$ROOT_DIR/android/app/version.properties"
readonly GITHUB_REMOTE="github"
readonly BRANCH="main"
readonly UPDATE_MANIFEST_URL="http://192.168.2.171:3000/api/v1/app/version"

usage() {
    cat <<'EOF'
Gebruik: ./deploy.sh [release notes]

Verhoogt altijd VERSION_CODE en de patchversie, commit en push naar GitHub,
bouwt een ondertekende release en publiceert die naar de updateserver op .171.
EOF
}

case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
esac

release_notes="${*:-Onderhoudsupdate en prestatieverbeteringen.}"

fail() {
    echo "FOUT: $*" >&2
    exit 1
}

cd "$ROOT_DIR"

command -v git >/dev/null || fail "git ontbreekt."
command -v node >/dev/null || fail "node ontbreekt."
command -v curl >/dev/null || fail "curl ontbreekt."
[[ -f "$VERSION_FILE" ]] || fail "Versiebestand ontbreekt: $VERSION_FILE"
git rev-parse --is-inside-work-tree >/dev/null || fail "Geen git-repository."
[[ "$(git branch --show-current)" == "$BRANCH" ]] || fail "Deploy alleen vanaf '$BRANCH'."
git remote get-url "$GITHUB_REMOTE" >/dev/null || fail "GitHub-remote '$GITHUB_REMOTE' ontbreekt."

# Voorkom dat een releasecommit bovenop een inmiddels gewijzigde GitHub-main wordt gemaakt.
git fetch --quiet "$GITHUB_REMOTE" "$BRANCH"
if ! git merge-base --is-ancestor "refs/remotes/$GITHUB_REMOTE/$BRANCH" HEAD; then
    fail "Lokale main loopt niet lineair vanaf GitHub; synchroniseer eerst met GitHub."
fi

current_code="$(awk -F= '$1 == "VERSION_CODE" { print $2 }' "$VERSION_FILE")"
current_name="$(awk -F= '$1 == "VERSION_NAME" { print $2 }' "$VERSION_FILE")"
[[ "$current_code" =~ ^[1-9][0-9]*$ ]] || fail "Ongeldige VERSION_CODE in $VERSION_FILE: '$current_code'"
[[ "$current_name" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || fail "Ongeldige VERSION_NAME in $VERSION_FILE: '$current_name'"

next_code=$((current_code + 1))
next_name="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"

printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$next_code" "$next_name" > "$VERSION_FILE"
echo "Versie verhoogd: $current_name ($current_code) -> $next_name ($next_code)"

# Een release vertegenwoordigt de volledige werkboom, inclusief de versiebump.
git add --all
git commit -m "chore: release $next_name"
git push "$GITHUB_REMOTE" "HEAD:refs/heads/$BRANCH"
echo "GitHub staat op $(git rev-parse --short=12 HEAD)."

# publish-release bouwt uitsluitend een gesigneerde release-APK, valideert package,
# versie en handtekening, en uploadt APK plus manifest atomair naar .171.
node "$ROOT_DIR/tools/publish-release.mjs" --notes "$release_notes"

# De publisher bewaart een lokale release wanneer SCP faalt. Een succesvolle deploy mag
# echter pas worden gemeld wanneer de actieve servermetadata de zojuist gebouwde versie toont.
remote_manifest="$(curl --fail --silent --show-error --connect-timeout 10 --max-time 30 "$UPDATE_MANIFEST_URL")" \
    || fail "De updateserver op .171 is niet bereikbaar na publicatie."

EXPECTED_VERSION_CODE="$next_code" \
EXPECTED_VERSION_NAME="$next_name" \
REMOTE_MANIFEST="$remote_manifest" \
node --input-type=module <<'NODE'
const expectedCode = Number(process.env.EXPECTED_VERSION_CODE);
const expectedName = process.env.EXPECTED_VERSION_NAME;
let manifest;

try {
  manifest = JSON.parse(process.env.REMOTE_MANIFEST);
} catch {
  console.error('FOUT: .171 retourneerde geen geldige version.json.');
  process.exit(1);
}

if (manifest.versionCode !== expectedCode || manifest.versionName !== expectedName) {
  console.error(
    `FOUT: .171 serveert ${manifest.versionName ?? '?'} (${manifest.versionCode ?? '?'}) ` +
    `in plaats van ${expectedName} (${expectedCode}).`
  );
  process.exit(1);
}
NODE

echo "Deploy voltooid: GitHub en .171 publiceren NexusTVGuide $next_name ($next_code)."

#!/usr/bin/env bash
# One-time setup of the release signing key. Run it yourself, on a machine you
# trust: the passwords are generated here, written only to the key directory
# and to GitHub Actions secrets, and never printed.
#
#   bash scripts/setup_release_signing.sh [--repo OWNER/NAME] [--dir DIR] [--no-upload]
#
# It creates DIR/openime-release.jks (default ~/.openime-release) and uploads the four
# secrets that .github/workflows/release.yml reads:
#   OPENIME_KEYSTORE_B64  OPENIME_KEYSTORE_PASSWORD  OPENIME_KEY_ALIAS  OPENIME_KEY_PASSWORD
#
# THE KEY IS THE APP'S PERMANENT IDENTITY. Android only updates an app in place
# when the new APK is signed by the same key, so a lost key means every user has
# to uninstall. Back DIR up (offline copy plus a password manager) before the
# first release. Nothing in this repository can recover it.
set -euo pipefail
umask 077

REPO=""
DIR="${OPENIME_KEY_DIR:-$HOME/.openime-release}"
UPLOAD=1
FORCE=0
ALIAS="openime"
DNAME="${OPENIME_DNAME:-CN=openIME, O=Slacker LLC}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --dir) DIR="$2"; shift 2 ;;
    --no-upload) UPLOAD=0; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "setup_release_signing: $*" >&2; exit 1; }

command -v keytool >/dev/null || die "keytool (JDK 17) not found"
if [[ "$UPLOAD" == "1" ]]; then
  command -v gh >/dev/null || die "gh (GitHub CLI) not found; install it or use --no-upload"
  gh auth status >/dev/null 2>&1 || die "run 'gh auth login' first"
  [[ -n "$REPO" ]] || REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi

KEYSTORE="$DIR/openime-release.jks"
[[ ! -e "$KEYSTORE" ]] || die "$KEYSTORE already exists; refusing to overwrite a signing key"

if [[ "$UPLOAD" == "1" ]]; then
  existing="$(gh secret list --repo "$REPO" --json name --jq '.[].name' | grep -c '^OPENIME_KEY' || true)"
  if [[ "$existing" -gt 0 && "$FORCE" != "1" ]]; then
    die "$REPO already has OPENIME_* secrets. Replacing the key after a release breaks in-place updates; pass --force only if no release has shipped."
  fi
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
# `|| true`: head closes the pipe early and pipefail would otherwise abort the script.
PASSWORD="$(head -c 96 /dev/urandom | base64 -w0 | tr -dc 'A-Za-z0-9' | head -c 40 || true)"
[[ "${#PASSWORD}" -eq 40 ]] || die "could not generate a password"

# PKCS12 uses one password for the store and the key.
if ! output="$(keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "$DNAME" 2>&1)"; then
  rm -f "$KEYSTORE"
  die "keytool failed: ${output//$PASSWORD/***}"
fi
chmod 600 "$KEYSTORE"

cat > "$DIR/credentials.txt" <<EOF
keystore:  $KEYSTORE
alias:     $ALIAS
password:  $PASSWORD   (store and key password are the same)
created:   $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
chmod 600 "$DIR/credentials.txt"

FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$PASSWORD" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1 | tr -d ':' | tr 'A-F' 'a-f')"

if [[ "$UPLOAD" == "1" ]]; then
  base64 -w0 "$KEYSTORE" | gh secret set OPENIME_KEYSTORE_B64 --repo "$REPO"
  printf '%s' "$PASSWORD" | gh secret set OPENIME_KEYSTORE_PASSWORD --repo "$REPO"
  printf '%s' "$ALIAS" | gh secret set OPENIME_KEY_ALIAS --repo "$REPO"
  printf '%s' "$PASSWORD" | gh secret set OPENIME_KEY_PASSWORD --repo "$REPO"
  echo "Uploaded 4 secrets to $REPO."
else
  echo "Skipped upload (--no-upload). Set the four secrets yourself; see docs/RELEASE.md."
fi

cat <<EOF

Signing key created.

  keystore     $KEYSTORE
  credentials  $DIR/credentials.txt
  SHA-256      $FINGERPRINT

Next:
  1. BACK UP $DIR now (offline copy + password manager). It cannot be recreated.
  2. After the first release, put this fingerprint in docs/release-cert.sha256 so every
     later release is checked against it:
         $FINGERPRINT
  3. Tag the release (docs/RELEASE.md, "发布步骤").
EOF

#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
deps_root="$repo_root/app/src/main/cpp/vendor/librime/deps"
mkdir -p "$deps_root"

fetch_dep() {
  local name="$1"
  local repo="$2"
  local sha="$3"
  local destination="$deps_root/$name"
  local marker="$destination/.openime-source-sha"

  if [[ -f "$marker" ]] && [[ "$(cat "$marker")" == "$sha" ]]; then
    echo "librime dependency $name already at $sha"
    return
  fi

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  echo "Fetching $repo@$sha"
  curl --fail --location --retry 3 --retry-delay 2 \
    "https://github.com/$repo/archive/$sha.tar.gz" \
    --output "$tmp/source.tar.gz"

  rm -rf "$destination"
  mkdir -p "$destination"
  tar -xzf "$tmp/source.tar.gz" --strip-components=1 -C "$destination"
  printf '%s\n' "$sha" > "$marker"

  rm -rf "$tmp"
  trap - RETURN
}

# These revisions are the gitlinks from upstream librime 1.17.0
# (commit 33e78140250125871856cdc5b42ddc6a5fcd3cd4).
fetch_dep "glog" "google/glog" "7b134a5c82c0c0b5698bb6bf7a835b230c5638e4"
fetch_dep "leveldb" "google/leveldb" "99b3c03b3284f5886f9ef9a4ef703d57373e61be"
fetch_dep "marisa-trie" "s-yata/marisa-trie" "3e87d53b78e15f2f43783d5e376561a8c9722051"
fetch_dep "yaml-cpp" "jbeder/yaml-cpp" "2f86d13775d119edbb69af52e5f566fd65c6953b"

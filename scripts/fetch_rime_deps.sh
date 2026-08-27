#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rime_root="$repo_root/app/src/main/cpp/vendor/librime"
deps_root="$rime_root/deps"
include_root="$rime_root/include"
RIME_UPSTREAM_SHA="33e78140250125871856cdc5b42ddc6a5fcd3cd4"
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

restore_librime_headers() {
  local marker="$include_root/.openime-source-sha"
  local required=(
    "COPYING.darts-clone"
    "darts.h"
    "utf8.h"
    "utf8/checked.h"
    "utf8/core.h"
    "utf8/cpp17.h"
    "utf8/unchecked.h"
    "X11/keysym.h"
    "X11/keysymdef.h"
  )

  local complete=true
  if [[ ! -f "$marker" ]] || [[ "$(cat "$marker")" != "$RIME_UPSTREAM_SHA" ]]; then
    complete=false
  else
    local path
    for path in "${required[@]}"; do
      if [[ ! -f "$include_root/$path" ]]; then
        complete=false
        break
      fi
    done
  fi

  if [[ "$complete" == true ]]; then
    echo "librime headers already restored from $RIME_UPSTREAM_SHA"
    return
  fi

  echo "Restoring librime include/ from upstream $RIME_UPSTREAM_SHA"
  rm -rf "$include_root"
  local path
  for path in "${required[@]}"; do
    mkdir -p "$(dirname "$include_root/$path")"
    curl --fail --location --retry 3 --retry-delay 2 \
      "https://raw.githubusercontent.com/rime/librime/$RIME_UPSTREAM_SHA/include/$path" \
      --output "$include_root/$path"
  done
  printf '%s\n' "$RIME_UPSTREAM_SHA" > "$marker"
}

# These revisions are the gitlinks from upstream librime 1.17.0.
fetch_dep "glog" "google/glog" "7b134a5c82c0c0b5698bb6bf7a835b230c5638e4"
fetch_dep "leveldb" "google/leveldb" "99b3c03b3284f5886f9ef9a4ef703d57373e61be"
fetch_dep "marisa-trie" "s-yata/marisa-trie" "3e87d53b78e15f2f43783d5e376561a8c9722051"
fetch_dep "yaml-cpp" "jbeder/yaml-cpp" "2f86d13775d119edbb69af52e5f566fd65c6953b"

# The vendored source intentionally omits ignored generated/vendor directories.
# Restore the exact headers shipped by the same librime release instead of
# borrowing host headers or floating third-party revisions.
restore_librime_headers

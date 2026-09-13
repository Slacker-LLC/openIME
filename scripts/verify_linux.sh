#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ $# -gt 1 ]]; then
  printf 'Usage: bash scripts/verify_linux.sh [device-serial]\n' >&2
  exit 2
fi

if head -c 100 app/libs/sherpa-onnx-1.13.6.aar | grep -q 'git-lfs.github.com/spec'; then
  printf 'Missing LFS files. Run git lfs install --local && git lfs pull first.\n' >&2
  exit 1
fi
if [[ ! -f app/src/main/cpp/vendor/librime/deps/glog/CMakeLists.txt ||
      ! -f app/src/main/cpp/vendor/librime/include/darts.h ]]; then
  printf 'Missing native sources. Run bash scripts/fetch_rime_deps.sh first.\n' >&2
  exit 1
fi

bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if [[ $# -eq 1 ]]; then
  ANDROID_SERIAL="$1" bash gradlew :app:connectedDebugAndroidTest --console=plain
fi

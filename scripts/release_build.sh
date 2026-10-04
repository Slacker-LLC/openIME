#!/usr/bin/env bash
# Build and verify the signed arm64 release APK. The release workflow and a
# maintainer's machine run exactly this, so the pipeline can be rehearsed locally.
#
# Required environment (see docs/RELEASE.md):
#   OPENIME_KEYSTORE_PATH  OPENIME_KEYSTORE_PASSWORD  OPENIME_KEY_ALIAS  OPENIME_KEY_PASSWORD
# Optional:
#   OPENIME_RELEASE_TAG    tag being released; must equal v<VERSION>
#   OPENIME_REHEARSAL=1    the keystore is a throwaway: skip the certificate continuity check
#   OPENIME_SKIP_TESTS=1   skip :app:testDebugUnitTest (quick local runs only)
#   OPENIME_OUT_DIR        output directory (default: build/release-files)
#   OPENIME_GRADLE_ARGS    extra Gradle arguments, e.g. --offline
#   ANDROID_HOME           SDK containing build-tools/35.0.0 (apksigner, aapt2)
#
# Writes to OPENIME_OUT_DIR: openIME-v<VERSION>-arm64-release.apk, RELEASE_NOTES.md,
# apksigner.txt, and SHA256SUMS.txt for the workflow's own check. Only the APK
# is attached to the GitHub Release; its checksum is printed in the notes.
# Also appends apk=, version= and cert_sha256= to $GITHUB_OUTPUT when it is set.
set -euo pipefail
export PYTHONUTF8=1

cd "$(dirname "$0")/.."

die() { echo "release_build: $*" >&2; exit 1; }

# mapfile and empty-array expansion under `set -u` need bash 4.4+ (macOS ships 3.2).
if (( BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
  die "bash 4.4 or newer is required (found $BASH_VERSION)"
fi

for name in OPENIME_KEYSTORE_PATH OPENIME_KEYSTORE_PASSWORD OPENIME_KEY_ALIAS OPENIME_KEY_PASSWORD; do
  [[ -n "${!name:-}" ]] || die "missing $name"
done
[[ -f "$OPENIME_KEYSTORE_PATH" ]] || die "keystore not found: $OPENIME_KEYSTORE_PATH"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="$SDK/build-tools/${OPENIME_BUILD_TOOLS:-35.0.0}"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[[ -x "$APKSIGNER" && -x "$AAPT2" ]] || die "apksigner/aapt2 not found in $BUILD_TOOLS"

OUT="${OPENIME_OUT_DIR:-build/release-files}"
rm -rf "$OUT"
mkdir -p "$OUT"

# 1. Version, changelog and (when releasing) tag agree.
check_args=(check)
[[ -n "${OPENIME_RELEASE_TAG:-}" ]] && check_args+=(--tag "$OPENIME_RELEASE_TAG")
python3 scripts/release_check.py "${check_args[@]}"
VERSION="$(python3 scripts/release_check.py version | cut -d' ' -f1)"

# 2. Build. lintRelease runs here because pull-request CI only lints the debug variant.
tasks=(:app:lintRelease :app:assembleRelease)
[[ "${OPENIME_SKIP_TESTS:-0}" == "1" ]] || tasks=(:app:testDebugUnitTest "${tasks[@]}")
read -r -a extra_gradle_args <<< "${OPENIME_GRADLE_ARGS:-}"
./gradlew "${tasks[@]}" --no-daemon --console=plain "${extra_gradle_args[@]}"

APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name 'app-release*.apk' | head -n 1)"
[[ -n "$APK" && -f "$APK" ]] || die "release APK not found (was signing configured?)"

# 3. The APK is signed with the release key and nothing else.
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee "$OUT/apksigner.txt"
grep -Eq 'Verified using v[23][^:]*: true' "$OUT/apksigner.txt" || die "no v2/v3 signature"
grep -q 'CN=Android Debug' "$OUT/apksigner.txt" && die "APK is signed with the Android debug certificate"
CERT_SHA256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/apksigner.txt" | head -n 1)"
[[ -n "$CERT_SHA256" ]] || die "could not read the signing certificate digest"

# 4. arm64-v8a only.
mapfile -t ABIS < <(unzip -Z1 "$APK" | awk -F/ '/^lib\/[^/]+\/[^/]+$/ {print $2}' | sort -u)
if [[ "${#ABIS[@]}" -ne 1 || "${ABIS[0]}" != "arm64-v8a" ]]; then
  die "release APK must contain only arm64-v8a, found: ${ABIS[*]:-none}"
fi

# 5. The identity baked into the APK is the one in VERSION.
python3 scripts/release_check.py apk "$APK" --aapt2 "$AAPT2"

# 6. Every release must be signed by the same key, or nobody can update in place.
#    docs/release-cert.sha256 holds the digest; it is recorded after the first release.
if [[ "${OPENIME_REHEARSAL:-0}" != "1" ]]; then
  EXPECTED=""
  if [[ -f docs/release-cert.sha256 ]]; then
    EXPECTED="$(tr -d '[:space:]:' < docs/release-cert.sha256 | tr 'A-F' 'a-f')"
  fi
  ACTUAL="$(printf '%s' "$CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')"
  if [[ -z "$EXPECTED" || "$EXPECTED" == "unset" ]]; then
    echo "::notice title=First release::Record the signing certificate in docs/release-cert.sha256: $ACTUAL"
  elif [[ "$EXPECTED" != "$ACTUAL" ]]; then
    die "signing certificate $ACTUAL does not match docs/release-cert.sha256 ($EXPECTED); a different key would break in-place updates"
  fi
fi

# 7. Release files.
NAME="openIME-v${VERSION}-arm64-release.apk"
cp "$APK" "$OUT/$NAME"
(cd "$OUT" && sha256sum "$NAME" > SHA256SUMS.txt)
APK_SHA256="$(cut -d' ' -f1 "$OUT/SHA256SUMS.txt")"
REPO_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-Slacker-LLC/openIME}"

CHANNEL="$(python3 scripts/release_check.py channel)"
if [[ "$CHANNEL" == "beta" ]]; then
  CHANNEL_NOTE="> **Beta 测试版。** 功能完整度和稳定性还在验证中，请不要作为日常唯一输入法；遇到问题请到 Issues 反馈。"
  PACKAGE_NOTE="固定签名的 arm64-v8a 测试包"
else
  CHANNEL_NOTE=""
  PACKAGE_NOTE="固定签名的 arm64-v8a 正式包"
fi

{
  [[ -n "$CHANNEL_NOTE" ]] && printf '%s\n\n' "$CHANNEL_NOTE"
  python3 scripts/release_check.py notes
  cat <<EOF

---

### 下载与校验

- \`$NAME\`：$PACKAGE_NOTE。这是本次发布唯一的附件。
- APK SHA-256：\`$APK_SHA256\`。下载后执行 \`sha256sum $NAME\`，结果一致再安装。
- 第三方组件的许可证随 APK 一起打包；清单见仓库中的 [THIRD_PARTY_NOTICES.md]($REPO_URL/blob/v$VERSION/THIRD_PARTY_NOTICES.md)。

签名证书 SHA-256：\`$(printf '%s' "$CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')\`。每个版本都应一致，可用 \`apksigner verify --print-certs\` 核对。

### 已知限制

- 手写输入尚未接入识别引擎，入口默认隐藏；九键暂不支持与外接键盘同时使用。
- 首次安装后需要完成完整词典部署，期间候选质量略低。
- 发布包只含 arm64-v8a；模拟器请使用 Debug 构建。
- 系统不允许降级或换签名覆盖安装：安装此前版本号更高的包或 Debug 包时，需要先卸载（卸载前可在「设置 → 关于与数据 → 数据管理」导出用户数据）。
EOF
} > "$OUT/RELEASE_NOTES.md"

echo "release files in $OUT:"
ls -l "$OUT"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "apk=$NAME"
    echo "version=$VERSION"
    echo "cert_sha256=$CERT_SHA256"
    echo "out_dir=$OUT"
  } >> "$GITHUB_OUTPUT"
fi

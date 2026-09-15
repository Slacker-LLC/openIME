#!/usr/bin/env bash
# Settings retention across an in-place upgrade (bash port of
# scripts/upgrade_regression.ps1, which still targets the pre-rework theme
# pills). Uses the preferences the current build actually persists: the
# preferred Chinese layout, chosen through the live IME.
#
# Usage: bash scripts/upgrade_regression.sh [device-serial] [apk]
set -uo pipefail

SERIAL="${1:-}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
ADB="${ADB_BIN:-adb}"
PKG=llc.slacker.openime
TMP_DIR="${TMPDIR:-/tmp}/openime-upgrade-regression"

if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
[[ -n "$SERIAL" ]] || { printf 'No adb device found.\n' >&2; exit 2; }
[[ -f "$APK" ]] || { printf 'Missing APK: %s\n' "$APK" >&2; exit 2; }
mkdir -p "$TMP_DIR"

say() { printf '%s\n' "$*"; }
adb_do() { "$ADB" -s "$SERIAL" "$@"; }
send() {
  adb_do shell am broadcast -n "$PKG/.E2ETestReceiver" -a "$PKG.TEST_COMMAND" --es cmd "$1" >/dev/null 2>&1
  sleep 0.4
}
prefs() { adb_do shell run-as "$PKG" cat shared_prefs/ime_settings.xml 2>/dev/null | tr -d '\r'; }

adb_do shell settings put --user 0 secure default_input_method "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell settings put --user 0 secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1
adb_do shell ime enable --user 0 "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell ime set --user 0 "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
adb_do shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4

# Drive the live IME so the preference is written by the product, not by us.
send 'mode:PINYIN_9'
send 'mode:PINYIN_26'
send 'mode:PINYIN_9'
sleep 1
before="$(prefs)"
if grep -q '<string name="preferred_chinese_mode">PINYIN_9</string>' <<<"$before"; then
  say 'PASS preferred layout saved before upgrade (PINYIN_9)'
else
  say "FAIL preferred layout not saved before upgrade: $before"
  exit 1
fi

"$ADB" -s "$SERIAL" install -r -t "$APK" >/dev/null 2>&1 || { say 'FAIL reinstall'; exit 1; }
sleep 2
after="$(prefs)"
if grep -q '<string name="preferred_chinese_mode">PINYIN_9</string>' <<<"$after"; then
  say 'PASS preferred layout survived adb install -r (PINYIN_9)'
else
  say "FAIL preferred layout lost after install -r: $after"
  exit 1
fi
say "SUMMARY upgrade device=$SERIAL PASS"


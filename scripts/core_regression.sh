#!/usr/bin/env bash
# Core regression against the live IME service (bash port of the core steps in
# scripts/core_regression.ps1, so a Linux host or CI job can run them without
# PowerShell). The debug-only E2E receiver drives the real click listeners of
# LocalVoiceImeService and the assertions read the real activity_ime_test_lab
# test_input text back over uiautomator.
#
# Usage: bash scripts/core_regression.sh [device-serial] [apk]
#
# Requires the debug APK (it ships the E2E receiver and MainActivity test host).
set -uo pipefail

SERIAL="${1:-}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
ADB="${ADB_BIN:-adb}"
PKG=llc.slacker.openime
RECEIVER="${PKG}/.E2ETestReceiver"
ACTION="${PKG}.TEST_COMMAND"
TMP_DIR="${TMPDIR:-/tmp}/openime-core-regression"
PASS=0
FAIL=0

if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  printf 'No adb device found. Pass a serial: bash scripts/core_regression.sh <serial> [apk]\n' >&2
  exit 2
fi
if [[ ! -f "$APK" ]]; then
  printf 'Missing APK: %s (run ./gradlew :app:assembleDebug first)\n' "$APK" >&2
  exit 2
fi
mkdir -p "$TMP_DIR"

say() { printf '%s\n' "$*"; }
adb_do() { "$ADB" -s "$SERIAL" "$@"; }

wait_for_default_ime() {
  local target="$1" current attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
    current="$(adb_do shell settings get secure default_input_method | tr -d '\r')"
    if [[ "$current" == "$target" ]]; then
      return 0
    fi
    adb_do shell ime set --user 0 "$target" >/dev/null 2>&1 || true
    sleep 0.25
  done
  say "FAIL default IME not selected: expected=$target actual=$current" >&2
  return 1
}

node_field() {
  # node_field <xml-file> <resource-id> <attribute>
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
path, resource_id, attribute = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    raw = open(path, encoding='utf-8', errors='replace').read()
except OSError:
    print('')
    raise SystemExit
for tag in re.findall(r'<node\b[^>]*>', raw):
    fields = dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
    if fields.get('resource-id') == resource_id:
        print(fields.get(attribute, ''))
        break
else:
    print('')
PY
}

dump_ui() {
  adb_do shell uiautomator dump "/sdcard/$1" >/dev/null 2>&1
  adb_do shell cat "/sdcard/$1" 2>/dev/null > "$TMP_DIR/$1"
}

editor_text() {
  dump_ui ui.xml
  node_field "$TMP_DIR/ui.xml" "${PKG}:id/test_input" text
}

focus_editor() {
  local attempt x y bounds
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    dump_ui focus.xml
    bounds="$(node_field "$TMP_DIR/focus.xml" "${PKG}:id/test_input" bounds)"
    if [[ "$bounds" =~ \[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\] ]]; then
      x=$(( (${BASH_REMATCH[1]} + ${BASH_REMATCH[3]}) / 2 ))
      y=$(( (${BASH_REMATCH[2]} + ${BASH_REMATCH[4]}) / 2 ))
      adb_do shell input tap "$x" "$y" >/dev/null 2>&1
      return 0
    fi
    sleep 1
  done
  return 1
}

send() {
  adb_do shell am broadcast -n "$RECEIVER" -a "$ACTION" --es cmd "$1" >/dev/null 2>&1
  sleep 0.35
}
tap() { send "tap:$1"; }

last_state() {
  adb_do logcat -d -s OpenImeE2E:I 2>/dev/null | grep -a 'STATE' | tail -1
}

mode() {
  local want="$1" attempt line
  for attempt in 1 2 3 4 5 6 7 8; do
    adb_do logcat -c >/dev/null 2>&1
    send state
    line="$(last_state)"
    case "$line" in *"mode=$want"*) return 0 ;; esac
    send "mode:$want"
    sleep 0.2
    adb_do logcat -c >/dev/null 2>&1
    send state
    line="$(last_state)"
    case "$line" in *"mode=$want"*) return 0 ;; esac
    tap 'key:mode'
    sleep 0.3
  done
  return 1
}

start_real() {
  adb_do logcat -c >/dev/null 2>&1
  adb_do shell am force-stop "$PKG" >/dev/null 2>&1
  adb_do shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 3
  focus_editor || say 'WARN: test_input was not found for focus'
  sleep 1
}

check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    say "PASS $name -> [$actual]"
    PASS=$((PASS + 1))
  else
    say "FAIL $name expected=[$expected] actual=[$actual]"
    FAIL=$((FAIL + 1))
  fi
}

say "device=$SERIAL api=$(adb_do shell getprop ro.build.version.sdk | tr -d '\r') apk=$APK"
"$ADB" -s "$SERIAL" install -r -t "$APK" >/dev/null 2>&1 || { say 'FAIL apk install'; exit 1; }
adb_do shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1
adb_do shell settings put --user 0 secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1
adb_do shell settings put --user 0 secure enabled_input_methods "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell settings put --user 0 secure default_input_method "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell ime enable --user 0 "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
adb_do shell ime set --user 0 "$PKG/.LocalVoiceImeService" >/dev/null 2>&1
wait_for_default_ime "$PKG/.LocalVoiceImeService" || exit 1
sleep 1

start_real
mode PINYIN_26 || say 'WARN: PINYIN_26 not reached'
for key in n i h a o; do tap "$key"; done
tap candidate-first-row
check '020 26-key nihao -> 你好' '你好' "$(editor_text)"

start_real
mode PINYIN_26 || say 'WARN: PINYIN_26 not reached'
for c in w o x i a n g c h i f a n; do tap "$c"; done
tap key-space
check '021 continuous Pinyin + space -> 我想吃饭' '我想吃饭' "$(editor_text)"

start_real
mode PINYIN_26 || say 'WARN: PINYIN_26 not reached'
for key in n i h a o; do tap "$key"; done
tap key-backspace
check '022 composition backspace -> niha' 'niha' "$(editor_text)"

start_real
mode PINYIN_26 || say 'WARN: PINYIN_26 not reached'
for key in n i h a o; do tap "$key"; done
tap candidate-first-row
tap key-backspace
check '023 committed backspace -> 你' '你' "$(editor_text)"

start_real
mode ENGLISH_26 || say 'WARN: ENGLISH_26 not reached'
for c in o p e n i m e; do tap "$c"; done
check '025 English 26 path -> openime' 'openime' "$(editor_text)"

start_real
mode PINYIN_9 || say 'WARN: PINYIN_9 not reached'
for key in 6 4 4 2 6; do tap "$key"; done
tap '确定'
check '030 nine-key 64426 -> 你好' '你好' "$(editor_text)"

start_real
mode DIGITS || say 'WARN: DIGITS not reached'
for key in 1 2 3; do tap "$key"; done
check '050 digits 123 -> 123' '123' "$(editor_text)"

say "SUMMARY device=$SERIAL pass=$PASS fail=$FAIL"
[[ $FAIL -eq 0 ]]

#!/usr/bin/env bash
# Renders the voice panel's character on a device and collects the pictures.
#
# VoiceFaceRenderInstrumentedTest paints one state of a voice session per
# iteration onto a bitmap, asserts the pixels it left, and writes a PNG of it
# into the app's external files directory. This script runs that one test class
# on the device and pulls the pictures into build/voice-face/, which is how the
# artwork is looked at by eye: the character is drawn with android.graphics, so
# no JVM test can show what it looks like.
#
# Usage: bash scripts/voice_face_capture.sh [device-serial]
#
# The serial defaults to the first device adb reports.
set -uo pipefail

SERIAL="${1:-}"
ADB="${ADB_BIN:-adb}"
PKG=llc.slacker.openime
CLASS="${PKG}.VoiceFaceRenderInstrumentedTest"
OUT=build/voice-face
REMOTE="/sdcard/Android/data/${PKG}/files/Pictures/voice-face"

if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  printf 'No adb device found. Pass a serial: bash scripts/voice_face_capture.sh <serial>\n' >&2
  exit 2
fi

printf 'Rendering the voice face on %s\n' "$SERIAL"
ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$CLASS" \
  --console=plain || exit $?

rm -rf "$OUT"
mkdir -p "$OUT"
"$ADB" -s "$SERIAL" pull "$REMOTE/." "$OUT/" >/dev/null 2>&1

count=$(find "$OUT" -name '*.png' | wc -l)
if [[ "$count" -eq 0 ]]; then
  printf 'No pictures came back from %s; the test wrote none.\n' "$REMOTE" >&2
  exit 1
fi
printf '%s pictures in %s:\n' "$count" "$OUT"
ls "$OUT"/*.png | sed 's|^|  |'

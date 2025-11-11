#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH" >&2
  exit 1
fi

if ! command -v aapt >/dev/null 2>&1; then
  echo "aapt is not available in PATH" >&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
APK_PATH="${SCRIPT_DIR%/tools}/mobile/build/outputs/apk/debug/mobile-debug.apk"

run() {
  echo "[+] $1"
  eval "$1"
  echo
}

run "adb shell dumpsys package dev.pointtosky.wear   | grep -i exported || true"
run "adb shell dumpsys package dev.pointtosky.mobile | grep -i exported || true"

if [[ -f "${APK_PATH}" ]]; then
  run "aapt dump badging \"${APK_PATH}\" | grep -i provider || true"
else
  cat <<MSG
[!] ${APK_PATH} not found.
    Build it first with: ./gradlew :mobile:assembleDebug
MSG
fi

cat <<'MSG'
[INFO] Expected FileProvider authority: content://dev.pointtosky.mobile.logs/crash_logs/<file>.zip
       Use `adb shell content read --uri ...` to confirm Permission Denial without a grant.
       After sharing, run `adb shell dumpsys package <target.pkg> | grep dev.pointtosky.mobile.logs` to inspect granted URIs.
MSG

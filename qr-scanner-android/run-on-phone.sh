#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo
echo "=== QR Scan — install and run on phone ==="
echo

if [[ -d "/d/android-build/sdk" ]]; then
  SDK_DIR="/d/android-build/sdk"
  SDK_DIR_PROP="D:\\\\android-build\\\\sdk"
elif [[ -d "${LOCALAPPDATA:-}/Android/Sdk" ]]; then
  SDK_DIR="${LOCALAPPDATA}/Android/Sdk"
  SDK_DIR_PROP="$(printf '%s' "$SDK_DIR" | sed 's#\\#\\\\#g')"
else
  echo "Android SDK not found."
  exit 1
fi

if [[ -d "/d/android-build/jdk/jdk-17.0.20.1+1" ]]; then
  export JAVA_HOME="/d/android-build/jdk/jdk-17.0.20.1+1"
fi
if [[ -d "/d/android-build/gradle-home" ]]; then
  export GRADLE_USER_HOME="/d/android-build/gradle-home"
fi

ADB="$SDK_DIR/platform-tools/adb.exe"
if [[ ! -x "$ADB" && ! -f "$ADB" ]]; then
  ADB="$SDK_DIR/platform-tools/adb"
fi

if [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$SDK_DIR_PROP" > local.properties
fi

echo "Waiting for a phone..."
"$ADB" start-server >/dev/null
"$ADB" devices
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo
  echo "No phone found. Plug it in over USB, then on the phone:"
  echo "  1) Settings -> About phone -> tap Build number 7 times"
  echo "  2) Settings -> Developer options -> USB debugging ON"
  echo "  3) Unlock the phone and tap Allow when it asks"
  echo "  4) Run this again"
  echo
  echo "Wireless later:  adb tcpip 5555   then   adb connect PHONE-IP:5555"
  echo
  exit 1
fi

echo "Building and installing..."
./gradlew.bat :app:installDebug
echo "Opening QR Scan..."
"$ADB" shell am start -n com.sumo.qrscanner/.MainActivity
echo
echo "Phone is running the debug build. Change code, then run this again."
echo "There is no Flutter-style hot reload — each run rebuilds only what changed."
echo

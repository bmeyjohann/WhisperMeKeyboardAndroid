#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${WSL_ANDROID_SDK_DIR:-/tmp/android-sdk-whisperme}"
TOOLS_ZIP="${SDK_ROOT}/cmdline-tools.zip"
TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
COMPILE_SDK="$(sed -n 's/^projectCompileSdk=//p' "${ROOT_DIR}/gradle.properties" | tr -d '\r')"
BUILD_TOOLS="$(sed -n 's/^projectBuildToolsVersion=//p' "${ROOT_DIR}/gradle.properties" | tr -d '\r')"
NDK_VERSION="$(sed -n 's/^projectNdkVersion=//p' "${ROOT_DIR}/gradle.properties" | tr -d '\r')"
: "${COMPILE_SDK:?Missing projectCompileSdk}" "${BUILD_TOOLS:?Missing projectBuildToolsVersion}" "${NDK_VERSION:?Missing projectNdkVersion}"

mkdir -p "${SDK_ROOT}"

if [[ ! -x "${SDKMANAGER}" ]]; then
  echo "Installing Android command-line tools into ${SDK_ROOT}..."
  curl -fL "${TOOLS_URL}" -o "${TOOLS_ZIP}"
  mkdir -p "${SDK_ROOT}/cmdline-tools"
  rm -rf "${SDK_ROOT}/cmdline-tools/latest" "${SDK_ROOT}/cmdline-tools/cmdline-tools"
  unzip -q "${TOOLS_ZIP}" -d "${SDK_ROOT}/cmdline-tools"
  mv "${SDK_ROOT}/cmdline-tools/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
fi

echo "Installing required SDK packages; review any new license terms explicitly."
"${SDKMANAGER}" --sdk_root="${SDK_ROOT}" \
  "platform-tools" \
  "platforms;android-${COMPILE_SDK}" \
  "build-tools;${BUILD_TOOLS}" \
  "ndk;${NDK_VERSION}"

echo "WSL Android SDK ready at: ${SDK_ROOT}"
echo "Next: run ./scripts/gradlew-wsl.sh :app:assembleDebug :app:testDebugUnitTest"

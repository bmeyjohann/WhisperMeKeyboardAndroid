#!/usr/bin/env bash
set -euo pipefail

SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WSL_SDK_DIR="${WSL_ANDROID_SDK_DIR:-/tmp/android-sdk-whisperme}"
WORK_ROOT="${WHISPERME_WSL_BUILD_ROOT:-/tmp/whisperme-android-compile}"
WORKSPACE_DIR="${WORK_ROOT}/workspace"
GRADLE_HOME_DIR="${WHISPERME_WSL_GRADLE_HOME:-/tmp/gradle-whisperme}"
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
OFFLINE_MODE="${WHISPERME_WSL_OFFLINE:-1}"

if [[ $# -gt 0 ]]; then
  echo "This command is locked and does not accept task arguments."
  echo "It always runs: :app:compileReleaseTestKotlin"
  exit 1
fi

if [[ ! -d "${WSL_SDK_DIR}" ]]; then
  echo "WSL SDK not found at ${WSL_SDK_DIR}"
  echo "Run: ./scripts/setup-wsl-android-sdk.sh"
  exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "rsync is required for safe compile copy."
  exit 1
fi

mkdir -p "${WORK_ROOT}" "${GRADLE_HOME_DIR}" "${WORKSPACE_DIR}"

echo "Syncing project into disposable workspace: ${WORKSPACE_DIR}"
rsync -a --delete \
  --exclude ".git" \
  --exclude ".gradle" \
  --exclude ".idea" \
  --exclude "build" \
  --exclude "**/build" \
  --exclude ".cxx" \
  --exclude "**/.cxx" \
  --exclude ".externalNativeBuild" \
  --exclude "**/.externalNativeBuild" \
  --exclude "local.properties.__wsl_backup__" \
  "${SOURCE_ROOT}/" "${WORKSPACE_DIR}/"

cat > "${WORKSPACE_DIR}/local.properties" <<EOF
sdk.dir=${WSL_SDK_DIR}
EOF

GRADLE_FLAGS=(
  "--no-daemon"
  "--console=plain"
)
if [[ "${OFFLINE_MODE}" == "1" ]]; then
  WRAPPER_PROPERTIES="${WORKSPACE_DIR}/gradle/wrapper/gradle-wrapper.properties"
  DIST_URL="$(sed -n 's/^distributionUrl=//p' "${WRAPPER_PROPERTIES}" | tail -n 1)"
  DIST_FILE="${DIST_URL##*/}"
  DIST_DIR_NAME="${DIST_FILE%-bin.zip}"
  DIST_DIR_NAME="${DIST_DIR_NAME%-all.zip}"
  if ! find "${GRADLE_HOME_DIR}/wrapper/dists" -type d -name "${DIST_DIR_NAME}" -print -quit 2>/dev/null | grep -q .; then
    echo "Gradle wrapper cache missing for offline mode at ${GRADLE_HOME_DIR}."
    echo "Run once with WHISPERME_WSL_OFFLINE=0 to warm caches, then rerun offline."
    exit 1
  fi
  GRADLE_FLAGS+=("--offline")
fi

echo "Running compile check in disposable workspace."
echo "Writes are limited to /tmp via workspace and GRADLE_USER_HOME."
(
  cd "${WORKSPACE_DIR}"
  GRADLE_USER_HOME="${GRADLE_HOME_DIR}" \
  JAVA_HOME="${JAVA_HOME_DIR}" \
  ANDROID_HOME="${WSL_SDK_DIR}" \
  ANDROID_SDK_ROOT="${WSL_SDK_DIR}" \
  ./gradlew "${GRADLE_FLAGS[@]}" :app:compileReleaseTestKotlin
)

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES="${ROOT_DIR}/local.properties"
BACKUP_PROPERTIES="${ROOT_DIR}/local.properties.__wsl_backup__"
WSL_SDK_DIR="${WSL_ANDROID_SDK_DIR:-/tmp/android-sdk-whisperme}"

if [[ ! -d "${WSL_SDK_DIR}" ]]; then
  echo "WSL SDK not found at ${WSL_SDK_DIR}"
  echo "Run: ./scripts/setup-wsl-android-sdk.sh"
  exit 1
fi

restore_local_properties() {
  if [[ -f "${BACKUP_PROPERTIES}" ]]; then
    mv -f "${BACKUP_PROPERTIES}" "${LOCAL_PROPERTIES}"
  fi
}
trap restore_local_properties EXIT

if [[ -f "${LOCAL_PROPERTIES}" ]]; then
  cp "${LOCAL_PROPERTIES}" "${BACKUP_PROPERTIES}"
fi

cat > "${LOCAL_PROPERTIES}" <<EOF
sdk.dir=${WSL_SDK_DIR}
EOF

echo "Using WSL SDK: ${WSL_SDK_DIR}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-whisperme}" \
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" \
ANDROID_HOME="${WSL_SDK_DIR}" \
"${ROOT_DIR}/gradlew" "$@"

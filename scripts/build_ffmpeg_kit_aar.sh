#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RN_ANDROID_DIR="$ROOT_DIR/RnAndroidHlsApp/android/app"
TARGET="${FFMPEG_KIT_TARGET:-rn}"
REPO_URL="https://github.com/arthenica/ffmpeg-kit.git"
TAG="${FFMPEG_KIT_TAG:-v6.0.LTS}"

if [[ -z "${ANDROID_SDK_ROOT:-}" || -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ANDROID_SDK_ROOT and ANDROID_NDK_ROOT must be set."
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

git clone --depth 1 --branch "$TAG" "$REPO_URL" "$WORK_DIR/ffmpeg-kit"
pushd "$WORK_DIR/ffmpeg-kit" >/dev/null
# CMake in the runner no longer accepts < 3.5, but cpu-features uses 2.8.
# Force a minimum policy version to keep the build working.
CMAKE_POLICY_VERSION_MINIMUM="${CMAKE_POLICY_VERSION_MINIMUM:-3.5}"
export CMAKE_POLICY_VERSION_MINIMUM
if [[ -n "${CMAKE_ARGS:-}" ]]; then
  export CMAKE_ARGS="${CMAKE_ARGS} -DCMAKE_POLICY_VERSION_MINIMUM=${CMAKE_POLICY_VERSION_MINIMUM}"
else
  export CMAKE_ARGS="-DCMAKE_POLICY_VERSION_MINIMUM=${CMAKE_POLICY_VERSION_MINIMUM}"
fi
if ! ./android.sh --disable-arm-v7a --disable-arm-v7a-neon; then
  if [[ -f "$WORK_DIR/ffmpeg-kit/build.log" ]]; then
    cp "$WORK_DIR/ffmpeg-kit/build.log" "$ROOT_DIR/ffmpeg-kit-build.log" || true
  fi
  exit 1
fi
if ! ./android.sh --lts --disable-arm-v7a --disable-arm-v7a-neon --disable-x86; then
  if [[ -f "$WORK_DIR/ffmpeg-kit/build.log" ]]; then
    cp "$WORK_DIR/ffmpeg-kit/build.log" "$ROOT_DIR/ffmpeg-kit-build.log" || true
  fi
  exit 1
fi

OUTPUT_GLOB="prebuilt/bundle-android-aar-lts/ffmpeg-kit-full-*.aar"
OUTPUT_FILE="$(ls $OUTPUT_GLOB 2>/dev/null | head -n 1 || true)"
if [[ -z "$OUTPUT_FILE" ]]; then
  echo "FFmpegKit AAR not found at $OUTPUT_GLOB"
  exit 1
fi

mkdir -p "$RN_ANDROID_DIR/libs"
cp "$OUTPUT_FILE" "$RN_ANDROID_DIR/libs/ffmpeg-kit-full.aar"
cat > "$RN_ANDROID_DIR/libs/ffmpeg-kit-full.version" <<EOF
tag=$TAG
source=$REPO_URL
built_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF
echo "Saved ffmpeg-kit-full.aar to $RN_ANDROID_DIR/libs"
popd >/dev/null

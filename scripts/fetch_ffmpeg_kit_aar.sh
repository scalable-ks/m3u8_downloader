#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
RN_ANDROID_DIR="$ROOT_DIR/RnAndroidHlsApp/android/app"
TARGET="${FFMPEG_KIT_TARGET:-core}"
if [[ -n "${FFMPEG_KIT_LIBS_DIR:-}" ]]; then
  LIBS_DIR="$FFMPEG_KIT_LIBS_DIR"
elif [[ "$TARGET" == "rn" ]]; then
  LIBS_DIR="$RN_ANDROID_DIR/libs"
else
  LIBS_DIR="$ANDROID_DIR/libs"
fi
AAR_NAME="ffmpeg-kit-full.aar"
URL="${FFMPEG_KIT_AAR_URL:-${1:-}}"

if [[ -z "$URL" ]]; then
  echo "FFMPEG_KIT_AAR_URL is required."
  echo "Example:"
  echo "  FFMPEG_KIT_AAR_URL=<direct_aar_url> ./scripts/fetch_ffmpeg_kit_aar.sh"
  echo "  FFMPEG_KIT_TARGET=rn FFMPEG_KIT_AAR_URL=<direct_aar_url> ./scripts/fetch_ffmpeg_kit_aar.sh"
  exit 1
fi

mkdir -p "$LIBS_DIR"

TMP_FILE="$(mktemp)"
HTTP_CODE="$(curl -L -w '%{http_code}' -o "$TMP_FILE" "$URL")"

if [[ "$HTTP_CODE" != "200" ]]; then
  rm -f "$TMP_FILE"
  echo "Download failed (HTTP $HTTP_CODE) for $URL"
  exit 1
fi

FILE_SIZE="$(wc -c < "$TMP_FILE")"
if [[ "$FILE_SIZE" -lt 1000000 ]]; then
  rm -f "$TMP_FILE"
  echo "Downloaded file too small ($FILE_SIZE bytes); aborting."
  exit 1
fi

mv "$TMP_FILE" "$LIBS_DIR/$AAR_NAME"
echo "Saved $AAR_NAME to $LIBS_DIR"

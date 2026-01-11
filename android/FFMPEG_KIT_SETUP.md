# FFmpegKit AAR Setup

This project expects an FFmpegKit Android AAR at:

```
android/libs/ffmpeg-kit-full.aar
```

## Option A: Use a Prebuilt AAR (Recommended)

If you have a direct AAR URL (internal artifact store or known release asset), run:

```bash
FFMPEG_KIT_AAR_URL=<direct_aar_url> task ffmpeg:fetch
```

The script will download the AAR and place it under `android/libs/`.

## Option B: Build the AAR from Source

1) Clone the FFmpegKit repo:

```bash
git clone https://github.com/arthenica/ffmpeg-kit.git
cd ffmpeg-kit
```

2) Set Android SDK/NDK environment variables:

```bash
export ANDROID_SDK_ROOT=<path_to_android_sdk>
export ANDROID_NDK_ROOT=<path_to_android_ndk>
```

3) Build Android AAR bundles:

```bash
./android.sh
```

4) Copy the AAR:

```bash
cp prebuilt/bundle-android-aar-lts/ffmpeg-kit-full-*.aar \
  /path/to/your/project/android/libs/ffmpeg-kit-full.aar
```

5) Re-run tests:

```bash
cd /path/to/your/project/android
./gradlew test
```

Notes:
- You can also choose lighter bundles (audio/min) if full isn’t required.
- This project will only use the AAR if it exists.

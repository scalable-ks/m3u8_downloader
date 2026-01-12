# FFmpegKit AAR Setup

This project expects an FFmpegKit Android AAR at:

```
android/libs/ffmpeg-kit-full.aar
```

The React Native app also expects:

```
RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.aar
```

## Option A: Use a Prebuilt AAR (Recommended)

If you have a direct AAR URL (internal artifact store or known release asset), run:

```bash
FFMPEG_KIT_AAR_URL=<direct_aar_url> task ffmpeg:fetch
```

The script will download the AAR and place it under `android/libs/`.

For the React Native app:

```bash
FFMPEG_KIT_TARGET=rn FFMPEG_KIT_AAR_URL=<direct_aar_url> ./scripts/fetch_ffmpeg_kit_aar.sh
```

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

For the React Native app:

```bash
cp prebuilt/bundle-android-aar-lts/ffmpeg-kit-full-*.aar \
  /path/to/your/project/RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.aar
```

Record the build metadata (manual copy):

```bash
echo "tag=v6.0.LTS" > /path/to/your/project/RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.version
echo "source=https://github.com/arthenica/ffmpeg-kit.git" >> /path/to/your/project/RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.version
echo "built_at=YYYY-MM-DDTHH:MM:SSZ" >> /path/to/your/project/RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.version
```

### Helper Script (React Native)

If you have the SDK/NDK installed, you can build and copy in one step:

```bash
export ANDROID_SDK_ROOT=<path_to_android_sdk>
export ANDROID_NDK_ROOT=<path_to_android_ndk>
FFMPEG_KIT_TAG=v6.0.LTS ./scripts/build_ffmpeg_kit_aar.sh
```

5) Re-run tests:

```bash
cd /path/to/your/project/android
./gradlew test
```

Notes:
- You can also choose lighter bundles (audio/min) if full isn’t required.
- This project will only use the AAR if it exists.

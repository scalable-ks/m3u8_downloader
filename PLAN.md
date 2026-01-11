# Implementation Plan

## Goals
- Download HLS (.m3u8) and choose the lowest-quality video variant.
- Audio: prefer ENG or ESP/SPA, otherwise take the first available track.
- Subtitles: only if the language matches audio, otherwise skip; embed inside MP4.
- Output file: MP4 (H.264 + AAC), compatible with VLC.
- Save to SD on Android 11 via SAF/MediaStore.

## Steps
1) Master playlist parser
   - Pick the minimal quality by RESOLUTION/BANDWIDTH.
   - Choose audio group by language (eng -> spa/esp -> first).
   - Choose subtitles only when language matches audio.
   - Follow media playlists (video/audio/subs), check for #EXT-X-KEY.

2) Native segment downloader (Kotlin)
   - Queue with concurrency limit (4-8).
   - Retries with backoff, resume by skipping already downloaded.
   - Progress by bytes and segments.
   - Temporary storage in app-specific external.

3) Decryption (if #EXT-X-KEY present)
   - AES-128 CBC, IV from playlist or sequence.
   - Decrypt on the fly before writing.

4) MP4 assembly
   - ffmpeg-kit: concat/remux (no re-encode).
   - Embed subtitles as mov_text when language matches.

5) Export to SD (Android 11)
   - SAF (ACTION_OPEN_DOCUMENT_TREE) for folder selection.
   - Or MediaStore for Movies/Downloads on external volume.

6) RN layer
   - UI progress, pause/resume, error handling.
   - Logging for network/decryption/ffmpeg errors.

7) Verification
   - Tests for language-based track selection.
   - Tests for MP4 assembly with/without subtitles.
   - SD export check on Android 11.

## Docker
- Use Dockerfile at repo root to build Android + RN environment.
- Build: docker build -t rn-android-hls .

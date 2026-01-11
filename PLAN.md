# Implementation Plan

## Goals
- Download HLS (.m3u8) and choose the lowest-quality video variant.
- Audio: prefer ENG or ESP/SPA, otherwise take the first available track.
- Subtitles: only if the language matches audio, otherwise skip; embed inside MP4.
- Output file: MP4 (H.264 + AAC), compatible with VLC.
- Save to SD on Android 11 via SAF/MediaStore.
- Be robust to network interruptions and resume after app restarts.
- Keep memory use low via streaming IO and bounded concurrency.

## Steps
1) Master playlist parser
   - Pick the minimal quality by RESOLUTION/BANDWIDTH.
   - Choose audio group by language (eng -> spa -> esp -> first).
   - Choose subtitles only when language matches audio.
   - Follow media playlists (video/audio/subs), check for #EXT-X-KEY.
   - Note segmented subtitle formats (e.g., WebVTT) if present.

2) Native segment downloader (Kotlin)
   - Queue with concurrency limit (default 2-4 on low-memory devices, configurable).
   - Retries with backoff + jitter, resume by skipping already downloaded.
   - Range requests for partial segment resume; append to .partial files.
   - Persist per-segment bytes and job state (DB/file) for resume after restart.
   - Progress by bytes and segments.
   - Temporary storage in app-specific external.

3) Decryption (if #EXT-X-KEY present)
   - AES-128 CBC, IV from playlist or sequence.
   - Decrypt on the fly before writing.

4) MP4 assembly
   - ffmpeg-kit: concat/remux; re-encode only when required for compatibility.
   - If source video codec is not H.264, allow video transcode to H.264.
   - Embed subtitles as mov_text when language matches.
   - Use concat list helper for fMP4 init segments; merge SRT segments before embedding.

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

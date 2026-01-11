# Implementation Plan

This plan is derived from `PLAN.md` and `PROJECT_ARCHITECTURE.md`.

## Architecture Decisions
- Background work: WorkManager for deferrable jobs; Foreground Service for long-running, user-visible downloads.
- Storage: temp files in app-specific external; export to SD via SAF (persisted URI permission). MediaStore as fallback.
- Codec policy: remux when possible; re-encode audio to AAC if track is AC3 or unsupported for MP4.
- Subtitles: only embed when language matches audio; otherwise skip.
- Robustness: resume after network interruptions and app restarts; avoid memory-heavy operations.

## Phase 1: Foundations
1) Define JS/TS domain models (playlist, variant, audio track, subtitle track, job status).
2) Implement master playlist parsing and selection logic:
   - lowest video quality
   - audio language priority (eng -> spa -> esp -> first)
   - audio group must match selected video variant (e.g., audio720 for 720p)
   - subtitles only if language matches audio
3) Resolve relative vs absolute URIs for all playlist entries.
4) Define RN ↔ Native bridge API (start/pause/resume/cancel, progress callbacks).
5) Define error taxonomy (network, storage, decryption, ffmpeg, validation).

## Phase 2: Android Native Downloader
1) Kotlin downloader with bounded concurrency, retry/backoff, and resume.
2) Streaming IO only (no buffering whole segments or playlists in memory).
3) Storage layer:
   - temp files in app-specific external
   - persisted SAF tree URI for SD export
4) Download state persistence (segments completed, bytes, playlist metadata) in a small local DB or file.
   - store per-segment byte counts for partial resume
   - store overall job state so WorkManager can resume after app restart
5) Disk space checks before large downloads.
6) Progress + error reporting back to JS.
7) Cancellation cleanup for partial files.
8) Network resilience:
   - exponential backoff with jitter
   - HTTP timeouts and retry limits per segment
   - Range requests for partial segment resume (append to .partial files)
   - bounded concurrency (default 2-4 on low-memory devices)
   - failure budget to avoid infinite retries

## Phase 3: Decryption (if #EXT-X-KEY present)
1) Detect encryption in media playlists.
2) Handle key rotation (playlist can change #EXT-X-KEY mid-stream).
3) AES-128 CBC, IV from playlist or sequence.
4) Decrypt on the fly before writing.

## Phase 4: Assembly to MP4
1) ffmpeg-kit integration.
2) Concatenate/remux video + audio.
3) Re-encode audio to AAC if required.
4) If source video codec is not H.264, allow video transcode to H.264.
5) Segment type support: TS and fMP4 (handle #EXT-X-MAP).
6) Subtitles:
   - download and merge SRT segments
   - normalize timestamps/encoding
   - embed as mov_text when language matches
   - note segmented WebVTT support if present in source playlists

## Phase 5: App Integration
1) React Native UI (progress, pause/resume, error states).
2) Job persistence across restarts.
3) Logs/metrics for debug.
4) Auth headers/cookies support when fetching playlists and segments.

## Phase 6: Testing
1) Unit tests for selection logic and language fallback.
2) Integration tests for downloader retry/resume and decryption.
3) End-to-end test: master → download → MP4 → export to SD.

## Phase 7: Hardening
1) Battery/network constraints.
2) Large playlist performance (streaming parse).
3) Edge cases (missing tracks, invalid playlists, partial downloads).
4) Cleanup policy for temp files and failed jobs.
5) Memory profiling on low-end devices.

## Optional
- Live playlist handling (refresh and rolling window).
- DRM detection: if present, offline reassembly may be blocked.

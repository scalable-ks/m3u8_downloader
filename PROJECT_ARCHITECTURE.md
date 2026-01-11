# Project Architecture & Requirements (Best Practices)

This document summarizes modern, widely used architecture practices for a React Native app that downloads HLS (m3u8) and assembles offline MP4 for Android 11+. It is aligned with Android’s official guidance on app architecture and storage, and standard background-work patterns.

## References (industry standards)
- Android Guide to App Architecture (official): https://developer.android.com/topic/architecture
- WorkManager (recommended for deferrable background work): https://developer.android.com/codelabs/android-workmanager-java
- Shared storage / SAF & Documents API (official): https://developer.android.com/training/data-storage/shared/documents-files

## Architectural Principles
- **Layered / Clean Architecture** to separate UI, domain logic, data sources, and platform-specific code.
- **Dependency Inversion** so domain logic is framework-agnostic and testable.
- **Single Responsibility** per module (parser, downloader, storage, muxing).
- **Background work reliability** via WorkManager (or Foreground Service when the user expects a persistent notification).
- **Scoped Storage compliance** for Android 11+ using SAF/MediaStore.

## Recommended High-Level Architecture

### 1) Presentation Layer (React Native)
- UI, progress bars, pause/resume, error states.
- State management (lightweight store or hooks) and view models (MVVM-style).
- Calls into a native module for heavy work.

### 2) Domain Layer (Platform-agnostic JS/TS)
- Use cases:
  - Parse master playlist.
  - Select lowest quality video.
  - Select audio by language (eng → spa → esp → fallback first).
  - Match subtitles by language (else skip).
  - Build download job definition.
- Business rules only; no IO or platform-specific code.

### 3) Data Layer (JS/TS + Native)
- Repository interfaces for:
  - HLS playlist fetching and parsing.
  - Segment download queue.
  - Local storage catalog and resume state.
  - Output file creation and metadata.
- Implementations split between JS and native (Android) for performance.

### 4) Native Android Layer (Kotlin)
- **Downloader**: OkHttp + coroutines, bounded concurrency, retry with backoff.
  - Range requests for partial resume; append to .partial files.
  - Per-segment state persisted for resume after process death/reboot.
- **Storage**: app-specific external storage for temp files; SAF/MediaStore export.
- **Muxing**: ffmpeg-kit (concat/remux, optional transcode).
  - FFmpegKit AAR setup: `android/FFMPEG_KIT_SETUP.md`
  - Concat list + subtitle merge helpers: `android/src/main/java/com/rnandroidhls/muxing`
- **Background Execution**: WorkManager for deferrable downloads; Foreground Service when long-running and user-visible.
  - Rule: use Foreground Service for active user-initiated downloads; fall back to WorkManager for deferred/resume work.

### 5) Integration Boundary (RN Bridge)
- Typed API surface between JS and native:
  - startJob(), pauseJob(), resumeJob(), cancelJob()
  - onProgress(bytes, segments), onError(code)
  - getJobStatus(id)

## Functional Requirements
- Input: HLS master playlist (m3u8).
- Select lowest quality video variant.
- Audio selection priority: ENG → SPA → ESP → first available.
- Subtitles embedded only if language matches audio; otherwise skip.
- Output: MP4 (H.264 + AAC), VLC-compatible.
  - Transcode audio/video when source codecs are not MP4-compatible.
- Subtitle sources may be segmented WebVTT or SRT; normalize before embedding.
- Storage: Android 11+ scoped storage; final export to SD via SAF/MediaStore.
- Resumable downloads and progress reporting.

## Non-Functional Requirements
- **Reliability**: resume after app restarts; handle network failures gracefully.
- **Performance**: bounded parallel downloads; avoid loading entire playlists in memory; prefer streaming IO.
- **Security**: handle encryption keys (if #EXT-X-KEY) safely in memory.
- **Compliance**: adhere to scoped storage, no legacy external storage flags.
- **Observability**: structured logs and error codes.

## Hardening & Profiling
- **Constraints**: apply unmetered/charging/idle checks before starting jobs; prefer WorkManager constraints for background runs.
- **Cleanup policy**: remove partial and failed job artifacts based on configured cleanup rules.
- **Large playlists**: parse line-by-line to avoid extra allocations on low-memory devices.
- **Memory profiling**: validate on low-end devices with Android Studio Profiler during large playlist downloads and muxing.

## Optional Capabilities
- **Live playlists**: merge rolling windows by sequence and compute refresh delays using target duration.
- **DRM detection**: detect SAMPLE-AES and known KEYFORMAT systems (Widevine/FairPlay/PlayReady) to warn users that offline assembly may be blocked.

## Testing Strategy (Unit-Heavy)
- **Unit tests** (JS/TS):
  - Playlist parsing and selection logic.
  - Language fallback behavior.
- **Unit/Integration (Android)**:
  - Segment downloader queue correctness and retry policy.
  - Storage export via SAF/MediaStore.
- **End-to-End**:
  - Full job: master → video/audio/subs → MP4 → export to SD.

## Notes on Background Execution (Android)
- Use WorkManager for guaranteed, deferrable tasks.
- Use Foreground Service for long-running downloads with visible notification.

## Storage Guidance (Android 11+)
- Temporary files in app-specific external storage.
- Final file export via SAF or MediaStore (user-approved SD access).

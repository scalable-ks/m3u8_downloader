# Test Implementation Guide for RnAndroidHlsApp

## Current Status

### ✅ Tests Added (This Session)
- `src/bridge/__tests__/downloadManager.test.ts` - Download manager lifecycle
- `src/domain/__tests__/m3u8.test.ts` - M3U8 playlist parsing
- `src/domain/__tests__/drm.test.ts` - DRM system detection
- `android/app/src/test/kotlin/com/rnandroidhlsapp/downloader/JobDownloaderComprehensiveTest.kt` - Job download orchestration
- `android/app/src/test/kotlin/com/rnandroidhlsapp/HlsDownloaderModuleTest.kt` - React Native bridge module

### 📊 Coverage Improvement
- **Before**: 7 test files (~15% coverage)
- **After**: 12 test files (~35% estimated coverage)
- **Critical paths covered**: Download manager, M3U8 parsing, DRM detection, job orchestration, native bridge

---

## Running the Tests

### JavaScript/TypeScript Tests
```bash
# Run all Jest tests
npm test

# Run with coverage
npm test -- --coverage

# Run specific test file
npm test -- src/bridge/__tests__/downloadManager.test.ts

# Watch mode for development
npm test -- --watch
```

### Android/Kotlin Tests
```bash
# On VM (required for Android tests)
cd ~/DOWNLOADER/RnAndroidHlsApp/android
./gradlew testDebugUnitTest

# Run specific test
./gradlew testDebugUnitTest --tests JobDownloaderComprehensiveTest

# With coverage report
./gradlew testDebugUnitTest jacocoTestReport
```

---

## TIER 1 Remaining Tests (High Priority)

### 1. Plan Builder Tests (`src/bridge/__tests__/plan.test.ts`)

**Why critical**: Orchestrates entire download flow, handles network requests, track selection

```typescript
describe('buildDownloadPlan', () => {
  it('should fetch and parse master playlist');
  it('should select lowest quality variant');
  it('should handle network errors with retries');
  it('should merge headers and cookies correctly');
  it('should detect live playlists and enable refresh');
  it('should throw on DRM-protected content');
  it('should handle missing audio/subtitle tracks gracefully');
  it('should resolve relative URIs correctly');
});
```

### 2. Native Bridge Tests (`src/bridge/__tests__/nativeBridge.test.ts`)

**Why critical**: Communication layer between JavaScript and Android

```typescript
describe('NativeDownloaderBridge', () => {
  it('should serialize download plan to JSON');
  it('should parse progress events from native');
  it('should parse error events from native');
  it('should handle malformed event payloads');
  it('should throw when native module unavailable');
  it('should register event listeners on construction');
  it('should clean up listeners on destroy');
});
```

### 3. SegmentDownloader Full Tests (`android/app/src/test/kotlin/.../SegmentDownloaderFullTest.kt`)

**Why critical**: Core download logic with encryption, byte ranges, retries

```kotlin
class SegmentDownloaderFullTest {
    @Test fun `downloads encrypted segment with AES-128`()
    @Test fun `caches encryption keys to avoid re-fetching`()
    @Test fun `downloads byte range segment`()
    @Test fun `resumes partial download`()
    @Test fun `retries on network timeout`()
    @Test fun `handles HTTP 403 error`()
    @Test fun `handles HTTP 404 error`()
    @Test fun `handles corrupt segment data`()
}
```

---

## TIER 2 Tests (Medium Priority)

### 4. Track Selection Logic (`src/domain/__tests__/selection.test.ts`)

```typescript
describe('selectTracks', () => {
  it('should select lowest bandwidth variant');
  it('should prefer English audio (eng, en)');
  it('should fallback to Spanish (spa, es, esp)');
  it('should pair subtitles with selected audio language');
  it('should handle missing audio group gracefully');
  it('should canonicalize language codes (eng→en)');
});
```

### 5. Media Playlist Tests (`src/domain/__tests__/media.test.ts`)

```typescript
describe('parseMediaPlaylist', () => {
  it('should parse segments with duration');
  it('should parse EXT-X-KEY encryption');
  it('should parse EXT-X-MAP init segments');
  it('should parse byte ranges');
  it('should detect live playlists');
  it('should merge live playlist updates');
  it('should calculate reload delay');
  it('should handle discontinuity sequences');
});
```

### 6. HlsDownloadWorker Tests (`android/.../HlsDownloadWorkerTest.kt`)

```kotlin
class HlsDownloadWorkerTest {
    @Test fun `downloads, muxes, and exports successfully`()
    @Test fun `handles assembly failure gracefully`()
    @Test fun `cleans up temp files on completion`()
    @Test fun `updates foreground notification during download`()
    @Test fun `cancels on user request`()
    @Test fun `handles constraint violations mid-download`()
}
```

---

## TIER 3 Tests (Lower Priority)

### 7. UI Component Tests

```typescript
// src/ui/__tests__/DownloadScreen.test.tsx
describe('DownloadScreen', () => {
  it('should render input form correctly');
  it('should validate URL before starting download');
  it('should show progress bar during download');
  it('should handle download errors with error message');
  it('should allow cancel during download');
});

// src/ui/__tests__/useDownloadJobs.test.tsx
describe('useDownloadJobs', () => {
  it('should initialize job list from manager');
  it('should update progress when events received');
  it('should add new jobs to list');
  it('should remove completed jobs after delay');
});
```

### 8. Storage & Persistence Tests

```kotlin
// FileDownloadStateStore, PlanFileStore
class FileDownloadStateStoreTest {
    @Test fun `saves download state to file`()
    @Test fun `loads state from file`()
    @Test fun `handles file I/O errors`()
    @Test fun `cleans up old state files`()
}
```

---

## Integration Tests (Cross-layer)

### End-to-End Download Flow

```typescript
// __tests__/integration/downloadFlow.test.ts
describe('Download Flow Integration', () => {
  it('should complete full download: plan → download → mux → export', async () => {
    // 1. Mock master playlist fetch
    // 2. Build download plan
    // 3. Start download via manager
    // 4. Verify progress events
    // 5. Wait for completion
    // 6. Verify output file exists
  });
});
```

```kotlin
// android/.../integration/DownloadFlowIntegrationTest.kt
class DownloadFlowIntegrationTest {
    @Test fun `end to end download with real HTTP server`() {
        // 1. Start mock HTTP server with playlist and segments
        // 2. Enqueue HlsDownloadWorker
        // 3. Wait for worker completion
        // 4. Verify MP4 output file
        // 5. Verify cleanup based on policy
    }
}
```

---

## Test Data & Fixtures

Create test fixtures for common scenarios:

```typescript
// __tests__/fixtures/playlists.ts
export const MASTER_PLAYLIST_BASIC = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
low.m3u8`;

export const MASTER_PLAYLIST_WITH_AUDIO = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="en",URI="audio-en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO="audio"
video.m3u8`;

export const MEDIA_PLAYLIST_ENCRYPTED = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key.bin"
#EXTINF:10.0
seg1.ts`;

export const MEDIA_PLAYLIST_LIVE = `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts`;
```

---

## Mocking Best Practices

### 1. Mock at the Right Level

```typescript
// ❌ BAD: Mock too low (HTTP library)
jest.mock('node-fetch');

// ✅ GOOD: Mock at service boundary
jest.mock('../bridge/nativeBridge');
```

### 2. Use Test Doubles for State

```typescript
// ✅ GOOD: Fake in-memory implementation
class FakeJobStore implements JobStore {
  private jobs = new Map<string, DownloadJob>();

  async save(job: DownloadJob) {
    this.jobs.set(job.id, job);
  }

  async load(id: string) {
    return this.jobs.get(id);
  }
}
```

### 3. Mock Async Operations Explicitly

```typescript
// ✅ GOOD: Explicit async resolution
mockBridge.startJob.mockResolvedValue({ id: 'job-123', state: 'PENDING' });

// ❌ BAD: Synchronous return (breaks async flow)
mockBridge.startJob.mockReturnValue({ ... });
```

---

## Continuous Integration

### GitHub Actions Workflow (`.github/workflows/test.yml`)

```yaml
name: Tests

on: [push, pull_request]

jobs:
  javascript-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: '20'
      - run: npm ci
      - run: npm test -- --coverage

  android-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'
      - name: Run Android unit tests
        run: |
          cd android
          ./gradlew testDebugUnitTest
```

---

## Next Steps

1. **Sync new test files to VM and run**
   ```bash
   rsync -avz --exclude='node_modules' ./ user@192.168.0.244:~/DOWNLOADER/
   npm test
   ```

2. **Implement TIER 1 remaining tests** (plan.ts, nativeBridge.ts, SegmentDownloader)

3. **Add integration tests** for end-to-end flows

4. **Set up code coverage reporting**
   ```bash
   npm test -- --coverage
   ```

5. **Add CI/CD pipeline** to run tests on every commit

---

## Success Metrics

- **Goal**: 70%+ code coverage on critical paths
- **Current**: ~35% estimated
- **Target breakdown**:
  - Download management: 80%+
  - Playlist parsing: 90%+
  - Native bridge: 75%+
  - UI components: 60%+

---

## References

- [React Native Testing Best Practices](https://reactnative.dev/docs/testing-overview)
- [Jest Documentation](https://jestjs.io/docs/getting-started)
- [Kotlin Testing with JUnit](https://developer.android.com/training/testing/local-tests)
- [MockK for Kotlin](https://mockk.io/)
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)

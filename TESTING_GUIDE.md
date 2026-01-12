# Testing Guide - Behavior-Focused Unit Tests (Node.js + TypeScript)

## Core Principle

> **Test observable behavior (what the code does), NOT implementation details (how it does it).**

### ✅ Good Tests Check
- **Observable behavior**: return values, thrown errors, final object state.
- **User-facing failures**: what breaks for users when bugs occur.
- **Edge cases**: invalid inputs, boundary conditions, malformed data.
- **Business logic**: real functionality failures, not internal helpers.

### ❌ Bad Tests Check
- **Implementation details**: private functions, call order, internal loops.
- **Internal state**: intermediate variables, private caches.
- **Happy path only**: missing failure/edge coverage.
- **Coupling to structure**: tests that break on refactor without behavior changes.

## References
- Node.js test runner overview: https://nodejs.org/api/test.html
- Arrange-Act-Assert pattern: https://automationpanda.com/2020/07/07/arrange-act-assert-a-pattern-for-writing-good-tests/
- Node.js testing best practices: https://github.com/goldbergyoni/nodejs-testing-best-practices

---

## Test Runner (Project Standard)

We use the **built-in Node.js test runner** (`node:test`) with TypeScript stripping.

```bash
npm test
# runs: node --test --experimental-strip-types
```

For watch mode:
```bash
npm run test:watch
```

---

## Test Structure - AAA Pattern

Use **Arrange-Act-Assert** to keep tests readable:

```js
import test from "node:test";
import assert from "node:assert/strict";
import { parseMasterPlaylist } from "../src/domain/m3u8.ts";

test("parseMasterPlaylist: resolves relative URIs", () => {
  // ARRANGE
  const content = `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlow.m3u8`;

  // ACT
  const playlist = parseMasterPlaylist(content, "https://example.com/master.m3u8");

  // ASSERT
  assert.equal(playlist.variants[0].uri, "https://example.com/low.m3u8");
});
```

Why AAA works:
- Clear separation between setup, action, and verification.
- Keeps tests focused on behavior.
- Makes refactors safer.

---

## Table-Driven Tests

Use table-driven tests for multiple scenarios:

```js
import test from "node:test";
import assert from "node:assert/strict";
import { selectTracks } from "../src/domain/selection.ts";

const cases = [
  {
    name: "audio fallback to eng",
    master: `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO=\"audio\"\nlow.m3u8\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",LANGUAGE=\"eng\",URI=\"a.m3u8\"`,
    wantAudioLang: "eng",
  },
  {
    name: "missing variants throws",
    master: `#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",LANGUAGE=\"eng\",URI=\"a.m3u8\"`,
    wantError: /No variants/,
  },
];

for (const tc of cases) {
  test(`selectTracks: ${tc.name}`, () => {
    if (tc.wantError) {
      assert.throws(() => selectTracks(tc.master), tc.wantError);
      return;
    }

    const selected = selectTracks(tc.master);
    assert.equal(selected.audio?.language, tc.wantAudioLang);
  });
}
```

Benefits:
- Easy to add cases.
- Self-documenting.
- Avoids duplicated setup.

---

## What to Test vs What NOT to Test

### ✅ DO Test: Observable Behavior
```js
test("selectTracks returns undefined audio if group mismatched", () => {
  const selected = selectTracks(masterPlaylistWithMismatchedGroups);
  assert.equal(selected.audio, undefined);
});
```

### ❌ DON'T Test: Implementation Details
```js
// BAD: asserts internal call order or private helpers
```

### ✅ DO Test: Error Cases
```js
test("selectTracks throws when no variants exist", () => {
  assert.throws(() => selectTracks(masterWithoutVariants), /No variants/);
});
```

---

## Test Naming Convention

Prefer clear, scenario-based naming:
- `parseMasterPlaylist: resolves relative URIs`
- `selectTracks: throws when no variants exist`
- `parseMediaPlaylist: supports key rotation`

---

## Test Documentation

Use short comments when needed to capture **why** a test exists (bug prevention or user impact). Avoid redundant comments.

---

## Edge Cases to Prioritize

- Empty or malformed playlists.
- Missing `AUDIO` or `SUBTITLES` group IDs.
- Language alias matching (`en` vs `eng`, `es` vs `spa`).
- No variants / no tracks.
- Key rotation in media playlists.

---

## Test Organization

- Keep tests in `test/`.
- Name files by module: `m3u8.test.js`, `media-playlist.test.js`, etc.
- Group related tests in the same file.

---

## Quality Over Coverage

Coverage is not the goal. Test **critical paths, edge cases, and regressions**. If a test doesn’t assert behavior, it isn’t useful.

---

## Quick Checklist

Before committing tests:
- [ ] Tests focus on **what** code does, not **how** it does it.
- [ ] AAA pattern is followed.
- [ ] Test names describe scenario + expected behavior.
- [ ] Edge cases and error cases are included.
- [ ] Bug regressions are documented (when applicable).
- [ ] Tests pass with `npm test`.

---

## End-to-End Test (Manual)

Run this before release builds:

1) Use a known-good HLS URL (public or internal test fixture).
2) Start a download from the app UI and confirm:
   - Progress updates over time (not instant completion).
   - Pausing/resuming works without losing progress.
3) Let the job complete and confirm:
   - MP4 output exists in the temp output directory.
   - Export to SAF/SD succeeds and file is readable.
4) Validate logs:
   - Job start, segment progress, muxing start, muxing complete.
   - No errors or retries that exceed the budget.

Record the URL used and final output size in the release notes.

---

## Memory Profiling (Android)

Goal: confirm the downloader stays within low-end device limits.

Checklist:
- [ ] Profile on a low-end device (1 GB RAM if possible).
- [ ] Use Android Studio Profiler or:
  `adb shell dumpsys meminfo <package>`
- [ ] Start a medium-sized download (10-20 minutes).
- [ ] Observe peak RAM during download and muxing.
- [ ] Confirm memory returns to baseline after completion/cancel.
- [ ] Confirm no large in-memory buffers (segments streamed to disk).

Record peak memory and device model in the release notes.

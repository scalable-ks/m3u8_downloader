# Test Fix Summary: SegmentDownloaderFullTest Encryption Tests

## Status: ✅ FIXED

All 12 tests in `SegmentDownloaderFullTest.kt` now pass (5 encryption tests + 7 non-encryption tests).

## Root Cause Analysis

**The original plan was INCORRECT.** The plan assumed the problem was:
1. Phase 1: android.util.Log crashes (fixed with `returnDefaultValues = true`)
2. Phase 2: CipherInputStream handling bug

**The ACTUAL problem was**: MockWebServer response ordering mismatch in the test setup.

## Discovery Process

### Step 1: Applied Plan's Proposed Fixes
- Added `returnDefaultValues = true` to build.gradle (was already done)
- Tried the proposed CipherInputStream fix - **still failed**
- Tried alternative stream handling approaches - **still failed**

### Step 2: Root Cause Investigation
Created diagnostic tests to isolate the problem:

1. **Basic crypto test** - ✅ PASSED
   - Verified javax.crypto.Cipher and CipherInputStream work correctly

2. **MockWebServer with manual decryption** - ✅ PASSED
   - Verified MockWebServer correctly transmits binary data
   - Verified HTTP fetching + manual decryption works

3. **Exact SegmentDownloader approach** - ✅ PASSED
   - Mimicked exact implementation code path
   - Used `body.byteStream()` + CipherInputStream
   - **This proved the implementation code was correct!**

### Step 3: The Breakthrough
Since all diagnostic tests passed, the problem had to be in how the real tests were set up.

**Discovered**: HTTP request order vs. MockResponse enqueue order mismatch!

#### How SegmentDownloader Works
```kotlin
// 1. Request segment data FIRST
client.newCall(request).execute().use { response ->
    // 2. Request encryption key SECOND (inside response handler)
    val decrypted = decryptStream(body.byteStream(), segment, headers)
}
```

Request order: **SEGMENT → KEY**

#### How Tests Were Set Up
```kotlin
// WRONG ORDER
server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))      // First
server.enqueue(MockResponse().setBody(Buffer().write(encryptedData))) // Second
```

Enqueue order: **KEY → SEGMENT**

#### The Problem
MockWebServer serves responses in **FIFO order** (first enqueued = first returned).

So when tests ran:
1. Segment request got the KEY response
2. Key request got the SEGMENT response

This caused the implementation to try decrypting the 16-byte key as if it were encrypted data, resulting in `BadPaddingException: Given final block not properly padded`.

## The Fix

Fixed all 5 failing encryption tests by swapping MockResponse enqueue order:

```kotlin
// CORRECT ORDER - matches request order
server.enqueue(MockResponse().setBody(Buffer().write(encryptedData))) // SEGMENT (requested first)
server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))      // KEY (requested second)
```

### Files Changed
- `SegmentDownloaderFullTest.kt` - Fixed MockResponse order in 5 tests:
  1. Line 54-57: "downloads encrypted segment with AES-128 decryption()"
  2. Line 93-96: "caches encryption keys to avoid re-fetching()"
  3. Line 193-195: "deletes and restarts encrypted segment on resume attempt()"
  4. Line 320-321: "passes custom headers to key request()"
  5. Line 384-385: "generates IV from sequence number when not provided()"

## Test Results

**Before Fix**:
- 12 tests completed
- 5 failed (all encryption tests)
- 7 passed (all non-encryption tests)

**After Fix**:
- 12 tests completed
- 0 failed ✅
- 12 passed ✅

## Key Insights

★ Insight ─────────────────────────────────────
**The Plan Was Wrong**: Sometimes plans based on incomplete analysis lead you astray. The systematic diagnostic approach (isolating variables through targeted tests) was crucial to finding the real issue.

**MockWebServer FIFO Ordering**: A common pitfall when testing HTTP code with MockWebServer. Always ensure enqueued responses match the order in which requests will be made.

**Test-First Bugs**: These tests were newly created (untracked in git) and had never passed. The bug was in the test setup, not the production code. The implementation worked correctly all along!
─────────────────────────────────────────────────

## No Changes Needed To Production Code

The original `SegmentDownloader.kt` implementation is correct and requires no modifications. The encryption logic, CipherInputStream handling, key fetching, and IV generation all work properly.

The `returnDefaultValues = true` in build.gradle is beneficial and should be kept for JVM unit testing, but it wasn't the fix for these specific test failures.

## Verification

All unit tests pass:
```
./gradlew testDebugUnitTest
BUILD SUCCESSFUL

SegmentDownloaderFullTest: 12 tests, 0 failures
HlsDownloadWorkerTest: 11 tests, 0 failures
HlsDownloaderModuleTest: 8 tests, 0 failures
```

No regressions introduced.

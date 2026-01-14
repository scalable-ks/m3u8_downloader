import test from "node:test";
import assert from "node:assert/strict";
import { buildDownloadPlan } from "../src/bridge/plan.ts";

test("buildDownloadPlan fetches playlists and builds segment plans", async () => {
  // ARRANGE
  const masterUrl = "https://example.com/master.m3u8";
  const videoUrl = "https://example.com/video.m3u8";
  const audioUrl = "https://example.com/audio.m3u8";
  const playlists = new Map([
    [
      masterUrl,
      `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="audio"
video.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="eng",URI="audio.m3u8"
`,
    ],
    [
      videoUrl,
      `#EXTM3U
#EXTINF:4.0,
seg1.ts
#EXTINF:4.0,
seg2.ts
#EXT-X-ENDLIST
`,
    ],
    [
      audioUrl,
      `#EXTM3U
#EXTINF:4.0,
aseg1.aac
#EXTINF:4.0,
aseg2.aac
#EXT-X-ENDLIST
`,
    ],
  ]);
  const requests = [];
  const fetcher = async (url, headers) => {
    requests.push({ url, headers });
    const content = playlists.get(url);
    if (!content) {
      throw new Error(`missing ${url}`);
    }
    return content;
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "job-1",
    masterPlaylistUri: masterUrl,
    headers: { Authorization: "Bearer token" },
    cookies: { session: "abc" },
    constraints: { requiresUnmetered: true },
    cleanupPolicy: { deleteOnFailure: true },
    exportTreeUri: "content://tree/primary%3ADownloads",
    fetcher,
  });

  // ASSERT
  assert.equal(plan.video.segments.length, 2);
  assert.equal(plan.audio?.segments.length, 2);
  assert.equal(plan.video.segments[0].uri, "https://example.com/seg1.ts");
  assert.equal(plan.audio?.segments[1].uri, "https://example.com/aseg2.aac");
  assert.equal(plan.headers.Cookie, "session=abc");
  assert.equal(requests[0].headers.Cookie, "session=abc");
  assert.equal(plan.constraints.requiresUnmetered, true);
  assert.equal(plan.cleanupPolicy.deleteOnFailure, true);
  assert.equal(plan.exportTreeUri, "content://tree/primary%3ADownloads");
});

test("buildDownloadPlan refreshes live playlists until ENDLIST", async () => {
  // ARRANGE
  const master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nvideo.m3u8";
  const initialVideo =
    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\nseg1.ts";
  const updatedVideo =
    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:2\n#EXTINF:2,\nseg2.ts\n#EXT-X-ENDLIST";
  const fetcher = createSequencedFetcher({
    "https://example.com/master.m3u8": [master],
    "https://example.com/video.m3u8": [initialVideo, updatedVideo],
  });

  // ACT
  const plan = await buildDownloadPlan({
    id: "job-live",
    masterPlaylistUri: "https://example.com/master.m3u8",
    fetcher,
    sleep: async () => {},
  });

  // ASSERT
  assert.equal(plan.video.segments.length, 1);
  assert.equal(plan.video.segments[0].uri, "https://example.com/seg2.ts");
});

test("buildDownloadPlan continues beyond old 5-refresh limit (regression)", async () => {
  // Bug #7: Previously limited to 5 refreshes, now continues until ENDLIST
  // ARRANGE
  const master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nvideo.m3u8";
  const liveVideo = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg";
  const finalVideo =
    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" +
    Array.from({ length: 10 }, (_, i) => `#EXTINF:2,\nseg${i}.ts`).join("\n") +
    "\n#EXT-X-ENDLIST";

  // Return live playlist 8 times, then final
  const fetcher = createSequencedFetcher({
    "https://example.com/master.m3u8": [master],
    "https://example.com/video.m3u8": [
      liveVideo,
      liveVideo,
      liveVideo,
      liveVideo,
      liveVideo,
      liveVideo, // Old limit was 5, we're at 6 refreshes now
      liveVideo,
      liveVideo,
      finalVideo,
    ],
  });

  // ACT
  const plan = await buildDownloadPlan({
    id: "job-regression",
    masterPlaylistUri: "https://example.com/master.m3u8",
    fetcher,
    sleep: async () => {},
  });

  // ASSERT
  assert.equal(plan.video.segments.length, 10, "Should capture all 10 segments beyond old 5-refresh limit");
});

test("buildDownloadPlan stops at maxLiveDurationMs safety limit", async () => {
  // ARRANGE
  const master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nvideo.m3u8";
  const liveVideo = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg1.ts";
  const fetcher = async (url, headers) => {
    // Always return live (no ENDLIST)
    if (url.includes("master")) return master;
    return liveVideo;
  };

  let refreshCount = 0;
  const mockSleep = async (ms) => {
    refreshCount++;
    // Simulate time passing by actually waiting a bit
    await new Promise((resolve) => setTimeout(resolve, 1));
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "job-duration-limit",
    masterPlaylistUri: "https://example.com/master.m3u8",
    fetcher,
    sleep: mockSleep,
    maxLiveDurationMs: 50, // Very short for test performance
  });

  // ASSERT
  // Should have stopped due to duration limit (not infinite loop)
  // With 50ms limit and 1ms per refresh, expect ~10-50 refreshes
  assert.ok(refreshCount > 0, "Should have attempted at least one refresh");
  assert.ok(refreshCount < 500, "Should stop within reasonable iterations");
});

test("buildDownloadPlan respects AbortSignal cancellation", async () => {
  // ARRANGE
  const master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nvideo.m3u8";
  const liveVideo = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg1.ts";
  const fetcher = async (url, headers) => {
    if (url.includes("master")) return master;
    return liveVideo;
  };

  const controller = new AbortController();
  let sleepCalls = 0;
  const mockSleep = async (ms) => {
    sleepCalls++;
    if (sleepCalls === 2) {
      controller.abort(); // Cancel after 2 refreshes
    }
  };

  // ACT & ASSERT
  await assert.rejects(
    async () => {
      await buildDownloadPlan({
        id: "job-cancel",
        masterPlaylistUri: "https://example.com/master.m3u8",
        fetcher,
        sleep: mockSleep,
        signal: controller.signal,
      });
    },
    /cancelled/i,
    "Should throw cancellation error when signal aborted",
  );
});

function createSequencedFetcher(map) {
  const counters = new Map();
  return async (url) => {
    const entries = map[url];
    if (!entries) {
      throw new Error(`Missing fixture for ${url}`);
    }
    const index = counters.get(url) ?? 0;
    counters.set(url, index + 1);
    return entries[Math.min(index, entries.length - 1)];
  };
}

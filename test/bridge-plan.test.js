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

test("buildDownloadPlan refreshes live playlists up to limit", async () => {
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
    liveRefreshLimit: 2,
  });

  // ASSERT
  assert.equal(plan.video.segments.length, 1);
  assert.equal(plan.video.segments[0].uri, "https://example.com/seg2.ts");
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

import test from "node:test";
import assert from "node:assert/strict";
import { buildDownloadPlan } from "../src/bridge/plan.ts";

/**
 * Tests for handling various HLS URL formats, including:
 * - Base64-encoded path segments
 * - Query parameters
 * - Special characters in paths
 */

test("handles URLs with base64-encoded paths and query parameters", async () => {
  // ARRANGE
  const masterUrl =
    "https://cdn2site.com/hls4/aWQ9Mjk3MDU2NTszMDA1MDgzODE3OzQxOTY0NDMzOzgwNzM2MTsxNzY4NDk3MDU1Jmg9MU9sZkdmcVRMaTdRSVRHWkk3SnlsdyZlPTE3Njg1ODM0NTU/807361.m3u8?loc=nl";

  const mockMasterPlaylist = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
video.m3u8`;

  const mockVideoPlaylist = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:10.0,
segment1.ts
#EXTINF:10.0,
segment2.ts
#EXT-X-ENDLIST`;

  const mockFetcher = async (url, _headers) => {
    if (url === masterUrl) {
      return mockMasterPlaylist;
    }
    // Resolve video.m3u8 relative to master URL
    if (url.includes("video.m3u8")) {
      return mockVideoPlaylist;
    }
    throw new Error(`Unexpected URL: ${url}`);
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "test-123",
    masterPlaylistUri: masterUrl,
    fetcher: mockFetcher,
  });

  // ASSERT
  assert.equal(plan.id, "test-123");
  assert.equal(plan.masterPlaylistUri, masterUrl);
  assert.equal(plan.video.segments.length, 2);

  // Verify the plan can be serialized to JSON and deserialized
  let planJson;
  assert.doesNotThrow(() => {
    planJson = JSON.stringify(plan);
  }, "Plan should be serializable to JSON");

  assert.ok(planJson, "Plan JSON should not be empty");
  assert.ok(planJson.length > 0, "Plan JSON should have content");

  // Verify the JSON can be parsed back
  let parsed;
  assert.doesNotThrow(() => {
    parsed = JSON.parse(planJson);
  }, "Plan JSON should be parseable");

  assert.equal(parsed.id, plan.id);
  assert.equal(parsed.masterPlaylistUri, plan.masterPlaylistUri);
  assert.equal(parsed.video.segments.length, plan.video.segments.length);
});

test("handles URLs with special characters in query parameters", async () => {
  // ARRANGE
  const masterUrl = "https://example.com/playlist.m3u8?token=abc123&user=test%40example.com";

  const mockMasterPlaylist = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
stream.m3u8`;

  const mockVideoPlaylist = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:5
#EXTINF:5.0,
seg1.ts
#EXT-X-ENDLIST`;

  const mockFetcher = async (url, _headers) => {
    if (url === masterUrl) {
      return mockMasterPlaylist;
    }
    if (url.includes("stream.m3u8")) {
      return mockVideoPlaylist;
    }
    throw new Error(`Unexpected URL: ${url}`);
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "test-456",
    masterPlaylistUri: masterUrl,
    fetcher: mockFetcher,
  });

  // ASSERT
  assert.equal(plan.masterPlaylistUri, masterUrl);

  // Verify JSON serialization preserves the URL correctly
  const planJson = JSON.stringify(plan);
  const parsed = JSON.parse(planJson);
  assert.equal(parsed.masterPlaylistUri, masterUrl, "URL should be preserved exactly in JSON");
});

test("plan serialization does not include NaN or Infinity values", async () => {
  // ARRANGE
  const masterUrl = "https://example.com/test.m3u8";

  const mockMasterPlaylist = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000
video.m3u8`;

  const mockVideoPlaylist = `#EXTM3U
#EXT-X-VERSION:3
#EXTINF:8.5,
seg1.ts
#EXTINF:7.2,
seg2.ts
#EXT-X-ENDLIST`;

  const mockFetcher = async (url, _headers) => {
    if (url === masterUrl) {
      return mockMasterPlaylist;
    }
    if (url.includes("video.m3u8")) {
      return mockVideoPlaylist;
    }
    throw new Error(`Unexpected URL: ${url}`);
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "test-789",
    masterPlaylistUri: masterUrl,
    fetcher: mockFetcher,
  });

  const planJson = JSON.stringify(plan);

  // ASSERT - JSON should not contain NaN or Infinity (they get converted to null)
  assert.ok(!planJson.includes("NaN"), "JSON should not contain NaN");
  assert.ok(!planJson.includes("Infinity"), "JSON should not contain Infinity");

  // Verify all segment durations are valid finite numbers
  plan.video.segments.forEach((segment, index) => {
    assert.ok(Number.isFinite(segment.duration), `Segment ${index} duration should be finite`);
    assert.ok(segment.duration > 0, `Segment ${index} duration should be positive`);
  });
});

import test from "node:test";
import assert from "node:assert/strict";
import { mergeLivePlaylist, nextReloadDelayMs, parseMediaPlaylist } from "../src/domain/media.ts";

test("mergeLivePlaylist merges rolling window by sequence", () => {
  // ARRANGE
  const first = `#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:1
#EXTINF:6.0,
seg-1.ts
#EXTINF:6.0,
seg-2.ts
`;
  const second = `#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:2
#EXTINF:6.0,
seg-2.ts
#EXTINF:6.0,
seg-3.ts
`;
  const firstPlaylist = parseMediaPlaylist(first, "https://example.com/live.m3u8");
  const secondPlaylist = parseMediaPlaylist(second, "https://example.com/live.m3u8");

  // ACT
  const merged = mergeLivePlaylist(firstPlaylist, secondPlaylist);

  // ASSERT
  assert.equal(merged.segments.length, 2);
  assert.equal(merged.segments[0].sequence, 2);
  assert.equal(merged.segments[1].sequence, 3);
});

test("nextReloadDelayMs uses target duration for live playlists", () => {
  // ARRANGE
  const content = `#EXTM3U
#EXT-X-TARGETDURATION:8
#EXT-X-MEDIA-SEQUENCE:10
#EXTINF:8.0,
seg-10.ts
`;
  const playlist = parseMediaPlaylist(content, "https://example.com/live.m3u8");

  // ACT
  const delay = nextReloadDelayMs(playlist);

  // ASSERT
  assert.equal(delay, 8000);
});

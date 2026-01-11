import test from "node:test";
import assert from "node:assert/strict";
import { parseMediaPlaylist } from "../src/domain/media.ts";

const media = `#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:5
#EXT-X-KEY:METHOD=AES-128,URI="key.key",IV=0x1234
#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
#EXTINF:6.0,Segment 1
seg-5.ts
#EXT-X-BYTERANGE:800@720
#EXTINF:6.0,Segment 2
seg-6.ts
#EXT-X-ENDLIST
`;

test("parseMediaPlaylist parses segments, keys, maps", () => {
  const playlist = parseMediaPlaylist(media, "https://example.com/video/low.m3u8");
  assert.equal(playlist.targetDuration, 6);
  assert.equal(playlist.mediaSequence, 5);
  assert.equal(playlist.isLive, false);
  assert.equal(playlist.segments.length, 2);
  assert.equal(playlist.segments[0].uri, "https://example.com/video/seg-5.ts");
  assert.equal(playlist.segments[0].key?.uri, "https://example.com/video/key.key");
  assert.equal(playlist.segments[0].map?.uri, "https://example.com/video/init.mp4");
  assert.equal(playlist.segments[1].byteRange?.length, 800);
  assert.equal(playlist.segments[1].sequence, 6);
});

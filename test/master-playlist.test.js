import test from "node:test";
import assert from "node:assert/strict";
import { parseMasterPlaylist } from "../src/domain/m3u8.ts";
import { selectTracks } from "../src/domain/selection.ts";

const master = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="audio-low",SUBTITLES="subs"
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio-hi",SUBTITLES="subs"
high.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-low",NAME="English",LANGUAGE="eng",DEFAULT=YES,URI="audio/eng.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-low",NAME="Spanish",LANGUAGE="spa",URI="audio/spa.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="eng",URI="subs/eng.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Spanish",LANGUAGE="spa",URI="subs/spa.m3u8"
`;

test("parseMasterPlaylist resolves URIs and extracts tracks", () => {
  // ARRANGE
  const uri = "https://example.com/master.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(master, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 2);
  assert.equal(playlist.audios.length, 2);
  assert.equal(playlist.subtitles.length, 2);
  assert.equal(playlist.variants[0].uri, "https://example.com/low.m3u8");
  assert.equal(playlist.audios[0].uri, "https://example.com/audio/eng.m3u8");
});

const cases = [
  {
    name: "lowest variant with matching audio/subtitle",
    master,
    expect: (selected) => {
      assert.equal(selected.video.uri, "https://example.com/low.m3u8");
      assert.equal(selected.audio?.language, "eng");
      assert.equal(selected.subtitle?.language, "eng");
    },
  },
  {
    name: "language alias for subtitle matches audio",
    master: `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240,AUDIO="audio",SUBTITLES="subs"
low.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="eng",URI="subs/eng.m3u8"
`,
    expect: (selected) => {
      assert.equal(selected.audio?.language, "en");
      assert.equal(selected.subtitle?.language, "eng");
    },
  },
  {
    name: "audio group mismatch returns no audio",
    master: `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240,AUDIO="audio-a"
low.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-b",NAME="English",LANGUAGE="eng",DEFAULT=YES,URI="audio/eng.m3u8"
`,
    expect: (selected) => {
      assert.equal(selected.audio, undefined);
    },
  },
  {
    name: "missing variants throws",
    master: `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="eng",DEFAULT=YES,URI="audio/eng.m3u8"
`,
    expectError: /No variants/,
  },
  {
    name: "missing subtitles group ignores subtitles",
    master: `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240,AUDIO="audio"
low.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="eng",DEFAULT=YES,URI="audio/eng.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="eng",URI="subs/eng.m3u8"
`,
    expect: (selected) => {
      assert.equal(selected.subtitle, undefined);
    },
  },
];

for (const tc of cases) {
  test(`selectTracks: ${tc.name}`, () => {
    // ARRANGE
    const playlist = parseMasterPlaylist(tc.master, "https://example.com/master.m3u8");

    // ACT + ASSERT
    if (tc.expectError) {
      assert.throws(() => selectTracks(playlist), tc.expectError);
      return;
    }

    const selected = selectTracks(playlist);
    tc.expect(selected);
  });
}

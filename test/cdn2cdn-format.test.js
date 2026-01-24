import test from "node:test";
import assert from "node:assert/strict";
import { parseMasterPlaylist } from "../src/domain/m3u8.ts";
import { selectTracks } from "../src/domain/selection.ts";
import { buildDownloadPlan } from "../src/bridge/plan.ts";

/**
 * Tests for cdn2cdn.com playlist format validation
 * Validates parsing of real-world playlists with:
 * - Multiple video variants with non-standard resolutions
 * - Multiple audio groups (audio1080, audio720, audio480)
 * - Multiple audio tracks per group
 * - Multiple subtitle tracks
 * - Complex HLS v5 attributes
 */

const CDN2CDN_MASTER = `#EXTM3U
#EXT-X-VERSION:5

# 3 video quality levels
#EXT-X-STREAM-INF:BANDWIDTH=2864588,RESOLUTION=1920x800,FRAME-RATE=23.976,CODECS="avc1.640028",VIDEO-RANGE=SDR,HDCP-LEVEL=TYPE-0,AUDIO="audio1080",SUBTITLES="subtitle"
1080/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1486090,RESOLUTION=1280x534,FRAME-RATE=23.976,CODECS="avc1.64001f",VIDEO-RANGE=SDR,HDCP-LEVEL=TYPE-0,AUDIO="audio720",SUBTITLES="subtitle"
720/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=655294,RESOLUTION=720x300,FRAME-RATE=23.976,CODECS="avc1.64001e",VIDEO-RANGE=SDR,HDCP-LEVEL=TYPE-0,AUDIO="audio480",SUBTITLES="subtitle"
480/video.m3u8

# 3 I-frame variants
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=143242,RESOLUTION=1920x800,CODECS="avc1.640028",URI="1080/iframes.m3u8"
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=74304,RESOLUTION=1280x534,CODECS="avc1.64001f",URI="720/iframes.m3u8"
#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=32766,RESOLUTION=720x300,CODECS="avc1.64001e",URI="480/iframes.m3u8"

# 12 audio tracks (4 languages × 3 quality groups)
# Russian dubbing
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio1080",NAME="Russian dubbing",LANGUAGE="rus",DEFAULT=YES,AUTOSELECT=YES,URI="audio/rus-dub-1080.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio720",NAME="Russian dubbing",LANGUAGE="rus",DEFAULT=YES,AUTOSELECT=YES,URI="audio/rus-dub-720.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio480",NAME="Russian dubbing",LANGUAGE="rus",DEFAULT=YES,AUTOSELECT=YES,URI="audio/rus-dub-480.m3u8"
# Russian AC3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio1080",NAME="Russian AC3",LANGUAGE="rus",URI="audio/rus-ac3-1080.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio720",NAME="Russian AC3",LANGUAGE="rus",URI="audio/rus-ac3-720.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio480",NAME="Russian AC3",LANGUAGE="rus",URI="audio/rus-ac3-480.m3u8"
# Ukrainian dubbing
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio1080",NAME="Ukrainian dubbing",LANGUAGE="ukr",URI="audio/ukr-dub-1080.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio720",NAME="Ukrainian dubbing",LANGUAGE="ukr",URI="audio/ukr-dub-720.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio480",NAME="Ukrainian dubbing",LANGUAGE="ukr",URI="audio/ukr-dub-480.m3u8"
# English original
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio1080",NAME="English original",LANGUAGE="eng",URI="audio/eng-1080.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio720",NAME="English original",LANGUAGE="eng",URI="audio/eng-720.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio480",NAME="English original",LANGUAGE="eng",URI="audio/eng-480.m3u8"

# 5 subtitle tracks
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitle",NAME="Ukrainian",LANGUAGE="ukr",DEFAULT=YES,AUTOSELECT=YES,URI="subs/ukr.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitle",NAME="Ukrainian (forced)",LANGUAGE="ukr",FORCED=YES,URI="subs/ukr-forced.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitle",NAME="Russian",LANGUAGE="rus",URI="subs/rus.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitle",NAME="Russian (forced)",LANGUAGE="rus",FORCED=YES,URI="subs/rus-forced.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitle",NAME="English",LANGUAGE="eng",URI="subs/eng.m3u8"
`;

test("parseMasterPlaylist handles cdn2cdn-style playlist with 6 variants, 12 audio tracks, 5 subtitles", () => {
  // ARRANGE
  const uri = "https://cdn2cdn.com/hls4/base64/22657.m3u8?loc=nl";

  // ACT
  const playlist = parseMasterPlaylist(CDN2CDN_MASTER, uri);

  // ASSERT - Variants (I-frame variants are not parsed by current implementation)
  assert.equal(playlist.variants.length, 3, "Should parse 3 regular video variants");

  // Verify variant resolutions (non-standard ultra-wide format)
  assert.equal(playlist.variants[0].resolution?.width, 1920);
  assert.equal(playlist.variants[0].resolution?.height, 800);
  assert.equal(playlist.variants[1].resolution?.width, 1280);
  assert.equal(playlist.variants[1].resolution?.height, 534);
  assert.equal(playlist.variants[2].resolution?.width, 720);
  assert.equal(playlist.variants[2].resolution?.height, 300);

  // Verify CODECS preserved
  assert.equal(playlist.variants[0].codecs, "avc1.640028");
  assert.equal(playlist.variants[1].codecs, "avc1.64001f");
  assert.equal(playlist.variants[2].codecs, "avc1.64001e");

  // Verify audio group IDs
  assert.equal(playlist.variants[0].audioGroupId, "audio1080");
  assert.equal(playlist.variants[1].audioGroupId, "audio720");
  assert.equal(playlist.variants[2].audioGroupId, "audio480");

  // Verify subtitle group ID
  assert.equal(playlist.variants[0].subtitleGroupId, "subtitle");
  assert.equal(playlist.variants[1].subtitleGroupId, "subtitle");
  assert.equal(playlist.variants[2].subtitleGroupId, "subtitle");

  // Verify URIs resolved against base URL (query params from base are not preserved - standard URL behavior)
  assert.equal(playlist.variants[0].uri, "https://cdn2cdn.com/hls4/base64/1080/video.m3u8");
  assert.equal(playlist.variants[1].uri, "https://cdn2cdn.com/hls4/base64/720/video.m3u8");
  assert.equal(playlist.variants[2].uri, "https://cdn2cdn.com/hls4/base64/480/video.m3u8");

  // ASSERT - Audio tracks
  assert.equal(playlist.audios.length, 12, "Should parse all 12 audio tracks");

  // Verify audio grouped by GROUP-ID
  const audio1080 = playlist.audios.filter(a => a.groupId === "audio1080");
  const audio720 = playlist.audios.filter(a => a.groupId === "audio720");
  const audio480 = playlist.audios.filter(a => a.groupId === "audio480");
  assert.equal(audio1080.length, 4, "Should have 4 audio tracks in audio1080 group");
  assert.equal(audio720.length, 4, "Should have 4 audio tracks in audio720 group");
  assert.equal(audio480.length, 4, "Should have 4 audio tracks in audio480 group");

  // Verify languages present in each group
  const languages1080 = new Set(audio1080.map(a => a.language));
  assert.ok(languages1080.has("rus"));
  assert.ok(languages1080.has("ukr"));
  assert.ok(languages1080.has("eng"));

  // Verify Russian dubbing is DEFAULT in each group
  const rusDefault1080 = audio1080.find(a => a.name === "Russian dubbing");
  assert.ok(rusDefault1080?.isDefault, "Russian dubbing should be DEFAULT in audio1080");
  assert.ok(rusDefault1080?.isAutoSelect, "Russian dubbing should be AUTOSELECT in audio1080");

  // ASSERT - Subtitles
  assert.equal(playlist.subtitles.length, 5, "Should parse all 5 subtitle tracks");

  // Verify subtitle languages
  const subLanguages = playlist.subtitles.map(s => s.language);
  assert.ok(subLanguages.includes("ukr"));
  assert.ok(subLanguages.includes("rus"));
  assert.ok(subLanguages.includes("eng"));

  // Verify Ukrainian subtitle is DEFAULT
  const ukrSub = playlist.subtitles.find(s => s.name === "Ukrainian");
  assert.ok(ukrSub?.isDefault, "Ukrainian subtitle should be DEFAULT");
  assert.ok(ukrSub?.isAutoSelect, "Ukrainian subtitle should be AUTOSELECT");
});

test("selectTracks chooses lowest variant (480p) for cdn2cdn playlist", () => {
  // ARRANGE
  const uri = "https://cdn2cdn.com/hls4/base64/22657.m3u8?loc=nl";
  const playlist = parseMasterPlaylist(CDN2CDN_MASTER, uri);

  // ACT
  const selected = selectTracks(playlist);

  // ASSERT
  assert.equal(selected.video.resolution?.width, 720);
  assert.equal(selected.video.resolution?.height, 300);
  assert.equal(selected.video.audioGroupId, "audio480");

  // Audio should be from audio480 group
  assert.equal(selected.audio?.groupId, "audio480");

  // Subtitle should match audio language
  assert.equal(selected.subtitle?.language, selected.audio?.language);
});

// ============================================================================
// PRIORITY 2: SCALE TESTS
// ============================================================================

test("parseMasterPlaylist handles 20+ video variants (stress test)", () => {
  // ARRANGE - Create 20 variants from 4320p down to 144p
  const resolutions = [
    [7680, 4320], [3840, 2160], [2560, 1440], [1920, 1080], [1600, 900],
    [1280, 720], [1024, 576], [960, 540], [854, 480], [640, 360],
    [480, 270], [426, 240], [384, 216], [320, 180], [256, 144],
    [1920, 800], [1280, 534], [720, 300], [640, 480], [800, 600]
  ];

  let masterContent = "#EXTM3U\n#EXT-X-VERSION:5\n";
  for (let i = 0; i < resolutions.length; i++) {
    const [width, height] = resolutions[i];
    const bandwidth = Math.floor(10000000 / (i + 1)); // Decreasing bandwidth
    masterContent += `#EXT-X-STREAM-INF:BANDWIDTH=${bandwidth},RESOLUTION=${width}x${height},CODECS="avc1.640028"\n`;
    masterContent += `variant${i}.m3u8\n`;
  }

  const uri = "https://cdn2cdn.com/stress-test.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(masterContent, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 20, "Should parse all 20 variants");

  // Verify first variant (highest resolution)
  assert.equal(playlist.variants[0].resolution?.width, 7680);
  assert.equal(playlist.variants[0].resolution?.height, 4320);

  // Verify last variant
  assert.equal(playlist.variants[19].resolution?.width, 800);
  assert.equal(playlist.variants[19].resolution?.height, 600);

  // Verify lowest variant is selected (by height, not bandwidth)
  // The variant with lowest height is 256x144
  const selected = selectTracks(playlist);
  assert.equal(selected.video.resolution?.width, 256);
  assert.equal(selected.video.resolution?.height, 144);
});

test("parseMasterPlaylist handles 30+ audio tracks across multiple groups", () => {
  // ARRANGE - Create 10 languages × 3 quality groups = 30 tracks
  const languages = [
    ["eng", "English"], ["spa", "Spanish"], ["fra", "French"],
    ["deu", "German"], ["ita", "Italian"], ["jpn", "Japanese"],
    ["rus", "Russian"], ["por", "Portuguese"], ["chi", "Chinese"], ["kor", "Korean"]
  ];
  const groups = ["audio-high", "audio-mid", "audio-low"];

  let masterContent = "#EXTM3U\n#EXT-X-VERSION:5\n";

  // Add a variant for each audio group
  for (const group of groups) {
    masterContent += `#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="${group}"\n`;
    masterContent += `${group}/video.m3u8\n`;
  }

  // Add 30 audio tracks (10 languages × 3 groups)
  for (const group of groups) {
    for (const [code, name] of languages) {
      const isDefault = code === "eng" ? "YES" : "NO";
      masterContent += `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="${group}",NAME="${name}",LANGUAGE="${code}",DEFAULT=${isDefault},URI="audio/${group}/${code}.m3u8"\n`;
    }
  }

  const uri = "https://cdn2cdn.com/multi-lang.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(masterContent, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 3, "Should parse 3 variants");
  assert.equal(playlist.audios.length, 30, "Should parse all 30 audio tracks");

  // Verify audio grouped correctly
  const highGroup = playlist.audios.filter(a => a.groupId === "audio-high");
  const midGroup = playlist.audios.filter(a => a.groupId === "audio-mid");
  const lowGroup = playlist.audios.filter(a => a.groupId === "audio-low");

  assert.equal(highGroup.length, 10, "Should have 10 tracks in audio-high");
  assert.equal(midGroup.length, 10, "Should have 10 tracks in audio-mid");
  assert.equal(lowGroup.length, 10, "Should have 10 tracks in audio-low");

  // Verify all languages present in each group
  const highLanguages = new Set(highGroup.map(a => a.language));
  assert.equal(highLanguages.size, 10, "Should have 10 unique languages");
  assert.ok(highLanguages.has("eng"));
  assert.ok(highLanguages.has("jpn"));
  assert.ok(highLanguages.has("kor"));

  // Verify English is DEFAULT in each group
  assert.ok(highGroup.find(a => a.language === "eng")?.isDefault);
  assert.ok(midGroup.find(a => a.language === "eng")?.isDefault);
  assert.ok(lowGroup.find(a => a.language === "eng")?.isDefault);
});

test("parseMasterPlaylist handles missing subtitles gracefully", () => {
  // ARRANGE - Playlist with variants and audio but NO subtitles
  const masterContent = `#EXTM3U
#EXT-X-VERSION:5
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="audio"
high.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="audio"
low.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="eng",DEFAULT=YES,URI="audio/eng.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",LANGUAGE="spa",URI="audio/spa.m3u8"
`;

  const uri = "https://cdn2cdn.com/no-subs.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(masterContent, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 2, "Should parse 2 variants");
  assert.equal(playlist.audios.length, 2, "Should parse 2 audio tracks");
  assert.equal(playlist.subtitles.length, 0, "Should have empty subtitles array");

  // Verify selectTracks works without subtitles
  const selected = selectTracks(playlist);
  assert.ok(selected.video, "Should select video");
  assert.ok(selected.audio, "Should select audio");
  assert.equal(selected.subtitle, undefined, "Should have no subtitle");
});

// ============================================================================
// PRIORITY 3: ATTRIBUTE PARSING EDGE CASES
// ============================================================================

test("parseAttributes handles complex CODECS with commas", () => {
  // ARRANGE - CODECS with commas inside quotes (video,audio or video,audio1,audio2)
  const masterContent = `#EXTM3U
#EXT-X-VERSION:5
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
h264-aac.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120.90,mp4a.40.5"
hevc-aac.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=3840x2160,CODECS="hev1.1.6.L150.90,mp4a.40.2,ec-3"
hevc-aac-dd.m3u8
`;

  const uri = "https://cdn2cdn.com/codecs-test.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(masterContent, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 3, "Should parse all 3 variants");

  // Verify CODECS with commas are preserved correctly (commas inside quotes don't split)
  assert.equal(playlist.variants[0].codecs, "avc1.640028,mp4a.40.2");
  assert.equal(playlist.variants[1].codecs, "hvc1.1.6.L120.90,mp4a.40.5");
  assert.equal(playlist.variants[2].codecs, "hev1.1.6.L150.90,mp4a.40.2,ec-3");

  // Verify resolutions parsed correctly even with complex CODECS
  assert.equal(playlist.variants[0].resolution?.width, 1920);
  assert.equal(playlist.variants[0].resolution?.height, 1080);
  assert.equal(playlist.variants[2].resolution?.width, 3840);
  assert.equal(playlist.variants[2].resolution?.height, 2160);
});

test("parseResolution handles non-standard resolutions", () => {
  // ARRANGE - Various non-standard resolutions
  const masterContent = `#EXTM3U
#EXT-X-VERSION:5
# Ultra-wide resolutions
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x800
ultrawide-1080.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=2560x1080
ultrawide-1440.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=3840x1608
ultrawide-4k.m3u8
# Portrait resolutions
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1080x1920
portrait-1080.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=720x1280
portrait-720.m3u8
# Very high resolutions
#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160
4k.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=7680x4320
8k.m3u8
`;

  const uri = "https://cdn2cdn.com/resolutions-test.m3u8";

  // ACT
  const playlist = parseMasterPlaylist(masterContent, uri);

  // ASSERT
  assert.equal(playlist.variants.length, 7, "Should parse all 7 variants");

  // Verify ultra-wide resolutions
  assert.equal(playlist.variants[0].resolution?.width, 1920);
  assert.equal(playlist.variants[0].resolution?.height, 800);
  assert.equal(playlist.variants[1].resolution?.width, 2560);
  assert.equal(playlist.variants[1].resolution?.height, 1080);
  assert.equal(playlist.variants[2].resolution?.width, 3840);
  assert.equal(playlist.variants[2].resolution?.height, 1608);

  // Verify portrait resolutions
  assert.equal(playlist.variants[3].resolution?.width, 1080);
  assert.equal(playlist.variants[3].resolution?.height, 1920);
  assert.equal(playlist.variants[4].resolution?.width, 720);
  assert.equal(playlist.variants[4].resolution?.height, 1280);

  // Verify very high resolutions
  assert.equal(playlist.variants[5].resolution?.width, 3840);
  assert.equal(playlist.variants[5].resolution?.height, 2160);
  assert.equal(playlist.variants[6].resolution?.width, 7680);
  assert.equal(playlist.variants[6].resolution?.height, 4320);

  // Verify lowest variant is selected (portrait 720x1280 has height=1280, but ultrawide 1920x800 has height=800)
  const selected = selectTracks(playlist);
  assert.equal(selected.video.resolution?.width, 1920);
  assert.equal(selected.video.resolution?.height, 800);
});

// ============================================================================
// PRIORITY 4: INTEGRATION TEST
// ============================================================================

// Mock media playlists for integration test
const MOCK_VIDEO_480 = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:10.0,
segment1.ts
#EXTINF:10.0,
segment2.ts
#EXT-X-ENDLIST`;

const MOCK_AUDIO_ENG_480 = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:10.0,
audio-segment1.m4a
#EXTINF:10.0,
audio-segment2.m4a
#EXT-X-ENDLIST`;

const MOCK_SUBTITLE_ENG = `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:10.0,
subtitle-segment1.vtt
#EXTINF:10.0,
subtitle-segment2.vtt
#EXT-X-ENDLIST`;

test("buildDownloadPlan handles cdn2cdn-style master playlist end-to-end", async () => {
  // ARRANGE
  const masterUrl = "https://cdn2cdn.com/hls4/base64/22657.m3u8?loc=nl";

  const mockFetcher = async (url, _headers) => {
    // Return master playlist
    if (url === masterUrl) {
      return CDN2CDN_MASTER;
    }

    // Return 480p video media playlist
    if (url.includes("480/video.m3u8")) {
      return MOCK_VIDEO_480;
    }

    // Return English audio from audio480 group
    if (url.includes("audio/eng-480.m3u8")) {
      return MOCK_AUDIO_ENG_480;
    }

    // Return English subtitle
    if (url.includes("subs/eng.m3u8")) {
      return MOCK_SUBTITLE_ENG;
    }

    throw new Error(`Unexpected URL requested: ${url}`);
  };

  // ACT
  const plan = await buildDownloadPlan({
    id: "cdn2cdn-integration-test",
    masterPlaylistUri: masterUrl,
    fetcher: mockFetcher,
  });

  // ASSERT - Plan structure
  assert.equal(plan.id, "cdn2cdn-integration-test", "Plan ID should match");
  assert.equal(plan.masterPlaylistUri, masterUrl, "Master URI should be preserved");

  // Verify video track selected (480p - lowest variant)
  assert.equal(plan.tracks.video.resolution?.width, 720);
  assert.equal(plan.tracks.video.resolution?.height, 300);
  assert.equal(plan.video.segments.length, 2, "Should have 2 video segments");

  // Verify audio track selected (English from audio480 group)
  assert.ok(plan.tracks.audio, "Audio track should be selected");
  assert.equal(plan.tracks.audio.language, "eng");
  assert.ok(plan.audio, "Audio TrackPlan should exist");
  assert.equal(plan.audio.segments.length, 2, "Should have 2 audio segments");

  // Verify subtitle track selected (English)
  assert.ok(plan.tracks.subtitle, "Subtitle track should be selected");
  assert.equal(plan.tracks.subtitle.language, "eng");
  assert.ok(plan.subtitles, "Subtitles TrackPlan should exist");
  assert.equal(plan.subtitles.segments.length, 2, "Should have 2 subtitle segments");

  // Verify JSON serialization
  let planJson;
  assert.doesNotThrow(() => {
    planJson = JSON.stringify(plan);
  }, "Plan should be serializable to JSON");

  assert.ok(planJson, "Plan JSON should not be empty");
  assert.ok(planJson.length > 0, "Plan JSON should have content");

  // Verify no NaN or Infinity in JSON
  assert.ok(!planJson.includes("NaN"), "JSON should not contain NaN");
  assert.ok(!planJson.includes("Infinity"), "JSON should not contain Infinity");

  // Verify JSON can be parsed back
  let parsed;
  assert.doesNotThrow(() => {
    parsed = JSON.parse(planJson);
  }, "Plan JSON should be parseable");

  assert.equal(parsed.id, plan.id, "Parsed plan should have same ID");
  assert.equal(parsed.masterPlaylistUri, plan.masterPlaylistUri, "Parsed plan should have same master URI");
  assert.equal(parsed.video.segments.length, plan.video.segments.length, "Parsed plan should have same video segments");
  assert.equal(parsed.audio.segments.length, plan.audio.segments.length, "Parsed plan should have same audio segments");

  // Verify all segment durations are valid finite numbers
  plan.video.segments.forEach((segment, index) => {
    assert.ok(Number.isFinite(segment.duration), `Video segment ${index} duration should be finite`);
    assert.ok(segment.duration > 0, `Video segment ${index} duration should be positive`);
  });

  plan.audio.segments.forEach((segment, index) => {
    assert.ok(Number.isFinite(segment.duration), `Audio segment ${index} duration should be finite`);
    assert.ok(segment.duration > 0, `Audio segment ${index} duration should be positive`);
  });
});

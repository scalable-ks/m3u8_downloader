import test from "node:test";
import assert from "node:assert/strict";
import { detectDrmFromContent } from "../src/domain/drm.ts";

test("detectDrmFromContent flags sample-aes and widevine", () => {
  // ARRANGE
  const content = `#EXTM3U
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine"
#EXTINF:6.0,
seg-1.ts
`;

  // ACT
  const result = detectDrmFromContent(content);

  // ASSERT
  assert.equal(result.hasDrm, true);
  assert.ok(result.systems.includes("sample-aes"));
  assert.ok(result.systems.includes("widevine"));
});

test("detectDrmFromContent ignores identity keyformat", () => {
  // ARRANGE
  const content = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,KEYFORMAT="identity"
#EXTINF:6.0,
seg-1.ts
`;

  // ACT
  const result = detectDrmFromContent(content);

  // ASSERT
  assert.equal(result.hasDrm, false);
  assert.equal(result.systems.length, 0);
});

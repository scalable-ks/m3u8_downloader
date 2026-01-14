import test from "node:test";
import assert from "node:assert/strict";
import { validatePlaylistUrl } from "../src/ui/validation.ts";

// URL validation is critical for security and stability:
// - Prevents crashes from malformed URLs (Bug #8 fix)
// - Enforces http/https only (blocks file://, ftp://, etc.)
// - User-facing: displays clear error message for invalid URLs

const cases = [
  {
    name: "accepts valid http URL",
    url: "http://example.com/playlist.m3u8",
    want: true,
  },
  {
    name: "accepts valid https URL",
    url: "https://example.com/playlist.m3u8",
    want: true,
  },
  {
    name: "accepts URL with port",
    url: "https://example.com:8080/playlist.m3u8",
    want: true,
  },
  {
    name: "accepts URL with query parameters",
    url: "https://example.com/playlist.m3u8?token=abc123",
    want: true,
  },
  {
    name: "rejects file:// protocol",
    url: "file:///path/to/playlist.m3u8",
    want: false,
  },
  {
    name: "rejects ftp:// protocol",
    url: "ftp://example.com/playlist.m3u8",
    want: false,
  },
  {
    name: "rejects malformed URL",
    url: "not a valid url",
    want: false,
  },
  {
    name: "rejects empty string",
    url: "",
    want: false,
  },
  {
    name: "rejects URL with only protocol",
    url: "http://",
    want: false,
  },
  {
    name: "rejects null input",
    url: null,
    want: false,
  },
  {
    name: "rejects undefined input",
    url: undefined,
    want: false,
  },
  {
    name: "rejects number input",
    url: 12345,
    want: false,
  },
  {
    name: "accepts object that stringifies to valid URL",
    url: { toString: () => "http://example.com" },
    want: true,
  },
  {
    name: "accepts array that stringifies to valid URL",
    url: ["http://example.com"],
    want: true,
  },
  {
    name: "rejects object that stringifies to invalid URL",
    url: { toString: () => "not a url" },
    want: false,
  },
];

for (const tc of cases) {
  test(`validatePlaylistUrl: ${tc.name}`, () => {
    // ARRANGE
    const url = tc.url;

    // ACT
    const result = validatePlaylistUrl(url);

    // ASSERT
    assert.equal(result, tc.want);
  });
}

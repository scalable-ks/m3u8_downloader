import test from "node:test";
import assert from "node:assert/strict";
import { validatePlaylistUrl } from "../src/ui/validation.ts";

test("validatePlaylistUrl: accepts valid http URL", () => {
  // ARRANGE
  const url = "http://example.com/playlist.m3u8";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, true);
});

test("validatePlaylistUrl: accepts valid https URL", () => {
  // ARRANGE
  const url = "https://example.com/playlist.m3u8";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, true);
});

test("validatePlaylistUrl: rejects file:// protocol", () => {
  // ARRANGE
  const url = "file:///path/to/playlist.m3u8";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, false);
});

test("validatePlaylistUrl: rejects ftp:// protocol", () => {
  // ARRANGE
  const url = "ftp://example.com/playlist.m3u8";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, false);
});

test("validatePlaylistUrl: rejects malformed URL", () => {
  // ARRANGE
  const url = "not a valid url";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, false);
});

test("validatePlaylistUrl: rejects empty string", () => {
  // ARRANGE
  const url = "";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, false);
});

test("validatePlaylistUrl: rejects URL with only protocol", () => {
  // ARRANGE
  const url = "http://";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, false);
});

test("validatePlaylistUrl: accepts URL with port", () => {
  // ARRANGE
  const url = "https://example.com:8080/playlist.m3u8";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, true);
});

test("validatePlaylistUrl: accepts URL with query parameters", () => {
  // ARRANGE
  const url = "https://example.com/playlist.m3u8?token=abc123";

  // ACT
  const result = validatePlaylistUrl(url);

  // ASSERT
  assert.equal(result, true);
});

import test from "node:test";
import assert from "node:assert/strict";
import { parseCookiesInput, parseHeadersInput } from "../RnAndroidHlsApp/src/ui/auth.ts";

test("parseHeadersInput parses JSON object", () => {
  // ARRANGE
  const input = '{"Authorization":"Bearer token"}';

  // ACT
  const headers = parseHeadersInput(input);

  // ASSERT
  assert.deepEqual(headers, { Authorization: "Bearer token" });
});

test("parseCookiesInput accepts raw cookie string", () => {
  // ARRANGE
  const input = "session=abc";

  // ACT
  const cookies = parseCookiesInput(input);

  // ASSERT
  assert.equal(cookies, "session=abc");
});

test("parseCookiesInput parses JSON object", () => {
  // ARRANGE
  const input = '{"session":"abc"}';

  // ACT
  const cookies = parseCookiesInput(input);

  // ASSERT
  assert.deepEqual(cookies, { session: "abc" });
});

test("parseHeadersInput throws on invalid JSON", () => {
  // ARRANGE
  const input = "not-json";

  // ACT + ASSERT
  assert.throws(() => parseHeadersInput(input), /Unexpected token/);
});

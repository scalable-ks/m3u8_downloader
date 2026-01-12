import type { CookieInput } from "../bridge/plan.ts";

function parseJsonObject(input: string): Record<string, string> {
  const parsed = JSON.parse(input);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Value must be a JSON object.");
  }
  const output: Record<string, string> = {};
  for (const [key, value] of Object.entries(parsed)) {
    output[key] = String(value);
  }
  return output;
}

export function parseHeadersInput(input: string): Record<string, string> | undefined {
  const trimmed = input.trim();
  if (!trimmed) {
    return undefined;
  }
  return parseJsonObject(trimmed);
}

export function parseCookiesInput(input: string): CookieInput | undefined {
  const trimmed = input.trim();
  if (!trimmed) {
    return undefined;
  }
  if (trimmed.startsWith("{")) {
    return parseJsonObject(trimmed);
  }
  return trimmed;
}

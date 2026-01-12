const NOT_FOUND = -1;
const KEYFORMAT_IDENTITY = "identity";
const KEY_SPLIT_PARTS = 2;
const DRM_METHODS = new Set(["SAMPLE-AES", "SAMPLE-AES-CTR"]);

const DRM_KEYFORMATS = new Map([
  ["com.widevine", "widevine"],
  ["com.microsoft.playready", "playready"],
  ["com.apple.fps", "fairplay"],
]);

function splitAttributes(raw: string): string[] {
  const parts: string[] = [];
  let current = "";
  let inQuotes = false;
  for (let i = 0; i < raw.length; i += 1) {
    const ch = raw[i];
    if (ch === '"') {
      inQuotes = !inQuotes;
      current += ch;
      continue;
    }
    if (ch === "," && !inQuotes) {
      parts.push(current);
      current = "";
      continue;
    }
    current += ch;
  }
  if (current.length > 0) {
    parts.push(current);
  }
  return parts;
}

function parseAttributes(raw: string): Record<string, string> {
  const attrs: Record<string, string> = {};
  for (const part of splitAttributes(raw)) {
    const idx = part.indexOf("=");
    if (idx === NOT_FOUND) {
      continue;
    }
    const key = part.slice(0, idx).trim();
    let value = part.slice(idx + 1).trim();
    if (value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    }
    attrs[key] = value;
  }
  return attrs;
}

function* iterateLines(content: string): IterableIterator<string> {
  let start = 0;
  for (let i = 0; i <= content.length; i += 1) {
    if (i === content.length || content[i] === "\n") {
      let line = content.slice(start, i);
      if (line.endsWith("\r")) {
        line = line.slice(0, -1);
      }
      line = line.trim();
      if (line.length > 0) {
        yield line;
      }
      start = i + 1;
    }
  }
}

export interface DrmDetection {
  hasDrm: boolean;
  systems: string[];
}

function normalizeKeyformat(value?: string): string | undefined {
  if (!value) {
    return undefined;
  }
  return value.trim().toLowerCase();
}

export function detectDrmFromContent(content: string): DrmDetection {
  const systems = new Set<string>();

  for (const line of iterateLines(content)) {
    if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
      const attrs = parseAttributes(line.split(":", KEY_SPLIT_PARTS)[1] ?? "");
      const method = attrs.METHOD?.toUpperCase();
      if (method && DRM_METHODS.has(method)) {
        systems.add("sample-aes");
      }
      const keyformat = normalizeKeyformat(attrs.KEYFORMAT);
      if (keyformat && keyformat !== KEYFORMAT_IDENTITY) {
        let matched = false;
        for (const [prefix, system] of DRM_KEYFORMATS.entries()) {
          if (keyformat.includes(prefix)) {
            systems.add(system);
            matched = true;
          }
        }
        if (!matched) {
          systems.add("unknown");
        }
      }
    }
  }

  return {
    hasDrm: systems.size > 0,
    systems: Array.from(systems.values()),
  };
}

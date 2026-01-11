import { resolveUri } from "./m3u8.ts";

export interface ByteRange {
  length: number;
  offset?: number;
}

export interface KeyInfo {
  method: string;
  uri?: string;
  iv?: string;
  keyFormat?: string;
  keyFormatVersions?: string;
}

export interface MapInfo {
  uri: string;
  byteRange?: ByteRange;
}

export interface Segment {
  uri: string;
  duration: number;
  title?: string;
  byteRange?: ByteRange;
  key?: KeyInfo;
  map?: MapInfo;
  sequence: number;
}

export interface MediaPlaylist {
  baseUri: string;
  targetDuration?: number;
  mediaSequence?: number;
  segments: Segment[];
  isLive: boolean;
}

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
    if (idx === -1) {
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

function parseByteRange(raw?: string): ByteRange | undefined {
  if (!raw) {
    return undefined;
  }
  const [lengthPart, offsetPart] = raw.split("@");
  const length = Number(lengthPart);
  if (!Number.isFinite(length)) {
    return undefined;
  }
  const offset = offsetPart !== undefined ? Number(offsetPart) : undefined;
  return Number.isFinite(offset) ? { length, offset } : { length };
}

function parseExtInf(raw: string): { duration: number; title?: string } | undefined {
  const trimmed = raw.trim();
  if (!trimmed) {
    return undefined;
  }
  const [durationText, title] = trimmed.split(/,(.*)/s);
  const duration = Number(durationText);
  if (!Number.isFinite(duration)) {
    return undefined;
  }
  return title ? { duration, title } : { duration };
}

export function parseMediaPlaylist(content: string, playlistUri: string): MediaPlaylist {
  const lines = content.split(/\r?\n/).map((line) => line.trim()).filter((line) => line.length > 0);
  const segments: Segment[] = [];

  let targetDuration: number | undefined;
  let mediaSequence = 0;
  let isLive = true;

  let currentKey: KeyInfo | undefined;
  let currentMap: MapInfo | undefined;
  let pendingByteRange: ByteRange | undefined;
  let pendingExtInf: { duration: number; title?: string } | undefined;

  let sequence = 0;

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];

    if (line.startsWith("#EXT-X-TARGETDURATION:")) {
      const value = Number(line.slice("#EXT-X-TARGETDURATION:".length));
      if (Number.isFinite(value)) {
        targetDuration = value;
      }
      continue;
    }

    if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
      const value = Number(line.slice("#EXT-X-MEDIA-SEQUENCE:".length));
      if (Number.isFinite(value)) {
        mediaSequence = value;
        sequence = value;
      }
      continue;
    }

    if (line.startsWith("#EXT-X-KEY:")) {
      const attrs = parseAttributes(line.slice("#EXT-X-KEY:".length));
      currentKey = {
        method: attrs.METHOD ?? "",
        uri: attrs.URI ? resolveUri(playlistUri, attrs.URI) : undefined,
        iv: attrs.IV,
        keyFormat: attrs.KEYFORMAT,
        keyFormatVersions: attrs.KEYFORMATVERSIONS,
      };
      continue;
    }

    if (line.startsWith("#EXT-X-MAP:")) {
      const attrs = parseAttributes(line.slice("#EXT-X-MAP:".length));
      const uri = attrs.URI ? resolveUri(playlistUri, attrs.URI) : undefined;
      if (uri) {
        currentMap = {
          uri,
          byteRange: parseByteRange(attrs.BYTERANGE),
        };
      }
      continue;
    }

    if (line.startsWith("#EXT-X-BYTERANGE:")) {
      pendingByteRange = parseByteRange(line.slice("#EXT-X-BYTERANGE:".length));
      continue;
    }

    if (line.startsWith("#EXTINF:")) {
      pendingExtInf = parseExtInf(line.slice("#EXTINF:".length));
      continue;
    }

    if (line.startsWith("#EXT-X-ENDLIST")) {
      isLive = false;
      continue;
    }

    if (line.startsWith("#")) {
      continue;
    }

    if (pendingExtInf) {
      const segment: Segment = {
        uri: resolveUri(playlistUri, line),
        duration: pendingExtInf.duration,
        title: pendingExtInf.title,
        byteRange: pendingByteRange,
        key: currentKey,
        map: currentMap,
        sequence,
      };
      segments.push(segment);
      pendingExtInf = undefined;
      pendingByteRange = undefined;
      sequence += 1;
    }
  }

  return {
    baseUri: playlistUri,
    targetDuration,
    mediaSequence,
    segments,
    isLive,
  };
}

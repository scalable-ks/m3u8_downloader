import { resolveUri } from "./m3u8.ts";

const NOT_FOUND = -1;
const DEFAULT_RELOAD_MS = 5000;
const MIN_RELOAD_MS = 1000;
const MS_PER_SECOND = 1000;

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

export function parseMediaPlaylist(content: string, playlistUri: string): MediaPlaylist {
  const segments: Segment[] = [];

  let targetDuration: number | undefined;
  let mediaSequence = 0;
  let isLive = true;

  let currentKey: KeyInfo | undefined;
  let currentMap: MapInfo | undefined;
  let pendingByteRange: ByteRange | undefined;
  let pendingExtInf: { duration: number; title?: string } | undefined;

  let sequence = 0;

  for (const line of iterateLines(content)) {
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

export function mergeLivePlaylist(
  previous: MediaPlaylist,
  next: MediaPlaylist,
): MediaPlaylist {
  if (!previous.isLive) {
    return next;
  }
  const combined = new Map<number, Segment>();
  for (const segment of previous.segments) {
    combined.set(segment.sequence, segment);
  }
  for (const segment of next.segments) {
    combined.set(segment.sequence, segment);
  }
  const minSequence =
    next.mediaSequence ?? next.segments[0]?.sequence ?? previous.segments[0]?.sequence;
  const mergedSegments = Array.from(combined.values())
    .filter((segment) => (minSequence !== undefined ? segment.sequence >= minSequence : true))
    .sort((a, b) => a.sequence - b.sequence);
  return {
    ...next,
    segments: mergedSegments,
  };
}

export function nextReloadDelayMs(playlist: MediaPlaylist): number {
  if (!playlist.isLive) {
    return 0;
  }
  const target = playlist.targetDuration ? playlist.targetDuration * MS_PER_SECOND : undefined;
  const lastDuration =
    playlist.segments.length > 0
      ? playlist.segments[playlist.segments.length - 1].duration * MS_PER_SECOND
      : undefined;
  const candidate = target ?? lastDuration ?? DEFAULT_RELOAD_MS;
  return Math.max(candidate, MIN_RELOAD_MS);
}

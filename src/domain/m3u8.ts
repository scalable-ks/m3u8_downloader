import type { MasterPlaylist, MediaTrack, Resolution, Variant } from "./types.ts";

const NOT_FOUND = -1;

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

function parseResolution(value?: string): Resolution | undefined {
  if (!value) {
    return undefined;
  }
  const match = value.match(/^(\d+)x(\d+)$/i);
  if (!match) {
    return undefined;
  }
  return { width: Number(match[1]), height: Number(match[2]) };
}

export function resolveUri(baseUri: string, ref: string): string {
  return new URL(ref, baseUri).toString();
}

export function parseMasterPlaylist(content: string, playlistUri: string): MasterPlaylist {
  const lines = content.split(/\r?\n/).map((line) => line.trim()).filter((line) => line.length > 0);
  const variants: Variant[] = [];
  const audios: MediaTrack[] = [];
  const subtitles: MediaTrack[] = [];

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];

    if (line.startsWith("#EXT-X-STREAM-INF:")) {
      const attrText = line.slice("#EXT-X-STREAM-INF:".length);
      const attrs = parseAttributes(attrText);
      const nextUriLine = lines[i + 1];
      if (!nextUriLine || nextUriLine.startsWith("#")) {
        continue;
      }
      const variant: Variant = {
        uri: resolveUri(playlistUri, nextUriLine),
        bandwidth: attrs.BANDWIDTH ? Number(attrs.BANDWIDTH) : undefined,
        resolution: parseResolution(attrs.RESOLUTION),
        codecs: attrs.CODECS,
        audioGroupId: attrs.AUDIO,
        subtitleGroupId: attrs.SUBTITLES,
      };
      variants.push(variant);
      i += 1;
      continue;
    }

    if (line.startsWith("#EXT-X-MEDIA:")) {
      const attrText = line.slice("#EXT-X-MEDIA:".length);
      const attrs = parseAttributes(attrText);
      const type = attrs.TYPE;
      const uri = attrs.URI;
      if (!uri || !type) {
        continue;
      }
      const track: MediaTrack = {
        groupId: attrs["GROUP-ID"] ?? "",
        name: attrs.NAME,
        language: attrs.LANGUAGE,
        uri: resolveUri(playlistUri, uri),
        isDefault: attrs.DEFAULT === "YES",
        isAutoSelect: attrs.AUTOSELECT === "YES",
      };
      if (type === "AUDIO") {
        audios.push(track);
      } else if (type === "SUBTITLES") {
        subtitles.push(track);
      }
    }
  }

  return {
    baseUri: playlistUri,
    variants,
    audios,
    subtitles,
  };
}

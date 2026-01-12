import { parseMasterPlaylist } from "../domain/m3u8.ts";
import { parseMediaPlaylist } from "../domain/media.ts";
import { selectTracks } from "../domain/selection.ts";
import type { MasterPlaylist } from "../domain/types.ts";
import type { DownloadPlan, TrackPlan } from "./models.ts";

const COOKIE_SEPARATOR = "; ";

export type CookieInput = string | Record<string, string>;

export interface PlanOptions {
  id: string;
  masterPlaylistUri: string;
  headers?: Record<string, string>;
  cookies?: CookieInput;
  constraints?: DownloadPlan["constraints"];
  cleanupPolicy?: DownloadPlan["cleanupPolicy"];
  exportTreeUri?: DownloadPlan["exportTreeUri"];
  fetcher?: (url: string, headers: Record<string, string>) => Promise<string>;
}

function mergeHeaders(
  headers: Record<string, string> | undefined,
  cookies: CookieInput | undefined,
): Record<string, string> {
  const merged: Record<string, string> = { ...(headers ?? {}) };
  if (!cookies) {
    return merged;
  }
  if (typeof cookies === "string") {
    merged.Cookie = cookies;
    return merged;
  }
  const cookiePairs = Object.entries(cookies).map(([name, value]) => `${name}=${value}`);
  merged.Cookie = cookiePairs.join(COOKIE_SEPARATOR);
  return merged;
}

async function fetchText(url: string, headers: Record<string, string>): Promise<string> {
  const response = await fetch(url, { headers });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`);
  }
  return response.text();
}

async function fetchPlaylist(
  url: string,
  headers: Record<string, string>,
  fetcher?: (url: string, headers: Record<string, string>) => Promise<string>,
): Promise<string> {
  if (fetcher) {
    return fetcher(url, headers);
  }
  return fetchText(url, headers);
}

function buildTrackPlan(playlistUri: string, content: string): TrackPlan {
  const playlist = parseMediaPlaylist(content, playlistUri);
  return {
    playlistUri,
    segments: playlist.segments,
  };
}

function loadMaster(content: string, uri: string): MasterPlaylist {
  return parseMasterPlaylist(content, uri);
}

export async function buildDownloadPlan(options: PlanOptions): Promise<DownloadPlan> {
  const headers = mergeHeaders(options.headers, options.cookies);
  const masterContent = await fetchPlaylist(options.masterPlaylistUri, headers, options.fetcher);
  const master = loadMaster(masterContent, options.masterPlaylistUri);
  const tracks = selectTracks(master);

  const videoContent = await fetchPlaylist(tracks.video.uri, headers, options.fetcher);
  const video = buildTrackPlan(tracks.video.uri, videoContent);

  const audio =
    tracks.audio?.uri
      ? buildTrackPlan(tracks.audio.uri, await fetchPlaylist(tracks.audio.uri, headers, options.fetcher))
      : undefined;

  const subtitles =
    tracks.subtitle?.uri
      ? buildTrackPlan(
          tracks.subtitle.uri,
          await fetchPlaylist(tracks.subtitle.uri, headers, options.fetcher),
        )
      : undefined;

  return {
    id: options.id,
    masterPlaylistUri: options.masterPlaylistUri,
    tracks,
    video,
    audio,
    subtitles,
    headers,
    constraints: options.constraints,
    cleanupPolicy: options.cleanupPolicy,
    exportTreeUri: options.exportTreeUri,
  };
}

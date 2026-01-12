import { parseMasterPlaylist } from "../domain/m3u8.ts";
import { mergeLivePlaylist, nextReloadDelayMs, parseMediaPlaylist } from "../domain/media.ts";
import { selectTracks } from "../domain/selection.ts";
import type { MasterPlaylist } from "../domain/types.ts";
import type { DownloadPlan, TrackPlan } from "./models.ts";

const COOKIE_SEPARATOR = "; ";
const DEFAULT_LIVE_REFRESH_LIMIT = 5;

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
  sleep?: (ms: number) => Promise<void>;
  liveRefreshLimit?: number;
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


function loadMaster(content: string, uri: string): MasterPlaylist {
  return parseMasterPlaylist(content, uri);
}

export async function buildDownloadPlan(options: PlanOptions): Promise<DownloadPlan> {
  const headers = mergeHeaders(options.headers, options.cookies);
  const masterContent = await fetchPlaylist(options.masterPlaylistUri, headers, options.fetcher);
  const master = loadMaster(masterContent, options.masterPlaylistUri);
  const tracks = selectTracks(master);

  const videoContent = await fetchPlaylist(tracks.video.uri, headers, options.fetcher);
  const video = await buildTrackPlanWithLiveRefresh(tracks.video.uri, videoContent, headers, options);

  const audio =
    tracks.audio?.uri
      ? await buildTrackPlanWithLiveRefresh(
          tracks.audio.uri,
          await fetchPlaylist(tracks.audio.uri, headers, options.fetcher),
          headers,
          options,
        )
      : undefined;

  const subtitles =
    tracks.subtitle?.uri
      ? await buildTrackPlanWithLiveRefresh(
          tracks.subtitle.uri,
          await fetchPlaylist(tracks.subtitle.uri, headers, options.fetcher),
          headers,
          options,
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

async function buildTrackPlanWithLiveRefresh(
  playlistUri: string,
  content: string,
  headers: Record<string, string>,
  options: PlanOptions,
): Promise<TrackPlan> {
  let playlist = parseMediaPlaylist(content, playlistUri);
  if (playlist.isLive) {
    const sleep = options.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
    const limit = options.liveRefreshLimit ?? DEFAULT_LIVE_REFRESH_LIMIT;
    let refreshes = 0;
    while (playlist.isLive && refreshes < limit) {
      await sleep(nextReloadDelayMs(playlist));
      const refreshedContent = await fetchPlaylist(playlistUri, headers, options.fetcher);
      const refreshed = parseMediaPlaylist(refreshedContent, playlistUri);
      playlist = mergeLivePlaylist(playlist, refreshed);
      refreshes += 1;
    }
  }
  return {
    playlistUri,
    segments: playlist.segments,
  };
}

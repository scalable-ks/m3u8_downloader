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

  let masterContent: string;
  let master: MasterPlaylist;

  try {
    console.log("Fetching master playlist:", options.masterPlaylistUri);
    masterContent = await fetchPlaylist(options.masterPlaylistUri, headers, options.fetcher);
    console.log("Master playlist fetched, size:", masterContent.length, "bytes");
  } catch (error) {
    throw new Error(`Failed to fetch master playlist from ${options.masterPlaylistUri}: ${error instanceof Error ? error.message : String(error)}`);
  }

  try {
    master = loadMaster(masterContent, options.masterPlaylistUri);
    console.log("Master playlist parsed successfully");
  } catch (error) {
    throw new Error(`Failed to parse master playlist: ${error instanceof Error ? error.message : String(error)}`);
  }

  const tracks = selectTracks(master);
  console.log("Tracks selected - video:", tracks.video.uri, "audio:", tracks.audio?.uri ?? "none", "subtitle:", tracks.subtitle?.uri ?? "none");

  let videoContent: string;
  try {
    videoContent = await fetchPlaylist(tracks.video.uri, headers, options.fetcher);
    console.log("Video playlist fetched, size:", videoContent.length, "bytes");
  } catch (error) {
    throw new Error(`Failed to fetch video playlist from ${tracks.video.uri}: ${error instanceof Error ? error.message : String(error)}`);
  }

  let video: TrackPlan;
  try {
    video = await buildTrackPlanWithLiveRefresh(tracks.video.uri, videoContent, headers, options);
    console.log("Video track plan built, segments:", video.segments.length);
  } catch (error) {
    throw new Error(`Failed to build video track plan: ${error instanceof Error ? error.message : String(error)}`);
  }

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

  console.log("Download plan built successfully - total segments:", video.segments.length + (audio?.segments.length ?? 0) + (subtitles?.segments.length ?? 0));

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

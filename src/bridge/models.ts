import type { Segment } from "../domain/media.ts";
import type { SelectedTracks } from "../domain/types.ts";

export interface JobConstraints {
  requiresUnmetered?: boolean;
  requiresCharging?: boolean;
  requiresIdle?: boolean;
  requiresStorageNotLow?: boolean;
}

export interface CleanupPolicy {
  deleteOnFailure?: boolean;
  deleteOnCancel?: boolean;
  deleteOnSuccess?: boolean;
}

export interface TrackPlan {
  playlistUri: string;
  segments: Segment[];
}

export interface DownloadPlan {
  id: string;
  masterPlaylistUri: string;
  tracks: SelectedTracks;
  video: TrackPlan;
  audio?: TrackPlan;
  subtitles?: TrackPlan;
  headers?: Record<string, string>;
  constraints?: JobConstraints;
  cleanupPolicy?: CleanupPolicy;
  exportTreeUri?: string;
}

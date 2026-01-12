export type LanguageCode = string;

export interface Resolution {
  width: number;
  height: number;
}

export interface Variant {
  uri: string;
  bandwidth?: number;
  resolution?: Resolution;
  codecs?: string;
  audioGroupId?: string;
  subtitleGroupId?: string;
}

export interface MediaTrack {
  groupId: string;
  name?: string;
  language?: LanguageCode;
  uri: string;
  isDefault?: boolean;
  isAutoSelect?: boolean;
}

export interface MasterPlaylist {
  baseUri: string;
  variants: Variant[];
  audios: MediaTrack[];
  subtitles: MediaTrack[];
}

export interface SelectedTracks {
  video: Variant;
  audio?: MediaTrack;
  subtitle?: MediaTrack;
}

export type JobState = "queued" | "running" | "paused" | "completed" | "failed" | "canceled";

export interface JobProgress {
  bytesDownloaded: number;
  totalBytes?: number;
  segmentsDownloaded: number;
  totalSegments?: number;
}

export interface DownloadJob {
  id: string;
  masterPlaylistUri: string;
  createdAt: number;
  state: JobState;
  progress: JobProgress;
}

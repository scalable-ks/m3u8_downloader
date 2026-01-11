import type { MasterPlaylist, MediaTrack, SelectedTracks, Variant } from "./types.ts";

const AUDIO_LANGUAGE_PRIORITY = ["eng", "spa", "esp"] as const;

type AudioLanguage = (typeof AUDIO_LANGUAGE_PRIORITY)[number];

function normalizeLanguage(lang?: string): string | undefined {
  return lang?.trim().toLowerCase();
}

function matchesLanguage(lang: string, preferred: AudioLanguage): boolean {
  const normalized = normalizeLanguage(lang);
  if (!normalized) {
    return false;
  }
  if (preferred === "eng") {
    return normalized === "eng" || normalized === "en" || normalized.startsWith("en-");
  }
  if (preferred === "spa") {
    return normalized === "spa" || normalized === "es" || normalized.startsWith("es-");
  }
  if (preferred === "esp") {
    return normalized === "esp";
  }
  return normalized === preferred || normalized.startsWith(`${preferred}-`);
}

function pickLowestQualityVariant(variants: Variant[]): Variant {
  if (variants.length === 0) {
    throw new Error("No variants in master playlist.");
  }
  return variants.reduce((lowest, current) => {
    const currentHeight = current.resolution?.height ?? Number.POSITIVE_INFINITY;
    const lowestHeight = lowest.resolution?.height ?? Number.POSITIVE_INFINITY;
    if (currentHeight !== lowestHeight) {
      return currentHeight < lowestHeight ? current : lowest;
    }
    const currentBandwidth = current.bandwidth ?? Number.POSITIVE_INFINITY;
    const lowestBandwidth = lowest.bandwidth ?? Number.POSITIVE_INFINITY;
    return currentBandwidth < lowestBandwidth ? current : lowest;
  });
}

function filterByGroup(tracks: MediaTrack[], groupId?: string): MediaTrack[] {
  if (!groupId) {
    return tracks;
  }
  const grouped = tracks.filter((track) => track.groupId === groupId);
  return grouped.length > 0 ? grouped : tracks;
}

function pickAudioTrack(tracks: MediaTrack[], groupId?: string): MediaTrack | undefined {
  const candidates = filterByGroup(tracks, groupId);
  for (const preferred of AUDIO_LANGUAGE_PRIORITY) {
    const match = candidates.find((track) => track.language && matchesLanguage(track.language, preferred));
    if (match) {
      return match;
    }
  }
  return candidates[0];
}

function pickSubtitleTrack(
  tracks: MediaTrack[],
  groupId: string | undefined,
  audioLanguage?: string,
): MediaTrack | undefined {
  if (!audioLanguage) {
    return undefined;
  }
  const candidates = filterByGroup(tracks, groupId);
  const match = candidates.find((track) => track.language && normalizeLanguage(track.language) === normalizeLanguage(audioLanguage));
  return match;
}

export function selectTracks(master: MasterPlaylist): SelectedTracks {
  const video = pickLowestQualityVariant(master.variants);
  const audio = pickAudioTrack(master.audios, video.audioGroupId);
  const subtitle = pickSubtitleTrack(master.subtitles, video.subtitleGroupId, audio?.language);
  return { video, audio, subtitle };
}

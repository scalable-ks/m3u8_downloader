import { selectTracks } from '../selection';
import type { MasterPlaylist, Variant, MediaTrack } from '../types';

describe('Track Selection', () => {
  describe('selectTracks', () => {
    it('should select lowest quality variant by resolution', () => {
      const master: MasterPlaylist = {
        variants: [
          { bandwidth: 5000000, resolution: { width: 1920, height: 1080 }, uri: 'https://example.com/1080p.m3u8' },
          { bandwidth: 3000000, resolution: { width: 1280, height: 720 }, uri: 'https://example.com/720p.m3u8' },
          { bandwidth: 1000000, resolution: { width: 640, height: 480 }, uri: 'https://example.com/480p.m3u8' },
        ],
        audios: [],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.video.resolution?.height).toBe(480);
      expect(result.video.uri).toBe('https://example.com/480p.m3u8');
    });

    it('should select lowest bandwidth when resolutions are equal', () => {
      const master: MasterPlaylist = {
        variants: [
          { bandwidth: 3000000, resolution: { width: 1280, height: 720 }, uri: 'https://example.com/720p-high.m3u8' },
          { bandwidth: 2000000, resolution: { width: 1280, height: 720 }, uri: 'https://example.com/720p-medium.m3u8' },
          { bandwidth: 1500000, resolution: { width: 1280, height: 720 }, uri: 'https://example.com/720p-low.m3u8' },
        ],
        audios: [],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.video.bandwidth).toBe(1500000);
      expect(result.video.uri).toBe('https://example.com/720p-low.m3u8');
    });

    it('should throw when no variants exist', () => {
      const master: MasterPlaylist = {
        variants: [],
        audios: [],
        subtitles: [],
      };

      expect(() => selectTracks(master)).toThrow('No variants in master playlist.');
    });

    it('should prefer English audio (eng, en, en-US)', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'Español', language: 'es', uri: 'https://example.com/audio-es.m3u8' },
          { groupId: 'audio', name: 'English', language: 'en-US', uri: 'https://example.com/audio-en.m3u8' },
          { groupId: 'audio', name: 'Français', language: 'fr', uri: 'https://example.com/audio-fr.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('en-US');
      expect(result.audio?.uri).toBe('https://example.com/audio-en.m3u8');
    });

    it('should prefer eng language code', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'Spanish', language: 'es', uri: 'https://example.com/audio-es.m3u8' },
          { groupId: 'audio', name: 'English', language: 'eng', uri: 'https://example.com/audio-eng.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('eng');
      expect(result.audio?.uri).toBe('https://example.com/audio-eng.m3u8');
    });

    it('should fallback to Spanish if no English (spa, es, es-MX)', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'Français', language: 'fr', uri: 'https://example.com/audio-fr.m3u8' },
          { groupId: 'audio', name: 'Español (México)', language: 'es-MX', uri: 'https://example.com/audio-es.m3u8' },
          { groupId: 'audio', name: 'Deutsch', language: 'de', uri: 'https://example.com/audio-de.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('es-MX');
      expect(result.audio?.uri).toBe('https://example.com/audio-es.m3u8');
    });

    it('should fallback to spa language code', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'French', language: 'fr', uri: 'https://example.com/audio-fr.m3u8' },
          { groupId: 'audio', name: 'Spanish', language: 'spa', uri: 'https://example.com/audio-spa.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('spa');
    });

    it('should fallback to first audio if no priority match', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: '日本語', language: 'ja', uri: 'https://example.com/audio-ja.m3u8' },
          { groupId: 'audio', name: '한국어', language: 'ko', uri: 'https://example.com/audio-ko.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('ja');
      expect(result.audio?.uri).toBe('https://example.com/audio-ja.m3u8');
    });

    it('should pair subtitles with selected audio language', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio', subtitleGroupId: 'subs' }],
        audios: [
          { groupId: 'audio', name: 'English', language: 'eng', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [
          { groupId: 'subs', name: 'Español', language: 'es', uri: 'https://example.com/subs-es.m3u8' },
          { groupId: 'subs', name: 'English', language: 'eng', uri: 'https://example.com/subs-en.m3u8' },
          { groupId: 'subs', name: 'Français', language: 'fr', uri: 'https://example.com/subs-fr.m3u8' },
        ],
      };

      const result = selectTracks(master);

      expect(result.subtitle?.language).toBe('eng');
      expect(result.subtitle?.uri).toBe('https://example.com/subs-en.m3u8');
    });

    it('should match Spanish audio with Spanish subtitles', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio', subtitleGroupId: 'subs' }],
        audios: [
          { groupId: 'audio', name: 'Español', language: 'es-MX', uri: 'https://example.com/audio-es.m3u8' },
        ],
        subtitles: [
          { groupId: 'subs', name: 'English', language: 'eng', uri: 'https://example.com/subs-en.m3u8' },
          { groupId: 'subs', name: 'Español', language: 'spa', uri: 'https://example.com/subs-es.m3u8' },
        ],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('es-MX');
      expect(result.subtitle?.language).toBe('spa');
      expect(result.subtitle?.uri).toBe('https://example.com/subs-es.m3u8');
    });

    it('should handle missing audio group gracefully', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio1' }],
        audios: [
          { groupId: 'audio2', name: 'English', language: 'en', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio).toBeUndefined();
    });

    it('should handle missing subtitle group gracefully', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio', subtitleGroupId: 'subs1' }],
        audios: [
          { groupId: 'audio', name: 'English', language: 'eng', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [
          { groupId: 'subs2', name: 'English', language: 'eng', uri: 'https://example.com/subs-en.m3u8' },
        ],
      };

      const result = selectTracks(master);

      expect(result.audio).toBeDefined();
      expect(result.subtitle).toBeUndefined();
    });

    it('should return undefined subtitle when no subtitle group specified', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'English', language: 'eng', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [
          { groupId: 'subs', name: 'English', language: 'eng', uri: 'https://example.com/subs-en.m3u8' },
        ],
      };

      const result = selectTracks(master);

      expect(result.subtitle).toBeUndefined();
    });

    it('should handle missing audio tracks list', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8' }],
        audios: [],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.video).toBeDefined();
      expect(result.audio).toBeUndefined();
      expect(result.subtitle).toBeUndefined();
    });

    it('should canonicalize language codes (en → eng, es → spa)', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'English', language: 'en', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      // 'en' should match 'eng' priority
      expect(result.audio?.language).toBe('en');
      expect(result.audio?.uri).toBe('https://example.com/audio-en.m3u8');
    });

    it('should match en-US variant to eng', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'German', language: 'de', uri: 'https://example.com/audio-de.m3u8' },
          { groupId: 'audio', name: 'English (US)', language: 'en-US', uri: 'https://example.com/audio-en-us.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('en-US');
    });

    it('should handle case-insensitive language matching', () => {
      const master: MasterPlaylist = {
        variants: [{ bandwidth: 1000000, uri: 'https://example.com/video.m3u8', audioGroupId: 'audio' }],
        audios: [
          { groupId: 'audio', name: 'English', language: 'EN', uri: 'https://example.com/audio-en.m3u8' },
        ],
        subtitles: [],
      };

      const result = selectTracks(master);

      expect(result.audio?.language).toBe('EN');
    });
  });
});

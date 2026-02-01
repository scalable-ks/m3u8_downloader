import { parseMasterPlaylist, parseAttributes, resolveUri } from '../m3u8';

describe('M3U8 Parser', () => {
  describe('parseMasterPlaylist', () => {
    it('should parse basic master playlist', () => {
      const playlist = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
high.m3u8`;

      const result = parseMasterPlaylist(playlist, 'https://example.com/master.m3u8');

      expect(result.variants).toHaveLength(2);
      expect(result.variants[0]).toMatchObject({
        bandwidth: 1280000,
        resolution: '640x360',
        uri: 'https://example.com/low.m3u8',
      });
      expect(result.variants[1]).toMatchObject({
        bandwidth: 2560000,
        resolution: '1280x720',
        uri: 'https://example.com/high.m3u8',
      });
    });

    it('should parse playlist with audio groups', () => {
      const playlist = `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",URI="audio-en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",LANGUAGE="es",URI="audio-es.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO="audio"
video.m3u8`;

      const result = parseMasterPlaylist(playlist, 'https://example.com/master.m3u8');

      expect(result.audioTracks).toHaveLength(2);
      expect(result.audioTracks[0]).toMatchObject({
        groupId: 'audio',
        name: 'English',
        language: 'en',
        uri: 'https://example.com/audio-en.m3u8',
      });
    });

    it('should parse playlist with subtitles', () => {
      const playlist = `#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs-en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1280000,SUBTITLES="subs"
video.m3u8`;

      const result = parseMasterPlaylist(playlist, 'https://example.com/master.m3u8');

      expect(result.subtitleTracks).toHaveLength(1);
      expect(result.subtitleTracks[0]).toMatchObject({
        groupId: 'subs',
        name: 'English',
        language: 'en',
        uri: 'https://example.com/subs-en.m3u8',
      });
    });

    it('should handle empty playlist', () => {
      const playlist = '#EXTM3U';
      const result = parseMasterPlaylist(playlist, 'https://example.com/master.m3u8');

      expect(result.variants).toHaveLength(0);
      expect(result.audioTracks).toHaveLength(0);
      expect(result.subtitleTracks).toHaveLength(0);
    });

    it('should throw on invalid playlist (missing #EXTM3U)', () => {
      const playlist = 'not a valid playlist';

      expect(() => {
        parseMasterPlaylist(playlist, 'https://example.com/master.m3u8');
      }).toThrow();
    });
  });

  describe('parseAttributes', () => {
    it('should parse simple attributes', () => {
      const attrs = parseAttributes('BANDWIDTH=1280000,RESOLUTION=640x360');

      expect(attrs).toEqual({
        BANDWIDTH: '1280000',
        RESOLUTION: '640x360',
      });
    });

    it('should parse quoted attributes', () => {
      const attrs = parseAttributes('NAME="English",LANGUAGE="en"');

      expect(attrs).toEqual({
        NAME: 'English',
        LANGUAGE: 'en',
      });
    });

    it('should handle attributes with commas in quotes', () => {
      const attrs = parseAttributes('NAME="English, US",LANGUAGE="en"');

      expect(attrs).toEqual({
        NAME: 'English, US',
        LANGUAGE: 'en',
      });
    });

    it('should handle escaped quotes', () => {
      const attrs = parseAttributes('NAME="Test\\"Quote"');

      expect(attrs).toEqual({
        NAME: 'Test"Quote',
      });
    });

    it('should handle empty attribute string', () => {
      const attrs = parseAttributes('');

      expect(attrs).toEqual({});
    });
  });

  describe('resolveUri', () => {
    it('should resolve relative URI', () => {
      const result = resolveUri('segment.ts', 'https://example.com/videos/playlist.m3u8');

      expect(result).toBe('https://example.com/videos/segment.ts');
    });

    it('should resolve parent directory URI', () => {
      const result = resolveUri('../audio/segment.ts', 'https://example.com/videos/playlist.m3u8');

      expect(result).toBe('https://example.com/audio/segment.ts');
    });

    it('should keep absolute URI unchanged', () => {
      const result = resolveUri('https://cdn.com/segment.ts', 'https://example.com/playlist.m3u8');

      expect(result).toBe('https://cdn.com/segment.ts');
    });

    it('should handle URI with query parameters', () => {
      const result = resolveUri('segment.ts?token=abc', 'https://example.com/playlist.m3u8');

      expect(result).toBe('https://example.com/segment.ts?token=abc');
    });

    it('should handle base URI with trailing slash', () => {
      const result = resolveUri('segment.ts', 'https://example.com/videos/');

      expect(result).toBe('https://example.com/videos/segment.ts');
    });
  });
});

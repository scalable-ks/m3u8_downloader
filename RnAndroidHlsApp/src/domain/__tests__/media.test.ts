import { parseMediaPlaylist, mergeLivePlaylist, nextReloadDelayMs } from '../media';

describe('Media Playlist Parser', () => {
  describe('parseMediaPlaylist', () => {
    it('should parse segments with duration and URIs', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
seg1.ts
#EXTINF:9.5
seg2.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(2);
      expect(result.segments[0]).toMatchObject({
        duration: 10.0,
        uri: 'https://example.com/seg1.ts',
      });
      expect(result.segments[1]).toMatchObject({
        duration: 9.5,
        uri: 'https://example.com/seg2.ts',
      });
    });

    it('should parse EXT-X-KEY encryption (AES-128)', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key.bin",IV=0x12345678901234567890123456789012
#EXTINF:10.0
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(1);
      expect(result.segments[0].key).toMatchObject({
        method: 'AES-128',
        uri: 'https://example.com/key.bin',
        iv: '0x12345678901234567890123456789012',
      });
    });

    it('should parse EXT-X-KEY with relative URI', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXTINF:10.0
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/path/playlist.m3u8');

      expect(result.segments[0].key?.uri).toBe('https://example.com/path/key.bin');
    });

    it('should parse EXT-X-MAP init segments', () => {
      const playlist = `#EXTM3U
#EXT-X-MAP:URI="init.mp4",BYTERANGE="800@0"
#EXTINF:10.0
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(1);
      expect(result.segments[0].map).toMatchObject({
        uri: 'https://example.com/init.mp4',
        byteRange: { length: 800, offset: 0 },
      });
    });

    it('should parse segment byte ranges', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
#EXT-X-BYTERANGE:1000@500
seg.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments[0].byteRange).toMatchObject({
        length: 1000,
        offset: 500,
      });
    });

    it('should parse byte range without offset', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
#EXT-X-BYTERANGE:1000
seg.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments[0].byteRange).toMatchObject({
        length: 1000,
      });
      expect(result.segments[0].byteRange?.offset).toBeUndefined();
    });

    it('should detect live playlists (no EXT-X-ENDLIST)', () => {
      const playlist = `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10.0
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.isLive).toBe(true);
    });

    it('should detect ended playlists (with EXT-X-ENDLIST)', () => {
      const playlist = `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10.0
seg1.ts
#EXT-X-ENDLIST`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.isLive).toBe(false);
    });

    it('should parse EXT-X-TARGETDURATION', () => {
      const playlist = `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:9.5
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.targetDuration).toBe(10);
    });

    it('should parse EXT-X-MEDIA-SEQUENCE', () => {
      const playlist = `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts
#EXTINF:10.0
seg101.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.mediaSequence).toBe(100);
      expect(result.segments[0].sequence).toBe(100);
      expect(result.segments[1].sequence).toBe(101);
    });

    it('should handle segments without media sequence (defaults to 0)', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
seg1.ts
#EXTINF:10.0
seg2.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.mediaSequence).toBe(0);
      expect(result.segments[0].sequence).toBe(0);
      expect(result.segments[1].sequence).toBe(1);
    });

    it('should parse EXTINF with title', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0,Sample Title
seg1.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments[0].duration).toBe(10.0);
      expect(result.segments[0].title).toBe('Sample Title');
    });

    it('should handle multiple segments with different encryption keys', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="key1.bin"
#EXTINF:10.0
seg1.ts
#EXT-X-KEY:METHOD=AES-128,URI="key2.bin"
#EXTINF:10.0
seg2.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments[0].key?.uri).toBe('https://example.com/key1.bin');
      expect(result.segments[1].key?.uri).toBe('https://example.com/key2.bin');
    });

    it('should resolve relative segment URIs', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
segment.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/path/to/playlist.m3u8');

      expect(result.segments[0].uri).toBe('https://example.com/path/to/segment.ts');
    });

    it('should handle absolute segment URIs', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
https://cdn.example.com/segment.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments[0].uri).toBe('https://cdn.example.com/segment.ts');
    });

    it('should ignore comment lines', () => {
      const playlist = `#EXTM3U
# This is a comment
#EXTINF:10.0
seg1.ts
# Another comment
#EXTINF:10.0
seg2.ts`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(2);
    });

    it('should handle empty lines', () => {
      const playlist = `#EXTM3U

#EXTINF:10.0

seg1.ts

`;

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(1);
    });

    it('should handle CRLF line endings', () => {
      const playlist = "#EXTM3U\r\n#EXTINF:10.0\r\nseg1.ts\r\n";

      const result = parseMediaPlaylist(playlist, 'https://example.com/playlist.m3u8');

      expect(result.segments).toHaveLength(1);
      expect(result.segments[0].uri).toBe('https://example.com/seg1.ts');
    });
  });

  describe('mergeLivePlaylist', () => {
    it('should merge live playlist updates with new segments', () => {
      const previous = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts
#EXTINF:10.0
seg101.ts`,
        'https://example.com/playlist.m3u8'
      );

      const next = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:101
#EXTINF:10.0
seg101.ts
#EXTINF:10.0
seg102.ts
#EXTINF:10.0
seg103.ts`,
        'https://example.com/playlist.m3u8'
      );

      const merged = mergeLivePlaylist(previous, next);

      expect(merged.segments).toHaveLength(3);
      expect(merged.segments[0].sequence).toBe(101);
      expect(merged.segments[1].sequence).toBe(102);
      expect(merged.segments[2].sequence).toBe(103);
    });

    it('should drop old segments before media sequence', () => {
      const previous = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts
#EXTINF:10.0
seg101.ts
#EXTINF:10.0
seg102.ts`,
        'https://example.com/playlist.m3u8'
      );

      const next = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:102
#EXTINF:10.0
seg102.ts
#EXTINF:10.0
seg103.ts`,
        'https://example.com/playlist.m3u8'
      );

      const merged = mergeLivePlaylist(previous, next);

      expect(merged.segments).toHaveLength(2);
      expect(merged.segments[0].sequence).toBe(102);
      expect(merged.segments[1].sequence).toBe(103);
    });

    it('should return next playlist when previous is not live', () => {
      const previous = parseMediaPlaylist(
        `#EXTM3U
#EXTINF:10.0
seg1.ts
#EXT-X-ENDLIST`,
        'https://example.com/playlist.m3u8'
      );

      const next = parseMediaPlaylist(
        `#EXTM3U
#EXTINF:10.0
seg1.ts
#EXTINF:10.0
seg2.ts
#EXT-X-ENDLIST`,
        'https://example.com/playlist.m3u8'
      );

      const merged = mergeLivePlaylist(previous, next);

      expect(merged).toBe(next);
    });

    it('should handle duplicate segments correctly', () => {
      const previous = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts`,
        'https://example.com/playlist.m3u8'
      );

      const next = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:10.0
seg100.ts
#EXTINF:10.0
seg101.ts`,
        'https://example.com/playlist.m3u8'
      );

      const merged = mergeLivePlaylist(previous, next);

      expect(merged.segments).toHaveLength(2);
      expect(merged.segments[0].sequence).toBe(100);
      expect(merged.segments[1].sequence).toBe(101);
    });
  });

  describe('nextReloadDelayMs', () => {
    it('should calculate reload delay from target duration', () => {
      const playlist = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10.0
seg1.ts`,
        'https://example.com/playlist.m3u8'
      );

      const delay = nextReloadDelayMs(playlist);

      expect(delay).toBe(10000); // 10 seconds in milliseconds
    });

    it('should use last segment duration when no target duration', () => {
      const playlist = parseMediaPlaylist(
        `#EXTM3U
#EXTINF:6.0
seg1.ts`,
        'https://example.com/playlist.m3u8'
      );

      const delay = nextReloadDelayMs(playlist);

      expect(delay).toBe(6000); // 6 seconds in milliseconds
    });

    it('should use default delay when no target duration or segments', () => {
      const playlist = parseMediaPlaylist('#EXTM3U', 'https://example.com/playlist.m3u8');

      const delay = nextReloadDelayMs(playlist);

      expect(delay).toBe(5000); // DEFAULT_RELOAD_MS
    });

    it('should enforce minimum delay of 1 second', () => {
      const playlist = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-TARGETDURATION:0.5
#EXTINF:0.5
seg1.ts`,
        'https://example.com/playlist.m3u8'
      );

      const delay = nextReloadDelayMs(playlist);

      expect(delay).toBe(1000); // MIN_RELOAD_MS
    });

    it('should return 0 for non-live playlists', () => {
      const playlist = parseMediaPlaylist(
        `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10.0
seg1.ts
#EXT-X-ENDLIST`,
        'https://example.com/playlist.m3u8'
      );

      const delay = nextReloadDelayMs(playlist);

      expect(delay).toBe(0);
    });
  });
});

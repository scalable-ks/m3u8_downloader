import { detectDrm, DrmSystem } from '../drm';

describe('DRM Detection', () => {
  describe('detectDrm', () => {
    it('should detect Widevine DRM', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",URI="skd://widevine"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.WIDEVINE);
    });

    it('should detect PlayReady DRM', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.microsoft.playready",URI="https://pr.example.com/license"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.PLAYREADY);
    });

    it('should detect FairPlay DRM', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://fairplay"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.FAIRPLAY);
    });

    it('should detect SAMPLE-AES encryption', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://example.com/key"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.SAMPLE_AES);
    });

    it('should detect multiple DRM systems', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",URI="skd://widevine"
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.apple.streamingkeydelivery",URI="skd://fairplay"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.WIDEVINE);
      expect(result.systems).toContain(DrmSystem.FAIRPLAY);
      expect(result.systems).toHaveLength(2);
    });

    it('should detect no DRM in unencrypted playlist', () => {
      const playlist = `#EXTM3U
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(false);
      expect(result.systems).toHaveLength(0);
    });

    it('should detect no DRM with AES-128 (not DRM)', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="https://example.com/key"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(false);
      expect(result.systems).toHaveLength(0);
    });

    it('should handle malformed EXT-X-KEY tags gracefully', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:INVALID_SYNTAX
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(false);
      expect(result.systems).toHaveLength(0);
    });

    it('should detect unknown DRM systems as OTHER', () => {
      const playlist = `#EXTM3U
#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.unknown.drm",URI="https://example.com/key"
#EXTINF:10.0
segment.ts`;

      const result = detectDrm(playlist);

      expect(result.hasDrm).toBe(true);
      expect(result.systems).toContain(DrmSystem.OTHER);
    });
  });
});

import { buildDownloadPlan } from '../plan';
import type { PlanOptions } from '../plan';
import { parseMasterPlaylist } from '../../domain/m3u8';
import { parseMediaPlaylist, mergeLivePlaylist, nextReloadDelayMs } from '../../domain/media';
import { selectTracks } from '../../domain/selection';

// Mock the domain modules
jest.mock('../../domain/m3u8');
jest.mock('../../domain/media');
jest.mock('../../domain/selection');

describe('buildDownloadPlan', () => {
  const mockParseMasterPlaylist = parseMasterPlaylist as jest.MockedFunction<typeof parseMasterPlaylist>;
  const mockParseMediaPlaylist = parseMediaPlaylist as jest.MockedFunction<typeof parseMediaPlaylist>;
  const mockSelectTracks = selectTracks as jest.MockedFunction<typeof selectTracks>;
  const mockMergeLivePlaylist = mergeLivePlaylist as jest.MockedFunction<typeof mergeLivePlaylist>;
  const mockNextReloadDelayMs = nextReloadDelayMs as jest.MockedFunction<typeof nextReloadDelayMs>;

  let mockFetcher: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockFetcher = jest.fn();
  });

  it('should fetch and parse master playlist successfully', async () => {
    const masterPlaylist = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nvideo.m3u8';
    const videoPlaylist = '#EXTM3U\n#EXTINF:10.0\nseg1.ts';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist) // master
      .mockResolvedValueOnce(videoPlaylist); // video

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, resolution: '640x360', uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    });

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
      audio: undefined,
      subtitle: undefined,
    });

    mockParseMediaPlaylist.mockReturnValue({
      segments: [{ duration: 10.0, uri: 'https://example.com/seg1.ts' }],
      isLive: false,
    } as any);

    const options: PlanOptions = {
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      headers: { 'User-Agent': 'TestAgent' },
      fetcher: mockFetcher,
    };

    const plan = await buildDownloadPlan(options);

    expect(mockFetcher).toHaveBeenCalledWith('https://example.com/master.m3u8', expect.objectContaining({ 'User-Agent': 'TestAgent' }));
    expect(mockParseMasterPlaylist).toHaveBeenCalledWith(masterPlaylist, 'https://example.com/master.m3u8');
    expect(plan.id).toBe('test-job');
    expect(plan.video.segments).toHaveLength(1);
  });

  it('should select tracks and fetch media playlists', async () => {
    const masterPlaylist = '#EXTM3U';
    const videoPlaylist = '#EXTM3U\n#EXTINF:10.0\nvideo-seg.ts';
    const audioPlaylist = '#EXTM3U\n#EXTINF:10.0\naudio-seg.ts';
    const subtitlePlaylist = '#EXTM3U\n#EXTINF:10.0\nsub-seg.vtt';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist) // master
      .mockResolvedValueOnce(videoPlaylist) // video
      .mockResolvedValueOnce(audioPlaylist) // audio
      .mockResolvedValueOnce(subtitlePlaylist); // subtitle

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [{ groupId: 'audio', uri: 'https://example.com/audio.m3u8', language: 'en' }],
      subtitleTracks: [{ groupId: 'subs', uri: 'https://example.com/subs.m3u8', language: 'en' }],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
      audio: { uri: 'https://example.com/audio.m3u8' },
      subtitle: { uri: 'https://example.com/subs.m3u8' },
    } as any);

    mockParseMediaPlaylist
      .mockReturnValueOnce({ segments: [{ duration: 10.0, uri: 'video-seg.ts' }], isLive: false } as any)
      .mockReturnValueOnce({ segments: [{ duration: 10.0, uri: 'audio-seg.ts' }], isLive: false } as any)
      .mockReturnValueOnce({ segments: [{ duration: 10.0, uri: 'sub-seg.vtt' }], isLive: false } as any);

    const plan = await buildDownloadPlan({
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      fetcher: mockFetcher,
    });

    expect(mockSelectTracks).toHaveBeenCalled();
    expect(plan.video).toBeDefined();
    expect(plan.audio).toBeDefined();
    expect(plan.subtitles).toBeDefined();
    expect(plan.audio?.segments).toHaveLength(1);
    expect(plan.subtitles?.segments).toHaveLength(1);
  });

  it('should merge headers and cookies correctly', async () => {
    const masterPlaylist = '#EXTM3U';
    const videoPlaylist = '#EXTM3U\n#EXTINF:10.0\nseg.ts';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist)
      .mockResolvedValueOnce(videoPlaylist);

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
    } as any);

    mockParseMediaPlaylist.mockReturnValue({
      segments: [{ duration: 10.0, uri: 'seg.ts' }],
      isLive: false,
    } as any);

    // Test with cookie object
    const plan = await buildDownloadPlan({
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      headers: { 'User-Agent': 'TestAgent' },
      cookies: { session: 'abc123', token: 'xyz789' },
      fetcher: mockFetcher,
    });

    expect(mockFetcher).toHaveBeenCalledWith(
      'https://example.com/master.m3u8',
      expect.objectContaining({
        'User-Agent': 'TestAgent',
        Cookie: 'session=abc123; token=xyz789',
      })
    );
    expect(plan.headers.Cookie).toBe('session=abc123; token=xyz789');
  });

  it('should merge cookie string directly', async () => {
    const masterPlaylist = '#EXTM3U';
    const videoPlaylist = '#EXTM3U\n#EXTINF:10.0\nseg.ts';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist)
      .mockResolvedValueOnce(videoPlaylist);

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
    } as any);

    mockParseMediaPlaylist.mockReturnValue({
      segments: [{ duration: 10.0, uri: 'seg.ts' }],
      isLive: false,
    } as any);

    const plan = await buildDownloadPlan({
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      cookies: 'session=abc123; token=xyz789',
      fetcher: mockFetcher,
    });

    expect(plan.headers.Cookie).toBe('session=abc123; token=xyz789');
  });

  it('should handle live playlists with refresh', async () => {
    const masterPlaylist = '#EXTM3U';
    const livePlaylist1 = '#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0\nseg1.ts';
    const livePlaylist2 = '#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0\nseg1.ts\n#EXTINF:10.0\nseg2.ts\n#EXT-X-ENDLIST';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist) // master
      .mockResolvedValueOnce(livePlaylist1) // initial video (live)
      .mockResolvedValueOnce(livePlaylist2); // refreshed video (ended)

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
    } as any);

    // First parse: live playlist
    mockParseMediaPlaylist
      .mockReturnValueOnce({
        segments: [{ duration: 10.0, uri: 'seg1.ts' }],
        isLive: true,
        targetDuration: 10,
      } as any)
      .mockReturnValueOnce({
        segments: [{ duration: 10.0, uri: 'seg1.ts' }, { duration: 10.0, uri: 'seg2.ts' }],
        isLive: false,
        targetDuration: 10,
      } as any);

    mockNextReloadDelayMs.mockReturnValue(10000);
    mockMergeLivePlaylist.mockReturnValue({
      segments: [{ duration: 10.0, uri: 'seg1.ts' }, { duration: 10.0, uri: 'seg2.ts' }],
      isLive: false,
      targetDuration: 10,
    } as any);

    const mockSleep = jest.fn().mockResolvedValue(undefined);

    const plan = await buildDownloadPlan({
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      fetcher: mockFetcher,
      sleep: mockSleep,
      liveRefreshLimit: 5,
    });

    expect(mockSleep).toHaveBeenCalledWith(10000);
    expect(mockMergeLivePlaylist).toHaveBeenCalled();
    expect(plan.video.segments).toHaveLength(2);
  });

  it('should throw on fetch errors', async () => {
    const error = new Error('Network timeout');
    mockFetcher.mockRejectedValue(error);

    await expect(
      buildDownloadPlan({
        id: 'test-job',
        masterPlaylistUri: 'https://example.com/master.m3u8',
        fetcher: mockFetcher,
      })
    ).rejects.toThrow('Failed to fetch master playlist from https://example.com/master.m3u8: Network timeout');
  });

  it('should handle missing audio/subtitle tracks gracefully', async () => {
    const masterPlaylist = '#EXTM3U';
    const videoPlaylist = '#EXTM3U\n#EXTINF:10.0\nseg.ts';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist)
      .mockResolvedValueOnce(videoPlaylist);

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
      audio: undefined,
      subtitle: undefined,
    } as any);

    mockParseMediaPlaylist.mockReturnValue({
      segments: [{ duration: 10.0, uri: 'seg.ts' }],
      isLive: false,
    } as any);

    const plan = await buildDownloadPlan({
      id: 'test-job',
      masterPlaylistUri: 'https://example.com/master.m3u8',
      fetcher: mockFetcher,
    });

    expect(plan.audio).toBeUndefined();
    expect(plan.subtitles).toBeUndefined();
    expect(plan.video).toBeDefined();
  });

  it('should throw when master playlist parsing fails', async () => {
    const masterPlaylist = 'INVALID CONTENT';
    mockFetcher.mockResolvedValue(masterPlaylist);

    const parseError = new Error('Invalid M3U8 format');
    mockParseMasterPlaylist.mockImplementation(() => {
      throw parseError;
    });

    await expect(
      buildDownloadPlan({
        id: 'test-job',
        masterPlaylistUri: 'https://example.com/master.m3u8',
        fetcher: mockFetcher,
      })
    ).rejects.toThrow('Failed to parse master playlist: Invalid M3U8 format');
  });

  it('should throw when video playlist fetch fails', async () => {
    const masterPlaylist = '#EXTM3U';

    mockFetcher
      .mockResolvedValueOnce(masterPlaylist)
      .mockRejectedValueOnce(new Error('404 Not Found'));

    mockParseMasterPlaylist.mockReturnValue({
      variants: [{ bandwidth: 1280000, uri: 'https://example.com/video.m3u8' }],
      audioTracks: [],
      subtitleTracks: [],
    } as any);

    mockSelectTracks.mockReturnValue({
      video: { uri: 'https://example.com/video.m3u8' },
    } as any);

    await expect(
      buildDownloadPlan({
        id: 'test-job',
        masterPlaylistUri: 'https://example.com/master.m3u8',
        fetcher: mockFetcher,
      })
    ).rejects.toThrow('Failed to fetch video playlist from https://example.com/video.m3u8: 404 Not Found');
  });
});

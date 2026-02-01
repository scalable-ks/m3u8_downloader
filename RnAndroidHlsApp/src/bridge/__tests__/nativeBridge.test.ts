import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import type { DownloadPlan } from '../models';
import type { JobStatus, JobError } from '../api';

// Unmock the nativeBridge module for this test
jest.unmock('../nativeBridge');

// Import after unmocking
import { NativeDownloaderBridge } from '../nativeBridge';

// Mock React Native modules
jest.mock('react-native', () => ({
  NativeModules: {
    HlsDownloaderModule: {
      startPlannedJob: jest.fn(),
      pauseJob: jest.fn(),
      resumeJob: jest.fn(),
      cancelJob: jest.fn(),
      getJobStatus: jest.fn(),
      listJobs: jest.fn(),
    },
  },
  Platform: {
    OS: 'android',
  },
  NativeEventEmitter: jest.fn(),
}));

describe('NativeDownloaderBridge', () => {
  let mockOnProgress: jest.Mock;
  let mockOnError: jest.Mock;
  let mockEmitter: {
    addListener: jest.Mock;
    removeListener: jest.Mock;
  };
  let progressListener: (payload: any) => void;
  let errorListener: (payload: any) => void;

  beforeEach(() => {
    jest.clearAllMocks();

    mockOnProgress = jest.fn();
    mockOnError = jest.fn();

    // Mock NativeEventEmitter to capture listeners
    mockEmitter = {
      addListener: jest.fn((eventName: string, callback: (payload: any) => void) => {
        if (eventName === 'downloadProgress') {
          progressListener = callback;
        } else if (eventName === 'downloadError') {
          errorListener = callback;
        }
      }),
      removeListener: jest.fn(),
    };

    (NativeEventEmitter as jest.Mock).mockImplementation(() => mockEmitter);

    // Reset Platform.OS to android
    (Platform as any).OS = 'android';
  });

  describe('constructor', () => {
    it('should initialize successfully on Android', () => {
      expect(() => {
        new NativeDownloaderBridge(mockOnProgress, mockOnError);
      }).not.toThrow();
    });

    it('should throw when NativeModules.HlsDownloaderModule is unavailable', () => {
      const originalModule = NativeModules.HlsDownloaderModule;
      (NativeModules as any).HlsDownloaderModule = undefined;

      expect(() => {
        new NativeDownloaderBridge(mockOnProgress, mockOnError);
      }).toThrow('Native downloader module not available.');

      (NativeModules as any).HlsDownloaderModule = originalModule;
    });

    it('should throw when Platform.OS is not android', () => {
      (Platform as any).OS = 'ios';

      expect(() => {
        new NativeDownloaderBridge(mockOnProgress, mockOnError);
      }).toThrow('Native downloader module not available.');
    });

    it('should register event listeners on construction', () => {
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      expect(mockEmitter.addListener).toHaveBeenCalledWith('downloadProgress', expect.any(Function));
      expect(mockEmitter.addListener).toHaveBeenCalledWith('downloadError', expect.any(Function));
    });
  });

  describe('startPlannedJob', () => {
    it('should serialize download plan to JSON and call native module', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const plan: DownloadPlan = {
        id: 'job-123',
        masterPlaylistUri: 'https://example.com/master.m3u8',
        tracks: {
          video: { uri: 'https://example.com/video.m3u8' },
        },
        video: {
          playlistUri: 'https://example.com/video.m3u8',
          segments: [{ duration: 10.0, uri: 'https://example.com/seg1.ts' }],
        },
        headers: { 'User-Agent': 'TestAgent' },
      } as any;

      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'PENDING',
        progress: 0,
      };

      (NativeModules.HlsDownloaderModule.startPlannedJob as jest.Mock).mockResolvedValue(mockStatus);

      const result = await bridge.startPlannedJob(plan);

      expect(NativeModules.HlsDownloaderModule.startPlannedJob).toHaveBeenCalledWith(expect.any(String));

      const passedJson = (NativeModules.HlsDownloaderModule.startPlannedJob as jest.Mock).mock.calls[0][0];
      const parsedPlan = JSON.parse(passedJson);
      expect(parsedPlan.id).toBe('job-123');
      expect(parsedPlan.masterPlaylistUri).toBe('https://example.com/master.m3u8');

      expect(result).toEqual({
        id: 'job-123',
        state: 'PENDING',
        progress: 0,
        masterPlaylistUri: 'https://example.com/master.m3u8',
        createdAt: expect.any(Number),
      });
    });

    it('should throw when plan cannot be serialized', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      // Create a circular reference that will fail JSON.stringify
      const plan: any = { id: 'job-123' };
      plan.circular = plan;

      await expect(bridge.startPlannedJob(plan)).rejects.toThrow('Failed to serialize download plan');
    });
  });

  describe('progress events', () => {
    it('should parse progress events from native and call onProgress', () => {
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const payload = JSON.stringify({
        id: 'job-123',
        state: 'RUNNING',
        progress: 50,
      });

      progressListener(payload);

      expect(mockOnProgress).toHaveBeenCalledWith({
        id: 'job-123',
        state: 'RUNNING',
        progress: 50,
      });
    });

    it('should parse object payloads directly', () => {
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const payload = {
        id: 'job-456',
        state: 'COMPLETED',
        progress: 100,
      };

      progressListener(payload);

      expect(mockOnProgress).toHaveBeenCalledWith({
        id: 'job-456',
        state: 'COMPLETED',
        progress: 100,
      });
    });

    it('should handle malformed JSON in progress events', () => {
      const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation();
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const invalidPayload = '{broken json';
      progressListener(invalidPayload);

      expect(consoleErrorSpy).toHaveBeenCalledWith(
        'Failed to parse downloadProgress event:',
        expect.any(Error),
        'payload:',
        invalidPayload
      );
      expect(mockOnProgress).not.toHaveBeenCalled();

      consoleErrorSpy.mockRestore();
    });
  });

  describe('error events', () => {
    it('should parse error events from native and call onError', () => {
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const payload = JSON.stringify({
        id: 'job-123',
        code: 'NETWORK_ERROR',
        message: 'Connection timeout',
        detail: 'Failed to connect to server',
      });

      errorListener(payload);

      expect(mockOnError).toHaveBeenCalledWith({
        id: 'job-123',
        code: 'NETWORK_ERROR',
        message: 'Connection timeout',
        detail: 'Failed to connect to server',
      });
    });

    it('should parse object payloads directly', () => {
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const payload = {
        id: 'job-456',
        code: 'DISK_FULL',
        message: 'Not enough storage space',
        detail: 'Required: 1GB, Available: 100MB',
      };

      errorListener(payload);

      expect(mockOnError).toHaveBeenCalledWith({
        id: 'job-456',
        code: 'DISK_FULL',
        message: 'Not enough storage space',
        detail: 'Required: 1GB, Available: 100MB',
      });
    });

    it('should handle malformed JSON in error events and emit fallback error', () => {
      const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation();
      new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const invalidPayload = '{broken json';
      errorListener(invalidPayload);

      expect(consoleErrorSpy).toHaveBeenCalledWith(
        'Failed to parse downloadError event:',
        expect.any(Error),
        'payload:',
        invalidPayload
      );

      expect(mockOnError).toHaveBeenCalledWith({
        id: 'unknown',
        code: 'json_parse_error',
        message: 'Failed to parse error event from native module',
        detail: expect.any(String),
      });

      consoleErrorSpy.mockRestore();
    });
  });

  describe('job control methods', () => {
    it('should pause job', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'PAUSED',
        progress: 25,
      };

      (NativeModules.HlsDownloaderModule.pauseJob as jest.Mock).mockResolvedValue(mockStatus);

      const result = await bridge.pauseJob('job-123');

      expect(NativeModules.HlsDownloaderModule.pauseJob).toHaveBeenCalledWith('job-123');
      expect(result).toEqual(mockStatus);
    });

    it('should resume job', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'RUNNING',
        progress: 25,
      };

      (NativeModules.HlsDownloaderModule.resumeJob as jest.Mock).mockResolvedValue(mockStatus);

      const result = await bridge.resumeJob('job-123');

      expect(NativeModules.HlsDownloaderModule.resumeJob).toHaveBeenCalledWith('job-123');
      expect(result).toEqual(mockStatus);
    });

    it('should cancel job', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'CANCELED',
        progress: 25,
      };

      (NativeModules.HlsDownloaderModule.cancelJob as jest.Mock).mockResolvedValue(mockStatus);

      const result = await bridge.cancelJob('job-123');

      expect(NativeModules.HlsDownloaderModule.cancelJob).toHaveBeenCalledWith('job-123');
      expect(result).toEqual(mockStatus);
    });

    it('should get job status', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'RUNNING',
        progress: 75,
      };

      (NativeModules.HlsDownloaderModule.getJobStatus as jest.Mock).mockResolvedValue(mockStatus);

      const result = await bridge.getJobStatus('job-123');

      expect(NativeModules.HlsDownloaderModule.getJobStatus).toHaveBeenCalledWith('job-123');
      expect(result).toEqual(mockStatus);
    });

    it('should list jobs', async () => {
      const bridge = new NativeDownloaderBridge(mockOnProgress, mockOnError);

      const jobsJson = JSON.stringify([
        {
          id: 'job-1',
          state: 'RUNNING',
          progress: 50,
          masterPlaylistUri: 'https://example.com/master1.m3u8',
          createdAt: 1234567890,
        },
        {
          id: 'job-2',
          state: 'COMPLETED',
          progress: 100,
          masterPlaylistUri: 'https://example.com/master2.m3u8',
          createdAt: 1234567900,
        },
      ]);

      (NativeModules.HlsDownloaderModule.listJobs as jest.Mock).mockResolvedValue(jobsJson);

      const result = await bridge.listJobs();

      expect(result).toHaveLength(2);
      expect(result[0]).toEqual({
        id: 'job-1',
        state: 'RUNNING',
        progress: 50,
        masterPlaylistUri: 'https://example.com/master1.m3u8',
        createdAt: 1234567890,
      });
      expect(result[1]).toEqual({
        id: 'job-2',
        state: 'COMPLETED',
        progress: 100,
        masterPlaylistUri: 'https://example.com/master2.m3u8',
        createdAt: 1234567900,
      });
    });
  });
});

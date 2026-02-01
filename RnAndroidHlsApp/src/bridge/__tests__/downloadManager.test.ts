import { DownloadManager } from '../downloadManager';
import type { DownloaderBridge, JobStatus, JobError } from '../api';
import type { JobStore } from '../jobStore';
import type { Logger } from '../logger';

describe('DownloadManager', () => {
  let mockBridge: jest.Mocked<DownloaderBridge>;
  let mockStore: jest.Mocked<JobStore>;
  let mockLogger: jest.Mocked<Logger>;
  let manager: DownloadManager;

  beforeEach(() => {
    mockBridge = {
      startJob: jest.fn(),
      pauseJob: jest.fn(),
      resumeJob: jest.fn(),
      cancelJob: jest.fn(),
      getJobStatus: jest.fn(),
      listJobs: jest.fn(),
    } as any;

    mockStore = {
      save: jest.fn().mockResolvedValue(undefined),
      load: jest.fn().mockResolvedValue(undefined),
      list: jest.fn().mockResolvedValue([]),
      remove: jest.fn().mockResolvedValue(undefined),
    } as any;

    mockLogger = {
      info: jest.fn(),
      warn: jest.fn(),
      error: jest.fn(),
    } as any;

    manager = new DownloadManager(mockBridge, mockStore, mockLogger);
  });

  describe('start', () => {
    it('should start a download and save to store', async () => {
      const mockJob: any = {
        id: 'job-123',
        state: 'PENDING',
        progress: 0,
        masterPlaylistUri: 'https://example.com/playlist.m3u8',
        createdAt: Date.now(),
      };
      mockBridge.startJob.mockResolvedValue(mockJob);

      const result = await manager.start({
        id: 'job-123',
        masterPlaylistUri: 'https://example.com/playlist.m3u8',
        exportTreeUri: '/storage/downloads',
        cleanupPolicy: 'deleteOnSuccess',
      });

      expect(mockBridge.startJob).toHaveBeenCalledWith(
        expect.objectContaining({
          id: 'job-123',
          masterPlaylistUri: 'https://example.com/playlist.m3u8',
        })
      );
      expect(mockStore.save).toHaveBeenCalledWith(mockJob);
      expect(result).toEqual(mockJob);
    });

    it('should throw error when bridge fails', async () => {
      const error = new Error('Network unavailable');
      mockBridge.startJob.mockRejectedValue(error);

      await expect(
        manager.start({
          id: 'job-123',
          masterPlaylistUri: 'https://example.com/playlist.m3u8',
          exportTreeUri: '/storage/downloads',
          cleanupPolicy: 'deleteOnSuccess',
        })
      ).rejects.toThrow('Network unavailable');
    });
  });

  describe('progress listener', () => {
    it('should notify listeners when progress updates', () => {
      const listener = jest.fn();
      manager.onProgress(listener);

      manager.handleProgress({
        id: 'job-123',
        state: 'RUNNING',
        progress: 50,
      });

      expect(listener).toHaveBeenCalledWith({
        id: 'job-123',
        state: 'RUNNING',
        progress: 50,
      });
    });

    it('should not notify removed listeners', () => {
      const listener = jest.fn();
      const unsubscribe = manager.onProgress(listener);

      unsubscribe();

      manager.handleProgress({
        id: 'job-123',
        state: 'RUNNING',
        progress: 50,
      });

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe('error listener', () => {
    it('should notify listeners when error occurs', () => {
      const listener = jest.fn();
      manager.onError(listener);

      const error: JobError = {
        id: 'job-123',
        message: 'Download failed',
        code: 'NETWORK_ERROR',
      };

      manager.handleError(error);

      expect(listener).toHaveBeenCalledWith(error);
    });
  });

  describe('cancel', () => {
    it('should cancel job and remove from store', async () => {
      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'CANCELED',
        progress: 25,
      };
      mockBridge.cancelJob.mockResolvedValue(mockStatus);

      await manager.cancel('job-123');

      expect(mockBridge.cancelJob).toHaveBeenCalledWith('job-123');
      expect(mockStore.remove).toHaveBeenCalledWith('job-123');
    });
  });

  describe('pause', () => {
    it('should pause running job', async () => {
      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'PAUSED',
        progress: 40,
      };
      mockBridge.pauseJob.mockResolvedValue(mockStatus);

      const result = await manager.pause('job-123');

      expect(mockBridge.pauseJob).toHaveBeenCalledWith('job-123');
      expect(result.state).toBe('PAUSED');
    });
  });

  describe('resume', () => {
    it('should resume paused job', async () => {
      const mockStatus: JobStatus = {
        id: 'job-123',
        state: 'RUNNING',
        progress: 40,
      };
      mockBridge.resumeJob.mockResolvedValue(mockStatus);

      const result = await manager.resume('job-123');

      expect(mockBridge.resumeJob).toHaveBeenCalledWith('job-123');
      expect(result.state).toBe('RUNNING');
    });
  });
});

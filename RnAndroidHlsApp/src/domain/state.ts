export type SegmentStatus = "pending" | "downloading" | "completed" | "failed";

export interface SegmentState {
  uri: string;
  index: number;
  status: SegmentStatus;
  bytesDownloaded: number;
  totalBytes?: number;
}

export interface DownloadState {
  jobId: string;
  playlistUri: string;
  createdAt: number;
  updatedAt: number;
  segments: SegmentState[];
}

export interface DownloadStateStore {
  get(jobId: string): Promise<DownloadState | undefined>;
  save(state: DownloadState): Promise<void>;
  updateSegment(jobId: string, segment: SegmentState): Promise<void>;
  delete(jobId: string): Promise<void>;
}

export class InMemoryDownloadStateStore implements DownloadStateStore {
  private readonly jobs = new Map<string, DownloadState>();

  async get(jobId: string): Promise<DownloadState | undefined> {
    return this.jobs.get(jobId);
  }

  async save(state: DownloadState): Promise<void> {
    this.jobs.set(state.jobId, state);
  }

  async updateSegment(jobId: string, segment: SegmentState): Promise<void> {
    const state = this.jobs.get(jobId);
    if (!state) {
      return;
    }
    const nextSegments = state.segments.map((item) => (item.index === segment.index ? segment : item));
    this.jobs.set(jobId, {
      ...state,
      segments: nextSegments,
      updatedAt: Date.now(),
    });
  }

  async delete(jobId: string): Promise<void> {
    this.jobs.delete(jobId);
  }
}

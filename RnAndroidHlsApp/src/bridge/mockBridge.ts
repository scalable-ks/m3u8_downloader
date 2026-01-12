import type { DownloadJob } from "../domain/types.ts";
import type { JobError, JobStatus, StartJobRequest, DownloaderBridge } from "./api.ts";

const DEFAULT_TOTAL_BYTES = 5_000_000;
const DEFAULT_TOTAL_SEGMENTS = 10;
const PROGRESS_INTERVAL_MS = 400;
const PROGRESS_STEP = 0.12;

type ProgressEmitter = (status: JobStatus) => void;
type ErrorEmitter = (error: JobError) => void;

export class MockBridge implements DownloaderBridge {
  private timers = new Map<string, ReturnType<typeof setInterval>>();
  private statuses = new Map<string, JobStatus>();
  private progressEmitter?: ProgressEmitter;
  private errorEmitter?: ErrorEmitter;

  setProgressEmitter(emitter: ProgressEmitter): void {
    this.progressEmitter = emitter;
  }

  setErrorEmitter(emitter: ErrorEmitter): void {
    this.errorEmitter = emitter;
  }

  async startJob(request: StartJobRequest): Promise<DownloadJob> {
    const job: DownloadJob = {
      id: request.id,
      masterPlaylistUri: request.masterPlaylistUri,
      createdAt: Date.now(),
      state: "running",
      progress: {
        bytesDownloaded: 0,
        totalBytes: DEFAULT_TOTAL_BYTES,
        segmentsDownloaded: 0,
        totalSegments: DEFAULT_TOTAL_SEGMENTS,
      },
    };
    this.statuses.set(request.id, {
      id: request.id,
      state: "running",
      progress: job.progress,
    });
    this.startProgressTimer(request.id);
    return job;
  }

  async pauseJob(id: string): Promise<JobStatus> {
    this.stopTimer(id);
    return this.updateStatus(id, "paused");
  }

  async resumeJob(id: string): Promise<JobStatus> {
    this.startProgressTimer(id);
    return this.updateStatus(id, "running");
  }

  async cancelJob(id: string): Promise<JobStatus> {
    this.stopTimer(id);
    return this.updateStatus(id, "canceled");
  }

  async getJobStatus(id: string): Promise<JobStatus> {
    const status = this.statuses.get(id);
    if (!status) {
      return {
        id,
        state: "failed",
        progress: {
          bytesDownloaded: 0,
          totalBytes: DEFAULT_TOTAL_BYTES,
          segmentsDownloaded: 0,
          totalSegments: DEFAULT_TOTAL_SEGMENTS,
        },
      };
    }
    return status;
  }

  private updateStatus(id: string, state: JobStatus["state"]): JobStatus {
    const status = this.statuses.get(id) ?? {
      id,
      state,
      progress: {
        bytesDownloaded: 0,
        totalBytes: DEFAULT_TOTAL_BYTES,
        segmentsDownloaded: 0,
        totalSegments: DEFAULT_TOTAL_SEGMENTS,
      },
    };
    const updated = {
      ...status,
      state,
    };
    this.statuses.set(id, updated);
    if (this.progressEmitter) {
      this.progressEmitter(updated);
    }
    return updated;
  }

  private startProgressTimer(id: string): void {
    this.stopTimer(id);
    const timer = setInterval(() => {
      const status = this.statuses.get(id);
      if (!status || status.state !== "running") {
        return;
      }
      const nextBytes = Math.min(
        status.progress.bytesDownloaded + DEFAULT_TOTAL_BYTES * PROGRESS_STEP,
        DEFAULT_TOTAL_BYTES,
      );
      const nextSegments = Math.min(
        status.progress.segmentsDownloaded + 1,
        DEFAULT_TOTAL_SEGMENTS,
      );
      const nextState = nextBytes >= DEFAULT_TOTAL_BYTES ? "completed" : "running";
      const updated: JobStatus = {
        id,
        state: nextState,
        progress: {
          ...status.progress,
          bytesDownloaded: nextBytes,
          segmentsDownloaded: nextSegments,
        },
      };
      this.statuses.set(id, updated);
      if (this.progressEmitter) {
        this.progressEmitter(updated);
      }
      if (nextState === "completed") {
        this.stopTimer(id);
      }
    }, PROGRESS_INTERVAL_MS);
    this.timers.set(id, timer);
  }

  private stopTimer(id: string): void {
    const timer = this.timers.get(id);
    if (timer) {
      clearInterval(timer);
      this.timers.delete(id);
    }
  }

  private emitError(id: string, message: string): void {
    if (!this.errorEmitter) {
      return;
    }
    this.errorEmitter({
      id,
      code: "network",
      message,
    });
  }
}

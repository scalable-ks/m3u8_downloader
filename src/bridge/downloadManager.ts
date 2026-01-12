import type { DownloadJob } from "../domain/types.ts";
import type { JobError, JobStatus, StartJobRequest, DownloaderBridge } from "./api.ts";
import type { DownloadPlan } from "./models.ts";
import { buildDownloadPlan } from "./plan.ts";
import type { JobStore } from "./jobStore.ts";
import { MemoryJobStore } from "./jobStore.ts";
import type { Logger } from "./logger.ts";
import { ConsoleLogger } from "./logger.ts";

const DEFAULT_TOTAL_SEGMENTS = 0;

export type ProgressListener = (status: JobStatus) => void;
export type ErrorListener = (error: JobError) => void;

export class DownloadManager {
  private readonly progressListeners = new Set<ProgressListener>();
  private readonly errorListeners = new Set<ErrorListener>();
  private readonly bridge: DownloaderBridge;
  private readonly store: JobStore;
  private readonly logger: Logger;

  constructor(
    bridge: DownloaderBridge,
    store: JobStore = new MemoryJobStore(),
    logger: Logger = new ConsoleLogger(),
  ) {
    this.bridge = bridge;
    this.store = store;
    this.logger = logger;
  }

  async start(request: StartJobRequest): Promise<DownloadJob> {
    const job = await this.bridge.startJob(request);
    await this.store.save(job);
    this.logger.info("job started", { id: job.id });
    return job;
  }

  async startPlanned(request: StartJobRequest): Promise<DownloadJob> {
    const plan = await buildDownloadPlan({
      id: request.id,
      masterPlaylistUri: request.masterPlaylistUri,
      headers: request.headers,
      cookies: request.cookies,
      constraints: request.constraints,
      cleanupPolicy: request.cleanupPolicy,
      exportTreeUri: request.exportTreeUri,
    });
    const job = await this.startPlannedJob(plan);
    this.logger.info("job planned", { id: job.id });
    return job;
  }

  async pause(id: string): Promise<JobStatus> {
    const status = await this.bridge.pauseJob(id);
    await this.updateFromStatus(status);
    return status;
  }

  async resume(id: string): Promise<JobStatus> {
    const status = await this.bridge.resumeJob(id);
    await this.updateFromStatus(status);
    return status;
  }

  async cancel(id: string): Promise<JobStatus> {
    const status = await this.bridge.cancelJob(id);
    await this.updateFromStatus(status);
    await this.store.remove(id);
    return status;
  }

  async getStatus(id: string): Promise<JobStatus> {
    const status = await this.bridge.getJobStatus(id);
    await this.updateFromStatus(status);
    return status;
  }

  async listJobs(): Promise<DownloadJob[]> {
    return this.store.list();
  }

  onProgress(listener: ProgressListener): () => void {
    this.progressListeners.add(listener);
    return () => this.progressListeners.delete(listener);
  }

  onError(listener: ErrorListener): () => void {
    this.errorListeners.add(listener);
    return () => this.errorListeners.delete(listener);
  }

  handleProgress(status: JobStatus): void {
    this.progressListeners.forEach((listener) => listener(status));
    void this.updateFromStatus(status);
  }

  handleError(error: JobError): void {
    this.errorListeners.forEach((listener) => listener(error));
    void this.markFailed(error.id);
    this.logger.error("job error", { id: error.id, code: error.code, message: error.message });
  }

  private async startPlannedJob(plan: DownloadPlan): Promise<DownloadJob> {
    if (!this.bridge.startPlannedJob) {
      throw new Error("Planned job start not supported by bridge.");
    }
    const job = await this.bridge.startPlannedJob(plan);
    await this.store.save(job);
    return job;
  }

  private async updateFromStatus(status: JobStatus): Promise<void> {
    const existing = (await this.store.list()).find((job) => job.id === status.id);
    const job: DownloadJob = existing ?? {
      id: status.id,
      masterPlaylistUri: "",
      createdAt: Date.now(),
      state: status.state,
      progress: status.progress ?? {
        bytesDownloaded: 0,
        totalBytes: undefined,
        segmentsDownloaded: 0,
        totalSegments: DEFAULT_TOTAL_SEGMENTS,
      },
    };
    job.state = status.state;
    job.progress = status.progress;
    await this.store.save(job);
  }

  private async markFailed(id: string): Promise<void> {
    const jobs = await this.store.list();
    const job = jobs.find((item) => item.id === id);
    if (!job) {
      return;
    }
    job.state = "failed";
    await this.store.save(job);
  }
}

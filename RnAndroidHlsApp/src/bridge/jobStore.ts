import type { DownloadJob } from "../domain/types.ts";

export interface JobStore {
  list(): Promise<DownloadJob[]>;
  save(job: DownloadJob): Promise<void>;
  remove(id: string): Promise<void>;
}

export class MemoryJobStore implements JobStore {
  private readonly jobs = new Map<string, DownloadJob>();

  async list(): Promise<DownloadJob[]> {
    return Array.from(this.jobs.values());
  }

  async save(job: DownloadJob): Promise<void> {
    this.jobs.set(job.id, job);
  }

  async remove(id: string): Promise<void> {
    this.jobs.delete(id);
  }
}

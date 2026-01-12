import type { DownloadJob } from "../domain/types.ts";
import type { JobStore } from "./jobStore.ts";

type JobLoader = () => Promise<DownloadJob[]>;

export class NativeJobStore implements JobStore {
  private readonly jobs = new Map<string, DownloadJob>();
  private hydrated = false;
  private readonly loader: JobLoader;

  constructor(loader: JobLoader) {
    this.loader = loader;
  }

  async list(): Promise<DownloadJob[]> {
    await this.hydrate();
    return Array.from(this.jobs.values());
  }

  async save(job: DownloadJob): Promise<void> {
    await this.hydrate();
    this.jobs.set(job.id, job);
  }

  async remove(id: string): Promise<void> {
    await this.hydrate();
    this.jobs.delete(id);
  }

  private async hydrate(): Promise<void> {
    if (this.hydrated) {
      return;
    }
    const loaded = await this.loader();
    loaded.forEach((job) => this.jobs.set(job.id, job));
    this.hydrated = true;
  }
}

import { promises as fs } from "node:fs";
import path from "node:path";
import type { DownloadJob } from "../domain/types.ts";
import type { JobStore } from "./jobStore.ts";

const STORE_FILE = "jobs.json";
const JSON_INDENT = 2;

export class NodeFileJobStore implements JobStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  async list(): Promise<DownloadJob[]> {
    const content = await this.read();
    return content.jobs;
  }

  async save(job: DownloadJob): Promise<void> {
    const content = await this.read();
    const index = content.jobs.findIndex((existing) => existing.id === job.id);
    if (index >= 0) {
      content.jobs[index] = job;
    } else {
      content.jobs.push(job);
    }
    await this.write(content);
  }

  async remove(id: string): Promise<void> {
    const content = await this.read();
    content.jobs = content.jobs.filter((job) => job.id !== id);
    await this.write(content);
  }

  private async read(): Promise<{ jobs: DownloadJob[] }> {
    const filePath = this.resolveFilePath();
    try {
      const raw = await fs.readFile(filePath, "utf8");
      const parsed = JSON.parse(raw) as { jobs?: DownloadJob[] };
      return { jobs: parsed.jobs ?? [] };
    } catch (error) {
      if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
        return { jobs: [] };
      }
      throw error;
    }
  }

  private async write(payload: { jobs: DownloadJob[] }): Promise<void> {
    const filePath = this.resolveFilePath();
    await fs.mkdir(this.directory, { recursive: true });
    await fs.writeFile(filePath, JSON.stringify(payload, null, JSON_INDENT));
  }

  private resolveFilePath(): string {
    return path.join(this.directory, STORE_FILE);
  }
}

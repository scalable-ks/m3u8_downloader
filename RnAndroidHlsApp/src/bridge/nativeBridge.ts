import { NativeEventEmitter, NativeModules, Platform } from "react-native";
import type { DownloaderBridge, JobError, JobStatus, StartJobRequest } from "./api.ts";
import type { DownloadJob } from "../domain/types.ts";
import type { DownloadPlan } from "./models.ts";

const MODULE_NAME = "HlsDownloaderModule";

interface NativeModuleShape {
  startPlannedJob(planJson: string): Promise<
    JobStatus & {
      masterPlaylistUri?: string;
      createdAt?: number;
    }
  >;
  pauseJob(id: string): Promise<JobStatus>;
  resumeJob(id: string): Promise<JobStatus>;
  cancelJob(id: string): Promise<JobStatus>;
  getJobStatus(id: string): Promise<JobStatus>;
  listJobs(): Promise<string>;
}

export class NativeDownloaderBridge implements DownloaderBridge {
  private readonly nativeModule: NativeModuleShape;
  private readonly emitter: NativeEventEmitter;

  constructor(
    onProgress: (status: JobStatus) => void,
    onError: (error: JobError) => void,
  ) {
    const module = NativeModules[MODULE_NAME] as NativeModuleShape | undefined;
    if (!module || Platform.OS !== "android") {
      throw new Error("Native downloader module not available.");
    }
    this.nativeModule = module;
    this.emitter = new NativeEventEmitter(module as never);
    this.emitter.addListener("downloadProgress", (payload) => {
      try {
        const data = typeof payload === "string" ? JSON.parse(payload) : payload;
        onProgress({
          id: data.id,
          state: data.state,
          progress: data.progress,
        });
      } catch (error) {
        console.error("Failed to parse downloadProgress event:", error, "payload:", payload);
      }
    });
    this.emitter.addListener("downloadError", (payload) => {
      try {
        const data = typeof payload === "string" ? JSON.parse(payload) : payload;
        onError({
          id: data.id,
          code: data.code,
          message: data.message,
          detail: data.detail,
        });
      } catch (error) {
        console.error("Failed to parse downloadError event:", error, "payload:", payload);
        // Still try to report a generic error
        onError({
          id: "unknown",
          code: "json_parse_error",
          message: "Failed to parse error event from native module",
          detail: String(error),
        });
      }
    });
  }

  async startJob(request: StartJobRequest) {
    throw new Error("Use startPlannedJob for native downloads.");
  }

  async startPlannedJob(plan: DownloadPlan) {
    const status = await this.nativeModule.startPlannedJob(JSON.stringify(plan));
    return {
      id: status.id,
      state: status.state,
      progress: status.progress,
      masterPlaylistUri: status.masterPlaylistUri ?? plan.masterPlaylistUri,
      createdAt: status.createdAt ?? Date.now(),
    };
  }

  pauseJob(id: string) {
    return this.nativeModule.pauseJob(id);
  }

  resumeJob(id: string) {
    return this.nativeModule.resumeJob(id);
  }

  cancelJob(id: string) {
    return this.nativeModule.cancelJob(id);
  }

  getJobStatus(id: string) {
    return this.nativeModule.getJobStatus(id);
  }

  async listJobs(): Promise<DownloadJob[]> {
    const payload = await this.nativeModule.listJobs();
    const items = JSON.parse(payload) as Array<{
      id: string;
      state: JobStatus["state"];
      progress: JobStatus["progress"];
      masterPlaylistUri: string;
      createdAt: number;
    }>;
    return items.map((item) => ({
      id: item.id,
      masterPlaylistUri: item.masterPlaylistUri,
      createdAt: item.createdAt,
      state: item.state,
      progress: item.progress,
    }));
  }
}

import type { DownloadJob, JobProgress, JobState } from "../domain/types.ts";
import type { ErrorCode } from "../domain/errors.ts";

export interface StartJobRequest {
  id: string;
  masterPlaylistUri: string;
  headers?: Record<string, string>;
}

export interface JobStatus {
  id: string;
  state: JobState;
  progress: JobProgress;
}

export interface JobError {
  id: string;
  code: ErrorCode;
  message: string;
  detail?: string;
}

export interface DownloaderBridge {
  startJob(request: StartJobRequest): Promise<DownloadJob>;
  pauseJob(id: string): Promise<JobStatus>;
  resumeJob(id: string): Promise<JobStatus>;
  cancelJob(id: string): Promise<JobStatus>;
  getJobStatus(id: string): Promise<JobStatus>;
}

export type ProgressCallback = (status: JobStatus) => void;
export type ErrorCallback = (error: JobError) => void;

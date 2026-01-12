import { useCallback, useEffect, useMemo, useState } from "react";
import type { DownloadJob } from "../domain/types.ts";
import type { StartJobRequest, JobError, JobStatus } from "../bridge/api.ts";
import type { DownloadManager } from "../bridge/downloadManager.ts";

interface UseDownloadJobsResult {
  jobs: DownloadJob[];
  lastError?: JobError;
  start: (request: StartJobRequest) => Promise<DownloadJob>;
  pause: (id: string) => Promise<JobStatus>;
  resume: (id: string) => Promise<JobStatus>;
  cancel: (id: string) => Promise<JobStatus>;
  refresh: (id: string) => Promise<JobStatus>;
}

export function useDownloadJobs(manager: DownloadManager): UseDownloadJobsResult {
  const [jobs, setJobs] = useState<DownloadJob[]>([]);
  const [lastError, setLastError] = useState<JobError | undefined>();

  useEffect(() => {
    let mounted = true;
    manager.listJobs().then((items) => {
      if (mounted) {
        setJobs(items);
      }
    });
    return () => {
      mounted = false;
    };
  }, [manager]);

  useEffect(() => {
    const unsubscribeProgress = manager.onProgress((status) => {
      setJobs((current) => {
        const existing = current.find((job) => job.id === status.id);
        if (!existing) {
          return [
            ...current,
            {
              id: status.id,
              masterPlaylistUri: "",
              createdAt: Date.now(),
              state: status.state,
              progress: status.progress,
            },
          ];
        }
        return current.map((job) =>
          job.id === status.id ? { ...job, state: status.state, progress: status.progress } : job,
        );
      });
    });
    const unsubscribeError = manager.onError((error) => {
      setLastError(error);
    });
    return () => {
      unsubscribeProgress();
      unsubscribeError();
    };
  }, [manager]);

  const start = useCallback(
    async (request: StartJobRequest) => {
      const job = await manager.startPlanned(request);
      setJobs((current) => [...current, job]);
      return job;
    },
    [manager],
  );

  const pause = useCallback((id: string) => manager.pause(id), [manager]);
  const resume = useCallback((id: string) => manager.resume(id), [manager]);
  const cancel = useCallback((id: string) => manager.cancel(id), [manager]);
  const refresh = useCallback((id: string) => manager.getStatus(id), [manager]);

  return useMemo(
    () => ({
      jobs,
      lastError,
      start,
      pause,
      resume,
      cancel,
      refresh,
    }),
    [jobs, lastError, start, pause, resume, cancel, refresh],
  );
}

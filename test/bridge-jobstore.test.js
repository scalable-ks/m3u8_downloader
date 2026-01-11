import test from "node:test";
import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { promises as fs } from "node:fs";
import { NodeFileJobStore } from "../src/bridge/nodeFileJobStore.ts";

test("NodeFileJobStore persists jobs across instances", async () => {
  // ARRANGE
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "job-store-"));
  const job = {
    id: "job-1",
    masterPlaylistUri: "https://example.com/master.m3u8",
    createdAt: Date.now(),
    state: "running",
    progress: {
      bytesDownloaded: 10,
      totalBytes: 20,
      segmentsDownloaded: 1,
      totalSegments: 2,
    },
  };

  // ACT
  const store = new NodeFileJobStore(tempDir);
  await store.save(job);
  const restoredStore = new NodeFileJobStore(tempDir);
  const jobs = await restoredStore.list();

  // ASSERT
  assert.equal(jobs.length, 1);
  assert.equal(jobs[0].id, "job-1");
});

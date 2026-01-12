import test from "node:test";
import assert from "node:assert/strict";
import { DownloadManager } from "../src/bridge/downloadManager.ts";

class CapturingBridge {
  constructor() {
    this.lastRequest = null;
  }

  async startJob(request) {
    this.lastRequest = request;
    return {
      id: request.id,
      masterPlaylistUri: request.masterPlaylistUri,
      createdAt: Date.now(),
      state: "running",
      progress: {
        bytesDownloaded: 0,
        totalBytes: 100,
        segmentsDownloaded: 0,
        totalSegments: 1,
      },
    };
  }

  async pauseJob() {
    throw new Error("not used");
  }

  async resumeJob() {
    throw new Error("not used");
  }

  async cancelJob() {
    throw new Error("not used");
  }

  async getJobStatus() {
    throw new Error("not used");
  }
}

test("DownloadManager forwards exportTreeUri to bridge", async () => {
  // ARRANGE
  const bridge = new CapturingBridge();
  const manager = new DownloadManager(bridge);

  // ACT
  await manager.start({
    id: "job-1",
    masterPlaylistUri: "https://example.com/master.m3u8",
    exportTreeUri: "content://tree/primary%3ADownloads",
  });

  // ASSERT
  assert.equal(bridge.lastRequest.exportTreeUri, "content://tree/primary%3ADownloads");
});

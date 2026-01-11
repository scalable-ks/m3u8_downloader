package com.rnandroidhls.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class FileDownloadStateStoreTest {
    @Test
    fun `persists and reloads job state`() {
        // ARRANGE
        val baseDir = createTempDirectory().toFile()
        val store = FileDownloadStateStore(baseDir)
        val state =
            DownloadJobState(
                id = "job-1",
                playlistUri = "https://example.com/master.m3u8",
                state = JobState.RUNNING,
                segments =
                    listOf(
                        SegmentState(
                            uri = "https://example.com/seg1.ts",
                            sequence = 1,
                            status = SegmentStatus.PENDING,
                            bytesDownloaded = 0,
                        ),
                        SegmentState(
                            uri = "https://example.com/seg2.ts",
                            sequence = 2,
                            status = SegmentStatus.COMPLETED,
                            bytesDownloaded = 12,
                            totalBytes = 12,
                        ),
                    ),
                createdAt = 1_000,
                updatedAt = 2_000,
            )

        // ACT
        store.save(state)
        val loaded = store.get("job-1")

        // ASSERT
        assertNotNull(loaded)
        assertEquals(JobState.RUNNING, loaded?.state)
        assertEquals(2, loaded?.segments?.size)
        assertEquals(SegmentStatus.COMPLETED, loaded?.segments?.get(1)?.status)
        assertEquals(12, loaded?.segments?.get(1)?.totalBytes)
    }

    @Test
    fun `updates segment state and deletes job`() {
        // ARRANGE
        val baseDir = createTempDirectory().toFile()
        val store = FileDownloadStateStore(baseDir)
        val state =
            DownloadJobState(
                id = "job-2",
                playlistUri = "https://example.com/master.m3u8",
                state = JobState.RUNNING,
                segments =
                    listOf(
                        SegmentState(
                            uri = "https://example.com/seg1.ts",
                            sequence = 1,
                            status = SegmentStatus.PENDING,
                            bytesDownloaded = 0,
                        ),
                    ),
                createdAt = 1_000,
                updatedAt = 1_000,
            )
        store.save(state)

        // ACT
        store.updateSegment(
            "job-2",
            SegmentState(
                uri = "https://example.com/seg1.ts",
                sequence = 1,
                status = SegmentStatus.COMPLETED,
                bytesDownloaded = 5,
                totalBytes = 5,
            ),
        )
        val updated = store.get("job-2")
        store.delete("job-2")
        val deleted = store.get("job-2")

        // ASSERT
        assertEquals(SegmentStatus.COMPLETED, updated?.segments?.firstOrNull()?.status)
        assertNull(deleted)
    }
}

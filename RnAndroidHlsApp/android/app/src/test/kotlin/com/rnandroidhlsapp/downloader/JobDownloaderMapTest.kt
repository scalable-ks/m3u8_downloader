package com.rnandroidhlsapp.downloader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

private class InMemoryStateStore : DownloadStateStore {
    private val states = ConcurrentHashMap<String, DownloadJobState>()

    override fun get(jobId: String): DownloadJobState? = states[jobId]

    override fun list(): List<DownloadJobState> = states.values.toList()

    @Synchronized
    override fun save(state: DownloadJobState) {
        states[state.id] = state
    }

    @Synchronized
    override fun updateSegment(
        jobId: String,
        segment: SegmentState,
    ) {
        val existing = states[jobId] ?: return
        val updated =
            existing.copy(
                segments = existing.segments.map { if (it.fileKey == segment.fileKey) segment else it },
                updatedAt = System.currentTimeMillis(),
            )
        states[jobId] = updated
    }

    @Synchronized
    override fun delete(jobId: String) {
        states.remove(jobId)
    }
}

private class RecordingSegmentDownloader : SegmentDownloader(OkHttpClient()) {
    val calls = CopyOnWriteArrayList<String>()

    override fun downloadSegment(
        segment: Segment,
        destination: File,
        headers: Map<String, String>,
        resumeBytes: Long,
    ): Long {
        calls.add(segment.fileKey)
        destination.sink(append = resumeBytes > 0).buffer().use { it.writeUtf8("data") }
        return resumeBytes + 4
    }
}

class JobDownloaderMapTest {
    @Test
    fun `downloads init map before segment and uses file keys`() =
        runBlocking {
            // ARRANGE
            val stateStore = InMemoryStateStore()
            val progressListener = object : ProgressListener {
                override fun onProgress(
                    jobId: String,
                    progress: JobProgress,
                ) = Unit
            }
            val errorListener = object : ErrorListener {
                override fun onError(
                    jobId: String,
                    code: String,
                    message: String,
                    detail: String?,
                ) = Unit
            }
            val segmentDownloader = RecordingSegmentDownloader()
            val downloader =
                JobDownloader(
                    stateStore = stateStore,
                    progressListener = progressListener,
                    errorListener = errorListener,
                    segmentDownloader = segmentDownloader,
                    maxParallel = 1,
                )
            val outputDir = createTempDirectory().toFile()
            val request =
                DownloadRequest(
                    id = "job-map",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val map =
                SegmentMap(
                    uri = "https://example.com/init.mp4",
                    fileKey = "video_init",
                )
            val segment =
                Segment(
                    uri = "https://example.com/seg1.m4s",
                    duration = 4.0,
                    sequence = 1,
                    fileKey = "video_1",
                    map = map,
                )

            // ACT
            downloader.start(request, listOf(segment))

            // ASSERT
            withTimeout(5_000) {
                while (true) {
                    val state = stateStore.get(request.id)
                    if (state != null && state.state == JobState.COMPLETED) break
                    delay(50)
                }
            }
            assertEquals(listOf("map_video_init", "video_1"), segmentDownloader.calls.toList())
            assertTrue(File(outputDir, "map_video_init.bin").exists())
            assertTrue(File(outputDir, "segment_video_1.bin").exists())
            val finalState = stateStore.get(request.id)
            assertEquals("video_1", finalState?.segments?.firstOrNull()?.fileKey)
        }
}

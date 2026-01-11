package com.rnandroidhls.downloader

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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

private class InMemoryStateStore : DownloadStateStore {
    private val states = ConcurrentHashMap<String, DownloadJobState>()

    override fun get(jobId: String): DownloadJobState? = states[jobId]

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
                segments = existing.segments.map { if (it.sequence == segment.sequence) segment else it },
                updatedAt = System.currentTimeMillis(),
            )
        states[jobId] = updated
    }

    @Synchronized
    override fun delete(jobId: String) {
        states.remove(jobId)
    }
}

private class RecordingProgressListener : ProgressListener {
    val updates = CopyOnWriteArrayList<JobProgress>()

    override fun onProgress(
        jobId: String,
        progress: JobProgress,
    ) {
        updates.add(progress)
    }
}

private class RecordingErrorListener : ErrorListener {
    val errors = CopyOnWriteArrayList<String>()

    override fun onError(
        jobId: String,
        code: String,
        message: String,
        detail: String?,
    ) {
        errors.add(code)
    }
}

private class FakeSegmentDownloader(
    private val failOnSequence: Long? = null,
) : SegmentDownloader(OkHttpClient()) {
    override fun downloadSegment(
        segment: Segment,
        destination: File,
        headers: Map<String, String>,
        resumeBytes: Long,
    ): Long {
        if (failOnSequence == segment.sequence) {
            throw IOException("forced failure")
        }
        destination.sink(append = resumeBytes > 0).buffer().use { it.writeUtf8("data") }
        return resumeBytes + 4
    }
}

class JobDownloaderTest {
    @Test
    fun `completes all segments and updates progress`() =
        runBlocking {
            // ARRANGE
            val stateStore = InMemoryStateStore()
            val progressListener = RecordingProgressListener()
            val errorListener = RecordingErrorListener()
            val fakeDownloader = FakeSegmentDownloader()
            val downloader =
                JobDownloader(stateStore, progressListener, errorListener, segmentDownloader = fakeDownloader)
            val outputDir = createTempDirectory().toFile()
            val request =
                DownloadRequest(
                    id = "job-1",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val segments =
                listOf(
                    Segment("https://example.com/seg1.ts", duration = 4.0, sequence = 1),
                    Segment("https://example.com/seg2.ts", duration = 4.0, sequence = 2),
                )

            // ACT
            downloader.start(request, segments)

            // ASSERT
            withTimeout(5_000) {
                while (true) {
                    val state = stateStore.get(request.id)
                    if (state != null && state.state == JobState.COMPLETED) break
                    delay(50)
                }
            }
            val finalState = stateStore.get(request.id)
            assertEquals(JobState.COMPLETED, finalState?.state)
            assertEquals(2, finalState?.segments?.count { it.status == SegmentStatus.COMPLETED })
            assertTrue(progressListener.updates.isNotEmpty())
        }

    @Test
    fun `marks job failed when segment fails and retries are exhausted`() =
        runBlocking {
            // ARRANGE
            val stateStore = InMemoryStateStore()
            val progressListener = RecordingProgressListener()
            val errorListener = RecordingErrorListener()
            val retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMs = 50, maxDelayMs = 200)
            val fakeDownloader = FakeSegmentDownloader(failOnSequence = 1)
            val downloader =
                JobDownloader(
                    stateStore,
                    progressListener,
                    errorListener,
                    retryPolicy = retryPolicy,
                    segmentDownloader = fakeDownloader,
                )
            val outputDir = createTempDirectory().toFile()
            val request =
                DownloadRequest(
                    id = "job-2",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val segments =
                listOf(
                    Segment("https://example.com/seg1.ts", duration = 4.0, sequence = 1),
                )

            // ACT
            downloader.start(request, segments)

            // ASSERT
            withTimeout(5_000) {
                while (true) {
                    val state = stateStore.get(request.id)
                    if (state != null && state.state == JobState.FAILED) break
                    delay(50)
                }
            }
            val finalState = stateStore.get(request.id)
            assertEquals(JobState.FAILED, finalState?.state)
            assertTrue(errorListener.errors.isNotEmpty())
        }
}

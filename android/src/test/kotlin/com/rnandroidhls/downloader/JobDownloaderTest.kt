package com.rnandroidhls.downloader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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

    @Test
    fun `retries segment download after transient failure`() =
        runBlocking {
            // ARRANGE
            val attempt = AtomicInteger(0)
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/seg1.ts") {
                            return if (attempt.incrementAndGet() == 1) {
                                MockResponse().setResponseCode(500)
                            } else {
                                MockResponse().setBody("data")
                            }
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }
            server.start()

            val stateStore = InMemoryStateStore()
            val progressListener = RecordingProgressListener()
            val errorListener = RecordingErrorListener()
            val retryPolicy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10, maxDelayMs = 50)
            val downloader =
                JobDownloader(
                    stateStore,
                    progressListener,
                    errorListener,
                    retryPolicy = retryPolicy,
                    segmentDownloader = SegmentDownloader(OkHttpClient()),
                )
            val outputDir = createTempDirectory().toFile()
            val request =
                DownloadRequest(
                    id = "job-3",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val segments =
                listOf(
                    Segment(server.url("/seg1.ts").toString(), duration = 4.0, sequence = 1),
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
            val finalFile = File(outputDir, "segment_1.bin")
            assertTrue(finalFile.exists())
            assertEquals("data", finalFile.readText())
            assertTrue(errorListener.errors.isEmpty())

            server.shutdown()
        }

    @Test
    fun `resumes partial segment with range request`() =
        runBlocking {
            // ARRANGE
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(206).setBody("world"))
            server.start()

            val stateStore = InMemoryStateStore()
            val progressListener = RecordingProgressListener()
            val errorListener = RecordingErrorListener()
            val downloader =
                JobDownloader(
                    stateStore,
                    progressListener,
                    errorListener,
                    segmentDownloader = SegmentDownloader(OkHttpClient()),
                )
            val outputDir = createTempDirectory().toFile()
            val partialFile = File(outputDir, "segment_1.partial")
            partialFile.sink(append = false).buffer().use { it.writeUtf8("hello ") }
            val request =
                DownloadRequest(
                    id = "job-4",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val segments =
                listOf(
                    Segment(server.url("/seg1.ts").toString(), duration = 4.0, sequence = 1),
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
            val requestCapture = server.takeRequest()
            assertEquals("bytes=6-", requestCapture.getHeader("Range"))
            val finalFile = File(outputDir, "segment_1.bin")
            assertTrue(finalFile.exists())
            assertEquals("hello world", finalFile.readText())
            assertTrue(errorListener.errors.isEmpty())

            server.shutdown()
        }

    @Test
    fun `downloads and decrypts segment in job flow`() =
        runBlocking {
            // ARRANGE
            val keyBytes = "0123456789abcdef".toByteArray()
            val ivBytes = ByteArray(16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            val plaintext = "secret"
            val encrypted = cipher.doFinal(plaintext.toByteArray())

            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when (request.path) {
                            "/seg1.ts" -> MockResponse().setBody(Buffer().write(encrypted))
                            "/key" -> MockResponse().setBody(Buffer().write(keyBytes))
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            server.start()

            val stateStore = InMemoryStateStore()
            val progressListener = RecordingProgressListener()
            val errorListener = RecordingErrorListener()
            val downloader =
                JobDownloader(
                    stateStore,
                    progressListener,
                    errorListener,
                    segmentDownloader = SegmentDownloader(OkHttpClient()),
                )
            val outputDir = createTempDirectory().toFile()
            val request =
                DownloadRequest(
                    id = "job-5",
                    playlistUri = "https://example.com/master.m3u8",
                    outputDir = outputDir,
                )
            val segments =
                listOf(
                    Segment(
                        uri = server.url("/seg1.ts").toString(),
                        duration = 4.0,
                        sequence = 1,
                        key =
                            SegmentKey(
                                method = "AES-128",
                                uri = server.url("/key").toString(),
                                iv = "0x00000000000000000000000000000000",
                            ),
                    ),
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
            val finalFile = File(outputDir, "segment_1.bin")
            assertTrue(finalFile.exists())
            assertEquals(plaintext, finalFile.readText())
            assertTrue(errorListener.errors.isEmpty())

            server.shutdown()
        }
}

package com.rnandroidhls

import com.rnandroidhls.downloader.DownloadJobState
import com.rnandroidhls.downloader.DownloadRequest
import com.rnandroidhls.downloader.DownloadStateStore
import com.rnandroidhls.downloader.ErrorListener
import com.rnandroidhls.downloader.JobDownloader
import com.rnandroidhls.downloader.JobState
import com.rnandroidhls.downloader.ProgressListener
import com.rnandroidhls.downloader.RetryPolicy
import com.rnandroidhls.downloader.Segment
import com.rnandroidhls.downloader.SegmentDownloader
import com.rnandroidhls.downloader.SegmentState
import com.rnandroidhls.muxing.ConcatListWriter
import com.rnandroidhls.muxing.FfmpegResult
import com.rnandroidhls.muxing.FfmpegRunner
import com.rnandroidhls.muxing.Mp4Assembler
import com.rnandroidhls.muxing.MuxRequest
import com.rnandroidhls.muxing.TrackInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory

private class E2eStateStore : DownloadStateStore {
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

private class NoopProgressListener : ProgressListener {
    override fun onProgress(
        jobId: String,
        progress: com.rnandroidhls.downloader.JobProgress,
    ) {
    }
}

private class NoopErrorListener : ErrorListener {
    override fun onError(
        jobId: String,
        code: String,
        message: String,
        detail: String?,
    ) {
    }
}

private class FakeFfmpegRunner : FfmpegRunner {
    var lastCommand: String? = null

    override fun run(command: String): FfmpegResult {
        lastCommand = command
        val outputPath = command.split(" ").last()
        File(outputPath).writeText("mp4")
        return FfmpegResult(success = true, returnCode = 0)
    }
}

class PipelineE2eTest {
    @Test
    fun `downloads segments assembles mp4 and exports`() =
        runBlocking {
            // ARRANGE
            val server = MockWebServer()
            val masterPlaylist =
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000
                video.m3u8
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="eng",URI="audio.m3u8"
                """.trimIndent()
            val videoPlaylist =
                """
                #EXTM3U
                #EXTINF:4.0,
                video1.ts
                #EXTINF:4.0,
                video2.ts
                """.trimIndent()
            val audioPlaylist =
                """
                #EXTM3U
                #EXTINF:4.0,
                audio1.aac
                #EXTINF:4.0,
                audio2.aac
                """.trimIndent()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when (request.path) {
                            "/master.m3u8" -> MockResponse().setBody(masterPlaylist)
                            "/video.m3u8" -> MockResponse().setBody(videoPlaylist)
                            "/audio.m3u8" -> MockResponse().setBody(audioPlaylist)
                            "/video1.ts" -> MockResponse().setBody("one")
                            "/video2.ts" -> MockResponse().setBody("two")
                            "/audio1.aac" -> MockResponse().setBody("three")
                            "/audio2.aac" -> MockResponse().setBody("four")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            server.start()
            val client = OkHttpClient()
            val masterUrl = server.url("/master.m3u8").toString()
            val masterContent = fetchText(client, masterUrl)
            val videoPlaylistUrl = parseVariantUri(masterContent, masterUrl)
            val audioPlaylistUrl = parseAudioUri(masterContent, masterUrl)
            val videoContent = fetchText(client, videoPlaylistUrl)
            val audioContent = fetchText(client, audioPlaylistUrl)
            val videoSegments = parseSegmentUris(videoContent, videoPlaylistUrl)
            val audioSegments = parseSegmentUris(audioContent, audioPlaylistUrl)

            val stateStore = E2eStateStore()
            val progressListener = NoopProgressListener()
            val errorListener = NoopErrorListener()
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
            val videoRequest =
                DownloadRequest(
                    id = "job-e2e-video",
                    playlistUri = videoPlaylistUrl,
                    outputDir = File(outputDir, "video"),
                )
            val audioRequest =
                DownloadRequest(
                    id = "job-e2e-audio",
                    playlistUri = audioPlaylistUrl,
                    outputDir = File(outputDir, "audio"),
                )
            videoRequest.outputDir.mkdirs()
            audioRequest.outputDir.mkdirs()
            val videoSegmentsWithSeq =
                videoSegments.mapIndexed { index, uri ->
                    Segment(uri = uri, duration = 4.0, sequence = index.toLong() + 1)
                }
            val audioSegmentsWithSeq =
                audioSegments.mapIndexed { index, uri ->
                    Segment(uri = uri, duration = 4.0, sequence = index.toLong() + 1)
                }

            // ACT
            downloader.start(videoRequest, videoSegmentsWithSeq)
            downloader.start(audioRequest, audioSegmentsWithSeq)

            // ASSERT
            withTimeout(5_000) {
                while (true) {
                    val videoState = stateStore.get(videoRequest.id)
                    val audioState = stateStore.get(audioRequest.id)
                    if (videoState?.state == JobState.FAILED || audioState?.state == JobState.FAILED) {
                        error("Download failed")
                    }
                    if (videoState?.state == JobState.COMPLETED && audioState?.state == JobState.COMPLETED) {
                        break
                    }
                    delay(50)
                }
            }
            val segmentFiles =
                listOf(
                    File(videoRequest.outputDir, "segment_1.bin"),
                    File(videoRequest.outputDir, "segment_2.bin"),
                )
            val audioFiles =
                listOf(
                    File(audioRequest.outputDir, "segment_1.bin"),
                    File(audioRequest.outputDir, "segment_2.bin"),
                )
            assertTrue(segmentFiles.all { it.exists() })
            assertTrue(audioFiles.all { it.exists() })

            val concatDir = createTempDirectory().toFile()
            val videoConcat = ConcatListWriter.write(File(concatDir, "video.txt"), segmentFiles)
            val audioConcat = ConcatListWriter.write(File(concatDir, "audio.txt"), audioFiles)
            val outputMp4 = File(concatDir, "output.mp4")
            val runner = FakeFfmpegRunner()
            val assembler = Mp4Assembler(runner)
            val result =
                assembler.assemble(
                    MuxRequest(
                        video = TrackInput(path = videoConcat.absolutePath, isConcatList = true),
                        audio = TrackInput(path = audioConcat.absolutePath, isConcatList = true),
                        outputPath = outputMp4.absolutePath,
                    ),
                )

            assertTrue(result.success)
            assertTrue(outputMp4.exists())

            val exportDir = createTempDirectory().toFile()
            val exported = outputMp4.copyTo(File(exportDir, "final.mp4"), overwrite = true)
            assertTrue(exported.exists())
            assertEquals("mp4", exported.readText())

            server.shutdown()
        }

    private fun fetchText(
        client: OkHttpClient,
        url: String,
    ): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    private fun parseVariantUri(
        content: String,
        baseUrl: String,
    ): String {
        val lines = content.lines().map { it.trim() }
        for (index in lines.indices) {
            if (lines[index].startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(index + 1) ?: continue
                if (next.isNotBlank() && !next.startsWith("#")) {
                    return resolve(baseUrl, next)
                }
            }
        }
        error("Missing variant")
    }

    private fun parseAudioUri(
        content: String,
        baseUrl: String,
    ): String {
        val line =
            content.lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }
                ?: error("Missing audio")
        val uriPart =
            line.split(",")
                .firstOrNull { it.trim().startsWith("URI=") }
                ?: error("Missing audio URI")
        val raw = uriPart.substringAfter("URI=").trim().trim('"')
        return resolve(baseUrl, raw)
    }

    private fun parseSegmentUris(
        content: String,
        baseUrl: String,
    ): List<String> {
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolve(baseUrl, it) }
    }

    private fun resolve(
        baseUrl: String,
        path: String,
    ): String {
        return URI(baseUrl).resolve(path).toString()
    }
}

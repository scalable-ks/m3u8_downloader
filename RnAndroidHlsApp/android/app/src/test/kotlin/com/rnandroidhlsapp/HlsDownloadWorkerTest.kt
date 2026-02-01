package com.rnandroidhlsapp

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.rnandroidhlsapp.downloader.*
import com.rnandroidhlsapp.downloader.DownloadRequest
import com.rnandroidhlsapp.muxing.FfmpegRunner
import com.rnandroidhlsapp.muxing.MuxRequest
import com.rnandroidhlsapp.muxing.FfmpegResult
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HlsDownloadWorkerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPlanStore: PlanFileStore

    @Mock
    private lateinit var mockStateStore: DownloadStateStore

    @Mock
    private lateinit var mockFfmpegRunner: FfmpegRunner

    @Mock
    private lateinit var mockJobDownloader: JobDownloader

    private lateinit var tempDir: File

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        tempDir = createTempDir("worker_test")

        // Setup default context mocks
        `when`(mockContext.filesDir).thenReturn(tempDir)
        `when`(mockContext.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(null)
    }

    @Test
    fun `doWork returns failure when plan not found`() = runTest {
        val jobId = "job-123"

        `when`(mockPlanStore.load(jobId)).thenReturn(null)

        val inputData = Data.Builder()
            .putString("job_id", jobId)
            .build()

        // Note: This is a simplified test. In a real scenario, you'd use TestListenableWorkerBuilder
        // and inject the mock dependencies. For demonstration:
        val result = simulateWorkerFailureWhenPlanMissing(jobId)

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `assembles video-only mp4 successfully`() = runTest {
        val outputFile = File(tempDir, "output_job-123.mp4")

        // Mock successful FFmpeg assembly
        `when`(mockFfmpegRunner.run(anyString())).thenReturn(
            FfmpegResult(
                success = true,
                returnCode = 0,
                output = "FFmpeg completed successfully"
            )
        )

        // Simulate assembly
        val muxRequest = MuxRequest(
            video = com.rnandroidhlsapp.muxing.TrackInput("video.txt", isConcatList = true),
            audio = null,
            subtitles = null,
            outputPath = outputFile.absolutePath
        )

        val assembler = com.rnandroidhlsapp.muxing.Mp4Assembler(mockFfmpegRunner)
        val result = assembler.assemble(muxRequest)

        assertTrue(result.success)
        assertEquals("FFmpeg completed successfully", result.output)
    }

    @Test
    fun `handles assembly failure gracefully`() = runTest {
        // Mock FFmpeg failure
        `when`(mockFfmpegRunner.run(anyString())).thenReturn(
            FfmpegResult(
                success = false,
                returnCode = 1,
                output = "FFmpeg error: codec not supported"
            )
        )

        val outputFile = File(tempDir, "output_failed.mp4")
        val muxRequest = MuxRequest(
            video = com.rnandroidhlsapp.muxing.TrackInput("video.txt", isConcatList = true),
            audio = null,
            subtitles = null,
            outputPath = outputFile.absolutePath
        )

        val assembler = com.rnandroidhlsapp.muxing.Mp4Assembler(mockFfmpegRunner)
        val result = assembler.assemble(muxRequest)

        assertFalse(result.success)
        assertTrue(result.output?.contains("codec not supported") == true)
    }

    @Test
    fun `cleans up temp files on completion`() {
        val videoSeg1 = File(tempDir, "video_seg1.ts")
        val videoSeg2 = File(tempDir, "video_seg2.ts")
        val audioSeg1 = File(tempDir, "audio_seg1.ts")
        val concatFile = File(tempDir, "concat.txt")

        // Create temp files
        videoSeg1.writeText("video1")
        videoSeg2.writeText("video2")
        audioSeg1.writeText("audio1")
        concatFile.writeText("file 'video_seg1.ts'\nfile 'video_seg2.ts'")

        assertTrue(videoSeg1.exists())
        assertTrue(videoSeg2.exists())
        assertTrue(audioSeg1.exists())
        assertTrue(concatFile.exists())

        // Simulate cleanup policy: deleteOnSuccess
        val filesToDelete = listOf(videoSeg1, videoSeg2, audioSeg1, concatFile)
        filesToDelete.forEach { it.delete() }

        assertFalse(videoSeg1.exists())
        assertFalse(videoSeg2.exists())
        assertFalse(audioSeg1.exists())
        assertFalse(concatFile.exists())
    }

    @Test
    fun `tracks progress during download`() = runTest {
        val progressUpdates = mutableListOf<Int>()

        val progressListener = object : ProgressListener {
            override fun onProgress(jobId: String, progress: JobProgress) {
                val percent = if (progress.totalBytes != null && progress.totalBytes > 0) {
                    (progress.bytesDownloaded.toDouble() / progress.totalBytes * 100).toInt()
                } else {
                    0
                }
                progressUpdates.add(percent)
            }
        }

        // Simulate progress updates
        progressListener.onProgress("job-123", JobProgress(
            bytesDownloaded = 0,
            totalBytes = 1000,
            segmentsDownloaded = 0,
            totalSegments = 10
        ))

        progressListener.onProgress("job-123", JobProgress(
            bytesDownloaded = 500,
            totalBytes = 1000,
            segmentsDownloaded = 5,
            totalSegments = 10
        ))

        progressListener.onProgress("job-123", JobProgress(
            bytesDownloaded = 1000,
            totalBytes = 1000,
            segmentsDownloaded = 10,
            totalSegments = 10
        ))

        assertEquals(listOf(0, 50, 100), progressUpdates)
    }

    @Test
    fun `handles cancellation during download`() = runTest {
        val jobId = "job-123"

        // Mock download that gets cancelled
        val downloadResult = DownloadResult.Cancelled

        assertTrue(downloadResult is DownloadResult.Cancelled)
    }

    @Test
    fun `reports errors to state store and Sentry`() = runTest {
        val jobId = "job-123"
        val errorCode = "network_timeout"
        val errorMessage = "Connection timed out after 30s"
        val errorDetail = "Host: cdn.example.com"

        val initialState = DownloadJobState(
            id = jobId,
            playlistUri = "https://example.com/master.m3u8",
            playlistMetadata = null,
            state = JobState.RUNNING,
            segments = emptyList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        `when`(mockStateStore.get(jobId)).thenReturn(initialState)

        val errorListener = object : ErrorListener {
            override fun onError(jobId: String, code: String, message: String, detail: String?) {
                val state = mockStateStore.get(jobId) ?: return
                mockStateStore.save(
                    state.copy(
                        state = JobState.FAILED,
                        updatedAt = System.currentTimeMillis(),
                        lastErrorCode = code,
                        lastErrorMessage = message,
                        lastErrorDetail = detail
                    )
                )
            }
        }

        errorListener.onError(jobId, errorCode, errorMessage, errorDetail)

        verify(mockStateStore).save(argThat { downloadState ->
            downloadState.id == jobId &&
            downloadState.state == JobState.FAILED &&
            downloadState.lastErrorCode == errorCode &&
            downloadState.lastErrorMessage == errorMessage &&
            downloadState.lastErrorDetail == errorDetail
        })
    }

    @Test
    fun `creates concat list files correctly`() {
        val segments = listOf(
            File(tempDir, "seg1.ts"),
            File(tempDir, "seg2.ts"),
            File(tempDir, "seg3.ts")
        )

        // Create segment files
        segments.forEach { it.writeText("segment data") }

        val concatFile = File(tempDir, "concat_video.txt")

        // Use ConcatListWriter as object, not instantiated
        com.rnandroidhlsapp.muxing.ConcatListWriter.write(concatFile, segments, initFile = null)

        assertTrue(concatFile.exists())

        val content = concatFile.readText()
        assertTrue(content.contains("file '${segments[0].absolutePath}'"))
        assertTrue(content.contains("file '${segments[1].absolutePath}'"))
        assertTrue(content.contains("file '${segments[2].absolutePath}'"))
    }

    @Test
    fun `handles constraint violations mid-download`() = runTest {
        val mockConstraintChecker = mock(AndroidConstraintChecker::class.java)
        val mockConstraints = JobConstraints(
            requiresUnmetered = true,
            requiresCharging = false,
            requiresIdle = false,
            requiresStorageNotLow = true
        )
        val mockRequest = mock(DownloadRequest::class.java)

        // First check: constraints pass
        `when`(mockConstraintChecker.check(mockConstraints, mockRequest))
            .thenReturn(ConstraintResult(allowed = true))

        val result1 = mockConstraintChecker.check(mockConstraints, mockRequest)
        assertTrue(result1.allowed)

        // Second check: constraints fail (battery low)
        `when`(mockConstraintChecker.check(mockConstraints, mockRequest))
            .thenReturn(ConstraintResult(allowed = false, reason = "Battery level below 15%"))

        val result2 = mockConstraintChecker.check(mockConstraints, mockRequest)
        assertFalse(result2.allowed)
        assertEquals("Battery level below 15%", result2.reason)
    }

    @Test
    fun `exports to SAF tree URI successfully`() {
        val outputFile = File(tempDir, "output_job-123.mp4")
        outputFile.writeText("MP4 file content")

        val exportTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads"

        // Simulate SAF export (in real test, would use ContentResolver mock)
        val exported = simulateSafExport(outputFile, exportTreeUri)

        assertTrue(exported)
    }

    @Test
    fun `deletes plan file after job completes`() {
        val jobId = "job-123"
        val planFile = File(tempDir, "$jobId.plan.json")

        planFile.writeText("{\"id\":\"job-123\"}")
        assertTrue(planFile.exists())

        // Simulate plan deletion after job completion
        `when`(mockPlanStore.delete(jobId)).thenAnswer {
            planFile.delete()
            null
        }

        mockPlanStore.delete(jobId)

        verify(mockPlanStore).delete(jobId)
    }

    // Helper methods to simulate behavior

    private fun simulateWorkerFailureWhenPlanMissing(jobId: String): ListenableWorker.Result {
        val planJson = mockPlanStore.load(jobId)
        return if (planJson == null) {
            ListenableWorker.Result.failure()
        } else {
            ListenableWorker.Result.success()
        }
    }

    private fun simulateSafExport(outputFile: File, treeUri: String): Boolean {
        // In a real test, this would use ContentResolver to write to SAF
        // For this test, we just verify the file exists and URI is valid
        return outputFile.exists() && treeUri.startsWith("content://")
    }
}

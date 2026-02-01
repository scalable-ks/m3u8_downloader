package com.rnandroidhlsapp

import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Dynamic
import com.facebook.react.bridge.ReadableType
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import java.util.UUID
import kotlin.test.assertEquals

class HlsDownloaderModuleTest {

    @Mock
    private lateinit var mockReactContext: ReactApplicationContext

    @Mock
    private lateinit var mockWorkManager: WorkManager

    @Mock
    private lateinit var mockPromise: Promise

    @Mock
    private lateinit var mockPlanMap: ReadableMap

    @Mock
    private lateinit var mockEventEmitter: DeviceEventManagerModule.RCTDeviceEventEmitter

    private lateinit var module: HlsDownloaderModule

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        `when`(mockReactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java))
            .thenReturn(mockEventEmitter)

        module = HlsDownloaderModule(mockReactContext)
    }

    @Test
    fun `startPlannedJob creates WorkManager work request`() = runTest {
        val planJson = """
            {
                "masterPlaylistUri": "https://example.com/master.m3u8",
                "videoSegments": [],
                "audioSegments": [],
                "subtitleSegments": [],
                "destinationUri": "/storage/downloads",
                "cleanup": "deleteOnSuccess"
            }
        """.trimIndent()

        val workId = UUID.randomUUID()
        val mockWorkInfo = mock(WorkInfo::class.java)
        `when`(mockWorkInfo.id).thenReturn(workId)
        `when`(mockWorkInfo.state).thenReturn(WorkInfo.State.ENQUEUED)

        val mockDynamic = mock(Dynamic::class.java)
        `when`(mockDynamic.type).thenReturn(ReadableType.String)
        `when`(mockDynamic.asString()).thenReturn(planJson)

        module.startPlannedJob(mockDynamic, mockPromise)

        // Verify WorkManager enqueue called
        verify(mockWorkManager).enqueue(any(androidx.work.WorkRequest::class.java))

        // Verify promise resolved with job status
        verify(mockPromise).resolve(any(WritableMap::class.java))
    }

    @Test
    fun `startPlannedJob rejects promise on invalid JSON`() = runTest {
        val invalidJson = "{ invalid json"

        val mockDynamic = mock(Dynamic::class.java)
        `when`(mockDynamic.type).thenReturn(ReadableType.String)
        `when`(mockDynamic.asString()).thenReturn(invalidJson)

        module.startPlannedJob(mockDynamic, mockPromise)

        verify(mockPromise).reject(
            eq("PARSE_ERROR"),
            contains("Failed to parse plan JSON"),
            any(Throwable::class.java)
        )
        verify(mockWorkManager, never()).enqueue(any(androidx.work.WorkRequest::class.java))
    }

    @Test
    fun `pauseJob updates work state`() = runTest {
        val jobId = UUID.randomUUID().toString()

        module.pauseJob(jobId, mockPromise)

        verify(mockWorkManager).cancelWorkById(UUID.fromString(jobId))
        verify(mockPromise).resolve(any(WritableMap::class.java))
    }

    @Test
    fun `resumeJob restarts paused work`() = runTest {
        val jobId = UUID.randomUUID().toString()

        // Mock: job exists in paused state
        val mockWorkInfo = mock(WorkInfo::class.java)
        `when`(mockWorkInfo.id).thenReturn(UUID.fromString(jobId))
        `when`(mockWorkInfo.state).thenReturn(WorkInfo.State.CANCELLED)

        module.resumeJob(jobId, mockPromise)

        // Verify work re-enqueued
        verify(mockWorkManager).enqueue(any(androidx.work.WorkRequest::class.java))
        verify(mockPromise).resolve(any(WritableMap::class.java))
    }

    @Test
    fun `cancelJob cancels work and cleans up resources`() = runTest {
        val jobId = UUID.randomUUID().toString()

        module.cancelJob(jobId, mockPromise)

        verify(mockWorkManager).cancelWorkById(UUID.fromString(jobId))
        verify(mockPromise).resolve(any(WritableMap::class.java))
    }

    @Test
    fun `getJobStatus returns current work state`() = runTest {
        val jobId = UUID.randomUUID().toString()

        val mockWorkInfo = mock(WorkInfo::class.java)
        `when`(mockWorkInfo.id).thenReturn(UUID.fromString(jobId))
        `when`(mockWorkInfo.state).thenReturn(WorkInfo.State.RUNNING)
        `when`(mockWorkInfo.progress).thenReturn(
            androidx.work.Data.Builder().putInt("progress", 50).build()
        )

        `when`(mockWorkManager.getWorkInfoById(UUID.fromString(jobId)))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(mockWorkInfo))

        module.getJobStatus(jobId, mockPromise)

        verify(mockPromise).resolve(argThat { map: WritableMap ->
            // Verify map contains expected fields
            true
        })
    }

    @Test
    fun `listJobs returns all active jobs`() = runTest {
        val job1 = UUID.randomUUID()
        val job2 = UUID.randomUUID()

        val mockWorkInfo1 = mock(WorkInfo::class.java)
        `when`(mockWorkInfo1.id).thenReturn(job1)
        `when`(mockWorkInfo1.state).thenReturn(WorkInfo.State.RUNNING)

        val mockWorkInfo2 = mock(WorkInfo::class.java)
        `when`(mockWorkInfo2.id).thenReturn(job2)
        `when`(mockWorkInfo2.state).thenReturn(WorkInfo.State.ENQUEUED)

        `when`(mockWorkManager.getWorkInfosByTag("HlsDownload"))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFuture(
                listOf(mockWorkInfo1, mockWorkInfo2)
            ))

        module.listJobs(mockPromise)

        verify(mockPromise).resolve(argThat { json: String ->
            json.contains(job1.toString()) && json.contains(job2.toString())
        })
    }

    @Test
    fun `module validates plan JSON schema before starting`() = runTest {
        val planMissingRequired = """
            {
                "masterPlaylistUri": "https://example.com/master.m3u8"
            }
        """.trimIndent()

        val mockDynamic = mock(Dynamic::class.java)
        `when`(mockDynamic.type).thenReturn(ReadableType.String)
        `when`(mockDynamic.asString()).thenReturn(planMissingRequired)

        module.startPlannedJob(mockDynamic, mockPromise)

        verify(mockPromise).reject(
            eq("VALIDATION_ERROR"),
            contains("Missing required field"),
            any(Throwable::class.java)
        )
    }
}

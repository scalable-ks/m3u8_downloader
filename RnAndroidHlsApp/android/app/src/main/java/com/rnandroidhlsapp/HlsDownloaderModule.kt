package com.rnandroidhlsapp

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.rnandroidhlsapp.downloader.DownloadJobState
import com.rnandroidhlsapp.downloader.DownloadStateStore
import com.rnandroidhlsapp.downloader.FileDownloadStateStore
import com.rnandroidhlsapp.downloader.JobProgress
import com.rnandroidhlsapp.downloader.JobState
import com.rnandroidhlsapp.downloader.SegmentState
import com.rnandroidhlsapp.downloader.SegmentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class HlsDownloaderModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val stateStore: DownloadStateStore = FileDownloadStateStore(reactContext)
    private val planStore = PlanFileStore(reactContext)
    private val workManager = WorkManager.getInstance(reactContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = ConcurrentHashMap<String, Job>()

    init {
        // Clean up any old plan files that may have been left behind
        planStore.cleanupOldPlans()
    }

    override fun getName(): String = "HlsDownloaderModule"

    @ReactMethod
    fun startPlannedJob(planJson: String, promise: Promise) {
        try {
            Log.d("HlsDownloaderModule", "Received plan JSON, size: ${planJson.length} bytes")

            val parsed = try {
                HlsPlanParser.parse(reactContext, planJson)
            } catch (e: org.json.JSONException) {
                Log.e("HlsDownloaderModule", "JSON parsing failed", e)

                // Send to Sentry with context
                Sentry.withScope { scope ->
                    scope.setTag("error_type", "json_parse")
                    scope.setContexts("plan", mapOf(
                        "size_bytes" to planJson.length,
                        "preview" to planJson.take(200)
                    ))
                    Sentry.captureException(e)
                }

                throw Exception("Failed to parse download plan JSON: ${e.message}")
            }

            val jobId = parsed.request.id
            Log.d("HlsDownloaderModule", "Plan parsed successfully for job: $jobId")

            val queued = buildQueuedState(parsed)
            stateStore.save(queued)
            enqueueWork(jobId, planJson, parsed.request.constraints)
            startPolling(jobId)
            promise.resolve(buildStatus(jobId, JobState.QUEUED))
        } catch (e: Exception) {
            Log.e("HlsDownloaderModule", "Failed to start planned job", e)

            // Send to Sentry
            Sentry.withScope { scope ->
                scope.setTag("operation", "start_planned_job")
                scope.setLevel(SentryLevel.ERROR)
                Sentry.captureException(e)
            }

            promise.reject("START_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun pauseJob(jobId: String, promise: Promise) {
        workManager.cancelUniqueWork(workName(jobId))
        val state = stateStore.get(jobId)
        if (state != null) {
            stateStore.save(
                state.copy(
                    state = JobState.PAUSED,
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = null,
                    lastErrorMessage = null,
                    lastErrorDetail = null,
                ),
            )
        }
        startPolling(jobId)
        promise.resolve(buildStatus(jobId, JobState.PAUSED))
    }

    @ReactMethod
    fun resumeJob(jobId: String, promise: Promise) {
        val stored = stateStore.get(jobId)
        if (stored == null || stored.playlistMetadata == null) {
            promise.resolve(buildStatus(jobId, JobState.FAILED))
            return
        }
        val parsed = HlsPlanParser.parse(reactContext, stored.playlistMetadata)
        val queued = buildQueuedState(parsed)
        stateStore.save(queued)
        enqueueWork(jobId, stored.playlistMetadata, parsed.request.constraints)
        startPolling(jobId)
        promise.resolve(buildStatus(jobId, JobState.QUEUED))
    }

    @ReactMethod
    fun cancelJob(jobId: String, promise: Promise) {
        workManager.cancelUniqueWork(workName(jobId))
        val state = stateStore.get(jobId)
        if (state != null) {
            stateStore.save(
                state.copy(
                    state = JobState.CANCELED,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        startPolling(jobId)
        promise.resolve(buildStatus(jobId, JobState.CANCELED))
    }

    @ReactMethod
    fun getJobStatus(jobId: String, promise: Promise) {
        val state = stateStore.get(jobId)
        if (state == null) {
            promise.resolve(buildStatus(jobId, JobState.FAILED))
            return
        }
        val progress = computeProgress(state)
        val status = JSONObject()
            .put("id", jobId)
            .put("state", state.state.name.lowercase())
            .put("progress", progress)
        promise.resolve(status)
    }

    @ReactMethod
    fun listJobs(promise: Promise) {
        val list = JSONArray()
        stateStore.list().forEach { state ->
            list.put(buildJobSnapshot(state))
            startPolling(state.id)
        }
        promise.resolve(list.toString())
    }

    private fun sendEvent(name: String, payload: JSONObject) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, payload.toString())
    }

    private fun buildStatus(jobId: String, state: JobState): JSONObject {
        val stored = stateStore.get(jobId)
        val progress = stored?.let { computeProgress(it) } ?: progressToJson(JobProgress(0, null, 0, 0))
        return JSONObject()
            .put("id", jobId)
            .put("state", state.name.lowercase())
            .put("progress", progress)
    }

    private fun buildJobSnapshot(state: DownloadJobState): JSONObject {
        return JSONObject()
            .put("id", state.id)
            .put("state", state.state.name.lowercase())
            .put("progress", computeProgress(state))
            .put("masterPlaylistUri", state.playlistUri)
            .put("createdAt", state.createdAt)
    }

    private fun enqueueWork(
        jobId: String,
        planJson: String,
        constraints: com.rnandroidhlsapp.downloader.JobConstraints,
    ) {
        // Save plan to file to avoid WorkManager's 10KB input data limit
        if (!planStore.save(jobId, planJson)) {
            Log.e("HlsDownloaderModule", "Failed to save plan for job $jobId")
            throw Exception("Failed to save download plan")
        }

        val request =
            OneTimeWorkRequestBuilder<HlsDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(HlsDownloadWorker.KEY_JOB_ID, jobId)
                        .build(),
                )
                .setConstraints(buildConstraints(constraints))
                .addTag(workTag(jobId))
                .build()
        workManager.enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun buildConstraints(constraints: com.rnandroidhlsapp.downloader.JobConstraints): Constraints {
        val builder = Constraints.Builder()
        if (constraints.requiresUnmetered) {
            builder.setRequiredNetworkType(NetworkType.UNMETERED)
        }
        if (constraints.requiresCharging) {
            builder.setRequiresCharging(true)
        }
        if (constraints.requiresIdle) {
            builder.setRequiresDeviceIdle(true)
        }
        if (constraints.requiresStorageNotLow) {
            builder.setRequiresStorageNotLow(true)
        }
        return builder.build()
    }

    private fun workName(jobId: String): String = "download_$jobId"

    private fun workTag(jobId: String): String = "download_tag_$jobId"

    private fun startPolling(jobId: String) {
        if (pollers.containsKey(jobId)) {
            return
        }
        val job =
            scope.launch {
                var lastErrorCode: String? = null
                while (isActive) {
                    val state = stateStore.get(jobId) ?: break
                    emitProgress(jobId, state)
                    if (state.state == JobState.FAILED && state.lastErrorCode != null) {
                        if (state.lastErrorCode != lastErrorCode) {
                            emitError(jobId, state.lastErrorCode, state.lastErrorMessage, state.lastErrorDetail)
                            lastErrorCode = state.lastErrorCode
                        }
                    }
                    if (
                        state.state == JobState.COMPLETED ||
                        state.state == JobState.FAILED ||
                        state.state == JobState.CANCELED
                    ) {
                        break
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        pollers[jobId] = job
        job.invokeOnCompletion { pollers.remove(jobId) }
    }

    private fun emitProgress(
        jobId: String,
        state: DownloadJobState,
    ) {
        val payload =
            JSONObject()
                .put("id", jobId)
                .put("state", state.state.name.lowercase())
                .put("progress", computeProgress(state))
        sendEvent("downloadProgress", payload)
    }

    private fun emitError(
        jobId: String,
        code: String?,
        message: String?,
        detail: String?,
    ) {
        val payload =
            JSONObject()
                .put("id", jobId)
                .put("code", code ?: "unknown")
                .put("message", message ?: "Download failed")
                .put("detail", detail)
        sendEvent("downloadError", payload)
    }

    private fun buildQueuedState(parsed: ParsedPlan): DownloadJobState {
        val now = System.currentTimeMillis()
        val segments =
            parsed.segments.map {
                SegmentState(
                    uri = it.uri,
                    sequence = it.sequence,
                    fileKey = it.fileKey,
                    status = SegmentStatus.PENDING,
                    bytesDownloaded = 0,
                )
            }
        return DownloadJobState(
            id = parsed.request.id,
            playlistUri = parsed.request.playlistUri,
            playlistMetadata = parsed.request.playlistMetadata,
            state = JobState.QUEUED,
            segments = segments,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun computeProgress(state: DownloadJobState): JSONObject {
        val totalSegments = state.segments.size
        val completed = state.segments.count { it.status == SegmentStatus.COMPLETED }
        val bytes = state.segments.sumOf { it.bytesDownloaded }
        val totalBytes = state.segments.mapNotNull { it.totalBytes }.sum().takeIf { it > 0 }
        return progressToJson(
            JobProgress(
                bytesDownloaded = bytes,
                totalBytes = totalBytes,
                segmentsDownloaded = completed,
                totalSegments = totalSegments,
            ),
        )
    }

    private fun progressToJson(progress: JobProgress): JSONObject {
        return JSONObject()
            .put("bytesDownloaded", progress.bytesDownloaded)
            .put("totalBytes", progress.totalBytes)
            .put("segmentsDownloaded", progress.segmentsDownloaded)
            .put("totalSegments", progress.totalSegments)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}

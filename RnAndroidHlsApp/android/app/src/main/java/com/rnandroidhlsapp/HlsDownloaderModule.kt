package com.rnandroidhlsapp

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.rnandroidhlsapp.downloader.DownloadRequest
import com.rnandroidhlsapp.downloader.DownloadStateStore
import com.rnandroidhlsapp.downloader.ErrorListener
import com.rnandroidhlsapp.downloader.FileDownloadStateStore
import com.rnandroidhlsapp.downloader.JobDownloader
import com.rnandroidhlsapp.downloader.JobProgress
import com.rnandroidhlsapp.downloader.JobState
import com.rnandroidhlsapp.downloader.ProgressListener
import com.rnandroidhlsapp.downloader.RetryPolicy
import com.rnandroidhlsapp.downloader.Segment
import com.rnandroidhlsapp.downloader.SegmentKey
import com.rnandroidhlsapp.downloader.StorageLocator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HlsDownloaderModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val stateStore: DownloadStateStore = FileDownloadStateStore(reactContext)
    private val jobs = ConcurrentHashMap<String, JobContext>()

    override fun getName(): String = "HlsDownloaderModule"

    @ReactMethod
    fun startPlannedJob(planJson: String, promise: Promise) {
        try {
            val plan = JSONObject(planJson)
            val jobId = plan.getString("id")
            val headers = readHeaders(plan.optJSONObject("headers"))
            val exportTreeUri = plan.optString("exportTreeUri", null)
            val outputDir = File(StorageLocator.tempDir(reactContext), "job_$jobId").apply { mkdirs() }

            val segments = mutableListOf<Segment>()
            segments.addAll(readSegments(plan.getJSONObject("video").getJSONArray("segments")))
            plan.optJSONObject("audio")?.optJSONArray("segments")?.let { audioArray ->
                segments.addAll(readSegments(audioArray))
            }

            val downloader =
                JobDownloader(
                    stateStore = stateStore,
                    progressListener = buildProgressListener(),
                    errorListener = buildErrorListener(),
                    retryPolicy = RetryPolicy.default(),
                )
            val request =
                DownloadRequest(
                    id = jobId,
                    playlistUri = plan.getString("masterPlaylistUri"),
                    outputDir = outputDir,
                    headers = headers,
                    playlistMetadata = plan.toString(),
                    exportTreeUri = exportTreeUri,
                )

            jobs[jobId] = JobContext(request, segments, downloader)
            downloader.start(request, segments)
            promise.resolve(buildStatus(jobId, JobState.RUNNING))
        } catch (e: Exception) {
            promise.reject("START_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun pauseJob(jobId: String, promise: Promise) {
        val context = jobs[jobId]
        if (context == null) {
            promise.resolve(buildStatus(jobId, JobState.FAILED))
            return
        }
        context.downloader?.cancel(jobId, context.request.outputDir, context.request.cleanupPolicy.copy(deleteOnCancel = false))
        stateStore.get(jobId)?.let { stateStore.save(it.copy(state = JobState.PAUSED)) }
        promise.resolve(buildStatus(jobId, JobState.PAUSED))
    }

    @ReactMethod
    fun resumeJob(jobId: String, promise: Promise) {
        val context = jobs[jobId]
        if (context == null) {
            promise.resolve(buildStatus(jobId, JobState.FAILED))
            return
        }
        val downloader =
            JobDownloader(
                stateStore = stateStore,
                progressListener = buildProgressListener(),
                errorListener = buildErrorListener(),
                retryPolicy = RetryPolicy.default(),
            )
        context.downloader = downloader
        downloader.start(context.request, context.segments)
        promise.resolve(buildStatus(jobId, JobState.RUNNING))
    }

    @ReactMethod
    fun cancelJob(jobId: String, promise: Promise) {
        val context = jobs[jobId]
        if (context != null) {
            context.downloader?.cancel(jobId, context.request.outputDir, context.request.cleanupPolicy)
        }
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

    private fun buildProgressListener(): ProgressListener {
        return object : ProgressListener {
            override fun onProgress(jobId: String, progress: JobProgress) {
                val payload =
                    JSONObject()
                        .put("id", jobId)
                        .put("state", JobState.RUNNING.name.lowercase())
                        .put("progress", progressToJson(progress))
                sendEvent("downloadProgress", payload)
            }
        }
    }

    private fun buildErrorListener(): ErrorListener {
        return object : ErrorListener {
            override fun onError(jobId: String, code: String, message: String, detail: String?) {
                val payload =
                    JSONObject()
                        .put("id", jobId)
                        .put("code", code)
                        .put("message", message)
                        .put("detail", detail)
                sendEvent("downloadError", payload)
            }
        }
    }

    private fun sendEvent(name: String, payload: JSONObject) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, payload.toString())
    }

    private fun readHeaders(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.getString(key)
        }
        return result
    }

    private fun readSegments(array: JSONArray): List<Segment> {
        val output = mutableListOf<Segment>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val keyJson = item.optJSONObject("key")
            val key =
                if (keyJson != null) {
                    SegmentKey(
                        method = keyJson.optString("method"),
                        uri = keyJson.optString("uri", null),
                        iv = keyJson.optString("iv", null),
                    )
                } else {
                    null
                }
            output.add(
                Segment(
                    uri = item.getString("uri"),
                    duration = item.getDouble("duration"),
                    sequence = item.getLong("sequence"),
                    key = key,
                ),
            )
        }
        return output
    }

    private fun buildStatus(jobId: String, state: JobState): JSONObject {
        val stored = stateStore.get(jobId)
        val progress = stored?.let { computeProgress(it) } ?: progressToJson(JobProgress(0, null, 0, 0))
        return JSONObject()
            .put("id", jobId)
            .put("state", state.name.lowercase())
            .put("progress", progress)
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

    private data class JobContext(
        val request: DownloadRequest,
        val segments: List<Segment>,
        var downloader: JobDownloader? = null,
    )
}

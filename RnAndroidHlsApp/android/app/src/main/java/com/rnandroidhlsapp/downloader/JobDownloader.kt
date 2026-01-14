package com.rnandroidhlsapp.downloader

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class DownloadResult {
    object Success : DownloadResult()
    object Failure : DownloadResult()
    object Cancelled : DownloadResult()
}

class JobDownloader(
    private val stateStore: DownloadStateStore,
    private val progressListener: ProgressListener,
    private val errorListener: ErrorListener,
    private val completionListener: CompletionListener? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy.default(),
    private val maxParallel: Int = 3,
    private val segmentDownloader: SegmentDownloader? = null,
    private val maxFailures: Int = 5,
    private val constraintChecker: ConstraintChecker = NoopConstraintChecker,
    private val parentScope: CoroutineScope? = null,
) {
    private var jobScope: CoroutineScope? = null
    private val isCanceled = AtomicBoolean(false)
    private val failureCount = AtomicInteger(0)

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    fun cancel(
        jobId: String,
        outputDir: File,
        cleanupPolicy: CleanupPolicy = CleanupPolicy(),
    ) {
        isCanceled.set(true)
        cleanup()
        if (cleanupPolicy.deleteOnCancel) {
            cleanupFiles(outputDir, deleteCompleted = false)
        }
        val state = stateStore.get(jobId)
        if (state != null) {
            stateStore.save(state.copy(state = JobState.CANCELED, updatedAt = System.currentTimeMillis()))
        }
        completionListener?.onComplete(jobId, JobState.CANCELED)
    }

    private fun cleanup() {
        jobScope?.cancel()
        jobScope = null
    }

    suspend fun start(
        request: DownloadRequest,
        segments: List<Segment>,
    ): DownloadResult = withContext(Dispatchers.IO) {
        // Create child scope for this job
        jobScope = if (parentScope != null) {
            CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        } else {
            // Fallback for backward compatibility
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        val constraintResult = constraintChecker.check(request.constraints, request)
        if (!constraintResult.allowed) {
            val failedState =
                DownloadJobState(
                    id = request.id,
                    playlistUri = request.playlistUri,
                    playlistMetadata = request.playlistMetadata,
                    state = JobState.FAILED,
                    segments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = "constraints",
                    lastErrorMessage = constraintResult.reason,
                )
            stateStore.save(failedState)
            errorListener.onError(
                request.id,
                "constraints",
                constraintResult.reason ?: "Constraints not met",
            )
            completionListener?.onComplete(request.id, JobState.FAILED)
            cleanup()
            return@withContext DownloadResult.Failure
        }
        if (!ensureDiskSpace(request)) {
            val failedState =
                DownloadJobState(
                    id = request.id,
                    playlistUri = request.playlistUri,
                    playlistMetadata = request.playlistMetadata,
                    state = JobState.FAILED,
                    segments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = "storage",
                    lastErrorMessage = "Insufficient disk space",
                )
            stateStore.save(failedState)
            errorListener.onError(request.id, "storage", "Insufficient disk space")
            completionListener?.onComplete(request.id, JobState.FAILED)
            cleanup()
            return@withContext DownloadResult.Failure
        }

        val existingState = stateStore.get(request.id)
        val initialState =
            buildInitialState(request, segments, existingState)
        stateStore.save(initialState)
        val semaphore = Semaphore(maxParallel)
        val downloader = segmentDownloader ?: SegmentDownloader(httpClient)
        val mapCache = HashSet<String>()

        val jobs =
            segments.filterNot { isSegmentComplete(it, request.outputDir) }.map { segment ->
                jobScope!!.launch {
                    semaphore.withPermit {
                        if (isCanceled.get()) {
                            return@withPermit
                        }
                        if (!ensureMapDownloaded(segment, request.id, request.outputDir, request.headers, downloader, mapCache)) {
                            return@withPermit
                        }
                        val partialFile = File(request.outputDir, "segment_${segment.fileKey}.partial")
                        val finalFile = File(request.outputDir, "segment_${segment.fileKey}.bin")
                        val byteRange = segment.byteRange
                        if (byteRange != null && resumeBytesFor(partialFile) >= byteRange.length) {
                            if (partialFile.exists()) {
                                partialFile.renameTo(finalFile)
                            }
                            val finalBytes = finalFile.takeIf { it.exists() }?.length() ?: byteRange.length
                            stateStore.updateSegment(
                                request.id,
                                SegmentState(
                                    uri = segment.uri,
                                    sequence = segment.sequence,
                                    fileKey = segment.fileKey,
                                    status = SegmentStatus.COMPLETED,
                                    bytesDownloaded = finalBytes,
                                    totalBytes = finalBytes,
                                ),
                            )
                            updateJobStateIfDone(request.id)
                            return@withPermit
                        }
                        if (finalFile.exists()) {
                            stateStore.updateSegment(
                                request.id,
                                SegmentState(
                                    uri = segment.uri,
                                    sequence = segment.sequence,
                                    fileKey = segment.fileKey,
                                    status = SegmentStatus.COMPLETED,
                                    bytesDownloaded = finalFile.length(),
                                    totalBytes = finalFile.length(),
                                ),
                            )
                            updateJobStateIfDone(request.id)
                            return@withPermit
                        }
                        var resumeBytes = resumeBytesFor(partialFile)
                        var attempt = 0
                        var completed = false
                        while (!completed && !isCanceled.get()) {
                            attempt += 1
                            try {
                                stateStore.updateSegment(
                                    request.id,
                                    SegmentState(
                                        uri = segment.uri,
                                        sequence = segment.sequence,
                                        fileKey = segment.fileKey,
                                        status = SegmentStatus.DOWNLOADING,
                                        bytesDownloaded = resumeBytes,
                                    ),
                                )
                                val total =
                                    downloader.downloadSegment(
                                        segment,
                                        partialFile,
                                        request.headers,
                                        resumeBytes,
                                    )
                                resumeBytes = total
                                if (partialFile.exists()) {
                                    partialFile.renameTo(finalFile)
                                }
                                stateStore.updateSegment(
                                    request.id,
                                    SegmentState(
                                        uri = segment.uri,
                                        sequence = segment.sequence,
                                        fileKey = segment.fileKey,
                                        status = SegmentStatus.COMPLETED,
                                        bytesDownloaded = total,
                                        totalBytes = total,
                                    ),
                                )
                                updateJobStateIfDone(request.id)
                                completed = true
                                publishProgress(request.id)
                            } catch (e: Exception) {
                                Log.w(
                                    "JobDownloader",
                                    "Segment download failed (attempt $attempt): ${segment.uri}",
                                    e,
                                )
                                if (!retryPolicy.shouldRetry(attempt)) {
                                    stateStore.updateSegment(
                                        request.id,
                                        SegmentState(
                                            uri = segment.uri,
                                            sequence = segment.sequence,
                                            fileKey = segment.fileKey,
                                            status = SegmentStatus.FAILED,
                                            bytesDownloaded = resumeBytes,
                                        ),
                                    )
                                    val failures = failureCount.incrementAndGet()
                                    if (failures >= maxFailures) {
                                        Log.e("JobDownloader", "Failure budget exceeded for job ${request.id}")
                                        stateStore.save(
                                            stateStore.get(request.id)?.copy(
                                                state = JobState.FAILED,
                                                updatedAt = System.currentTimeMillis(),
                                                lastErrorCode = "network",
                                                lastErrorMessage = "Failure budget exceeded",
                                            ) ?: return@withPermit,
                                        )
                                        errorListener.onError(request.id, "network", "Failure budget exceeded")
                                        stopDownloadsOnFailure(request)
                                        return@withPermit
                                    }
                                    updateJobStateIfDone(request.id)
                                    errorListener.onError(request.id, "network", e.message ?: "download failed")
                                    stopDownloadsOnFailure(request)
                                    return@withPermit
                                }
                                delay(retryPolicy.nextDelayMs(attempt))
                            }
                        }
                    }
                }
            }

        // Launch progress monitoring as separate job
        val progressJob =
            jobScope!!.launch {
                while (isActive && !isCanceled.get()) {
                    publishProgress(request.id)
                    delay(1_000)
                    updateJobStateIfDone(request.id)
                    val state = stateStore.get(request.id) ?: return@launch
                    if (state.state == JobState.COMPLETED || state.state == JobState.FAILED) {
                        return@launch
                    }
                }
            }

        // Await all downloads and handle result
        return@withContext try {
            jobs.joinAll()
            progressJob.cancel()
            updateJobStateIfDone(request.id)
            val finalState = stateStore.get(request.id)

            if (finalState != null) {
                completionListener?.onComplete(request.id, finalState.state)
            }

            if (finalState?.state == JobState.COMPLETED) {
                if (request.cleanupPolicy.deleteOnSuccess) {
                    cleanupFiles(request.outputDir, deleteCompleted = true)
                }
                cleanup()
                DownloadResult.Success
            } else {
                cleanup()
                DownloadResult.Failure
            }
        } catch (e: CancellationException) {
            Log.i("JobDownloader", "Download cancelled for job ${request.id}")
            progressJob.cancel()
            cleanup()
            DownloadResult.Cancelled
        } catch (e: Exception) {
            Log.e("JobDownloader", "Download failed for job ${request.id}", e)
            progressJob.cancel()
            cleanup()
            DownloadResult.Failure
        }
    }

    private fun publishProgress(jobId: String) {
        val state = stateStore.get(jobId) ?: return
        val totalSegments = state.segments.size
        val segmentsDownloaded = state.segments.count { it.status == SegmentStatus.COMPLETED }
        val bytesDownloaded = state.segments.sumOf { it.bytesDownloaded }
        val totalBytes = state.segments.mapNotNull { it.totalBytes }.takeIf { it.isNotEmpty() }?.sum()
        progressListener.onProgress(
            jobId,
            JobProgress(
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                segmentsDownloaded = segmentsDownloaded,
                totalSegments = totalSegments,
            ),
        )
    }

    private fun updateJobStateIfDone(jobId: String) {
        val state = stateStore.get(jobId) ?: return
        if (state.segments.all { it.status == SegmentStatus.COMPLETED }) {
            stateStore.save(state.copy(state = JobState.COMPLETED, updatedAt = System.currentTimeMillis()))
            return
        }
        if (state.segments.any { it.status == SegmentStatus.FAILED }) {
            stateStore.save(state.copy(state = JobState.FAILED, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun buildInitialState(
        request: DownloadRequest,
        segments: List<Segment>,
        existingState: DownloadJobState?,
    ): DownloadJobState {
        val createdAt = existingState?.createdAt ?: System.currentTimeMillis()
        val updatedAt = System.currentTimeMillis()
        val existingBySequence = existingState?.segments?.associateBy { it.fileKey }.orEmpty()
        val updatedSegments =
            segments.map { segment ->
                val existing = existingBySequence[segment.fileKey]
                if (existing != null && existing.status == SegmentStatus.COMPLETED) {
                    existing
                } else {
                    SegmentState(
                        uri = segment.uri,
                        sequence = segment.sequence,
                        fileKey = segment.fileKey,
                        status = SegmentStatus.PENDING,
                        bytesDownloaded = 0,
                    )
                }
            }
        return DownloadJobState(
            id = request.id,
            playlistUri = request.playlistUri,
            playlistMetadata = request.playlistMetadata,
            state = JobState.RUNNING,
            segments = updatedSegments,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ensureDiskSpace(request: DownloadRequest): Boolean {
        val usableSpace = request.outputDir.usableSpace

        // Android system reserve: 10% or 500MB, whichever is larger
        val systemReserve = maxOf(usableSpace / 10, 500L * 1024 * 1024)
        val availableForDownload = usableSpace - systemReserve

        if (request.requiredBytes != null) {
            // Account for assembly overhead (estimate 2x for MP4 + concat files)
            val totalRequired = request.requiredBytes * 2
            if (availableForDownload < totalRequired) {
                Log.w(
                    "JobDownloader",
                    "Insufficient disk space: required=${totalRequired}, available=${availableForDownload}",
                )
                return false
            }
        } else {
            // For live streams or unknown size, require minimum 1GB free
            val minimumRequired = 1024L * 1024 * 1024 // 1GB
            if (availableForDownload < minimumRequired) {
                Log.w(
                    "JobDownloader",
                    "Insufficient disk space for unknown size: available=${availableForDownload}, minimum=${minimumRequired}",
                )
                return false
            }
        }

        return true
    }

    private fun isSegmentComplete(
        segment: Segment,
        outputDir: File,
    ): Boolean {
        val finalFile = File(outputDir, "segment_${segment.fileKey}.bin")
        return finalFile.exists()
    }

    private fun cleanupFiles(
        outputDir: File,
        deleteCompleted: Boolean,
    ) {
        val files = outputDir.listFiles() ?: return
        files.filter { it.name.endsWith(".partial") }.forEach { it.delete() }
        if (deleteCompleted) {
            files.filter { it.name.startsWith("segment_") && it.name.endsWith(".bin") }
                .forEach { it.delete() }
            files.filter { it.name.startsWith("map_") && it.name.endsWith(".bin") }
                .forEach { it.delete() }
        }
    }

    private fun stopDownloadsOnFailure(request: DownloadRequest) {
        isCanceled.set(true)
        if (request.cleanupPolicy.deleteOnFailure) {
            cleanupFiles(request.outputDir, deleteCompleted = true)
        }
    }

    private fun resumeBytesFor(partialFile: File): Long {
        return partialFile.takeIf { it.exists() }?.length() ?: 0
    }

    private fun ensureMapDownloaded(
        segment: Segment,
        jobId: String,
        outputDir: File,
        headers: Map<String, String>,
        downloader: SegmentDownloader,
        cache: MutableSet<String>,
    ): Boolean {
        val map = segment.map ?: return true
        val key = map.fileKey
        synchronized(cache) {
            if (cache.contains(key)) {
                return true
            }
            cache.add(key)
        }
        val partialFile = File(outputDir, "map_${map.fileKey}.partial")
        val finalFile = File(outputDir, "map_${map.fileKey}.bin")
        if (finalFile.exists()) {
            return true
        }
        return try {
            val mapSegment =
                Segment(
                    uri = map.uri,
                    duration = 0.0,
                    sequence = -1,
                    fileKey = "map_${map.fileKey}",
                    byteRange = map.byteRange,
                )
            downloader.downloadSegment(mapSegment, partialFile, headers, 0)
            if (partialFile.exists()) {
                partialFile.renameTo(finalFile)
            }
            true
        } catch (e: Exception) {
            Log.e("JobDownloader", "Failed to download init segment: ${map.uri}", e)
            errorListener.onError(jobId, "network", "Failed to download init segment", e.message)
            false
        }
    }
}

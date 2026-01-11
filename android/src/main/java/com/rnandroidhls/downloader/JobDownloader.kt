package com.rnandroidhls.downloader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JobDownloader(
    private val stateStore: DownloadStateStore,
    private val progressListener: ProgressListener,
    private val errorListener: ErrorListener,
    private val retryPolicy: RetryPolicy = RetryPolicy.default(),
    private val maxParallel: Int = 3,
    private val segmentDownloader: SegmentDownloader? = null,
    private val maxFailures: Int = 5,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    ) {
        isCanceled.set(true)
        cleanupPartialFiles(outputDir)
        val state = stateStore.get(jobId)
        if (state != null) {
            stateStore.save(state.copy(state = JobState.CANCELED, updatedAt = System.currentTimeMillis()))
        }
    }

    fun start(
        request: DownloadRequest,
        segments: List<Segment>,
    ) {
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
                )
            stateStore.save(failedState)
            errorListener.onError(request.id, "storage", "Insufficient disk space")
            return
        }

        val existingState = stateStore.get(request.id)
        val initialState =
            buildInitialState(request, segments, existingState)
        stateStore.save(initialState)
        val semaphore = Semaphore(maxParallel)
        val downloader = segmentDownloader ?: SegmentDownloader(httpClient)

        val jobs =
            segments.filterNot { isSegmentComplete(it, request.outputDir) }.map { segment ->
                scope.launch {
                    semaphore.withPermit {
                        if (isCanceled.get()) {
                            return@withPermit
                        }
                        val partialFile = File(request.outputDir, "segment_${segment.sequence}.partial")
                        val finalFile = File(request.outputDir, "segment_${segment.sequence}.bin")
                        if (finalFile.exists()) {
                            stateStore.updateSegment(
                                request.id,
                                SegmentState(
                                    uri = segment.uri,
                                    sequence = segment.sequence,
                                    status = SegmentStatus.COMPLETED,
                                    bytesDownloaded = finalFile.length(),
                                    totalBytes = finalFile.length(),
                                ),
                            )
                            updateJobStateIfDone(request.id)
                            return@withPermit
                        }
                        var resumeBytes = partialFile.takeIf { it.exists() }?.length() ?: 0
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
                                        status = SegmentStatus.COMPLETED,
                                        bytesDownloaded = total,
                                        totalBytes = total,
                                    ),
                                )
                                updateJobStateIfDone(request.id)
                                completed = true
                                publishProgress(request.id)
                            } catch (e: Exception) {
                                if (!retryPolicy.shouldRetry(attempt)) {
                                    stateStore.updateSegment(
                                        request.id,
                                        SegmentState(
                                            uri = segment.uri,
                                            sequence = segment.sequence,
                                            status = SegmentStatus.FAILED,
                                            bytesDownloaded = resumeBytes,
                                        ),
                                    )
                                    val failures = failureCount.incrementAndGet()
                                    if (failures >= maxFailures) {
                                        updateJobStateIfDone(request.id)
                                        errorListener.onError(
                                            request.id,
                                            "network",
                                            "Failure budget exceeded",
                                        )
                                        cancel(request.id, request.outputDir)
                                        return@withPermit
                                    }
                                    updateJobStateIfDone(request.id)
                                    errorListener.onError(request.id, "network", e.message ?: "download failed")
                                    return@withPermit
                                }
                                delay(retryPolicy.nextDelayMs(attempt))
                            }
                        }
                    }
                }
            }

        scope.launch {
            while (!isCanceled.get()) {
                publishProgress(request.id)
                delay(1_000)
                updateJobStateIfDone(request.id)
                val state = stateStore.get(request.id) ?: return@launch
                if (state.state == JobState.COMPLETED || state.state == JobState.FAILED) {
                    return@launch
                }
            }
        }

        scope.launch {
            jobs.joinAll()
            updateJobStateIfDone(request.id)
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
        val existingBySequence = existingState?.segments?.associateBy { it.sequence }.orEmpty()
        val updatedSegments =
            segments.map { segment ->
                val existing = existingBySequence[segment.sequence]
                if (existing != null && existing.status == SegmentStatus.COMPLETED) {
                    existing
                } else {
                    SegmentState(
                        uri = segment.uri,
                        sequence = segment.sequence,
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
        val required = request.requiredBytes ?: return true
        return request.outputDir.usableSpace > required
    }

    private fun isSegmentComplete(
        segment: Segment,
        outputDir: File,
    ): Boolean {
        val finalFile = File(outputDir, "segment_${segment.sequence}.bin")
        return finalFile.exists()
    }

    private fun cleanupPartialFiles(outputDir: File) {
        val files = outputDir.listFiles() ?: return
        files.filter { it.name.endsWith(".partial") }.forEach { it.delete() }
    }
}

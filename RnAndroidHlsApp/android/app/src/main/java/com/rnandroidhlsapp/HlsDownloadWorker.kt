package com.rnandroidhlsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rnandroidhlsapp.downloader.AndroidConstraintChecker
import com.rnandroidhlsapp.downloader.DownloadStateStore
import com.rnandroidhlsapp.downloader.ErrorListener
import com.rnandroidhlsapp.downloader.FileDownloadStateStore
import com.rnandroidhlsapp.downloader.JobDownloader
import com.rnandroidhlsapp.downloader.JobProgress
import com.rnandroidhlsapp.downloader.JobState
import com.rnandroidhlsapp.downloader.ProgressListener
import com.rnandroidhlsapp.downloader.RetryPolicy
import com.rnandroidhlsapp.muxing.ConcatListWriter
import com.rnandroidhlsapp.muxing.FfmpegKitRunner
import com.rnandroidhlsapp.muxing.Mp4Assembler
import com.rnandroidhlsapp.muxing.MuxRequest
import com.rnandroidhlsapp.muxing.SubtitleInput
import com.rnandroidhlsapp.muxing.SubtitleMerger
import com.rnandroidhlsapp.muxing.TrackInput
import kotlinx.coroutines.delay
import java.io.File

class HlsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val stateStore: DownloadStateStore = FileDownloadStateStore(appContext)

    override suspend fun doWork(): Result {
        val planJson = inputData.getString(KEY_PLAN_JSON) ?: return Result.failure()
        val parsed = HlsPlanParser.parse(applicationContext, planJson)
        val jobId = parsed.request.id
        setForeground(createForegroundInfo(jobId, 0, null))

        val progressListener =
            object : ProgressListener {
                override fun onProgress(
                    jobId: String,
                    progress: JobProgress,
                ) {
                    val total = progress.totalBytes ?: 0
                    val percent = if (total > 0) ((progress.bytesDownloaded * 100) / total).toInt() else 0
                    val info = createForegroundInfo(jobId, percent, progress)
                    setForegroundAsync(info)
                }
            }
        val errorListener =
            object : ErrorListener {
                override fun onError(
                    jobId: String,
                    code: String,
                    message: String,
                    detail: String?,
                ) {
                    val state = stateStore.get(jobId) ?: return
                    stateStore.save(
                        state.copy(
                            state = JobState.FAILED,
                            updatedAt = System.currentTimeMillis(),
                            lastErrorCode = code,
                            lastErrorMessage = message,
                            lastErrorDetail = detail,
                        ),
                    )
                }
            }

        val downloader =
            JobDownloader(
                stateStore = stateStore,
                progressListener = progressListener,
                errorListener = errorListener,
                retryPolicy = RetryPolicy.default(),
                constraintChecker = AndroidConstraintChecker(applicationContext),
            )
        downloader.start(parsed.request, parsed.segments)

        while (true) {
            if (isStopped) {
                downloader.cancel(jobId, parsed.request.outputDir, parsed.request.cleanupPolicy)
                return Result.failure()
            }
            val state = stateStore.get(jobId) ?: return Result.failure()
            if (state.state == JobState.COMPLETED || state.state == JobState.FAILED) {
                break
            }
            delay(POLL_INTERVAL_MS)
        }

        val finalState = stateStore.get(jobId)
        if (finalState?.state != JobState.COMPLETED) {
            return Result.failure()
        }

        return if (assembleAndExport(parsed)) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private fun assembleAndExport(parsed: ParsedPlan): Boolean {
        if (parsed.videoSegments.isEmpty()) {
            val state = stateStore.get(parsed.request.id) ?: return false
            stateStore.save(
                state.copy(
                    state = JobState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = "validation",
                    lastErrorMessage = "No video segments to assemble",
                ),
            )
            return false
        }
        val outputDir = parsed.request.outputDir
        val videoConcat = buildConcatList(outputDir, "video", parsed.videoSegments)
        val audioConcat =
            if (parsed.audioSegments.isNotEmpty()) {
                buildConcatList(outputDir, "audio", parsed.audioSegments)
            } else {
                null
            }
        val subtitleInput =
            if (parsed.subtitleSegments.isNotEmpty()) {
                val subtitleFile = mergeSubtitleSegments(outputDir, parsed.subtitleSegments, parsed.request.id)
                val language = parsed.subtitleLanguage ?: "und"
                SubtitleInput(subtitleFile.absolutePath, language)
            } else {
                null
            }
        val outputFile = File(outputDir, "output_${parsed.request.id}.mp4")
        val result =
            Mp4Assembler(FfmpegKitRunner()).assemble(
                MuxRequest(
                    video = TrackInput(videoConcat.absolutePath, isConcatList = true),
                    audio =
                        audioConcat?.let {
                            TrackInput(it.absolutePath, isConcatList = true)
                        },
                    subtitles = subtitleInput,
                    outputPath = outputFile.absolutePath,
                ),
            )
        if (!result.success) {
            val state = stateStore.get(parsed.request.id) ?: return false
            stateStore.save(
                state.copy(
                    state = JobState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = "ffmpeg",
                    lastErrorMessage = "Assembly failed",
                    lastErrorDetail = result.output,
                ),
            )
            return false
        }
        val exportUri = parsed.exportTreeUri
        if (exportUri.isNullOrBlank()) {
            return true
        }
        val exported = exportToSaf(exportUri, outputFile)
        if (!exported) {
            val state = stateStore.get(parsed.request.id) ?: return false
            stateStore.save(
                state.copy(
                    state = JobState.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    lastErrorCode = "storage",
                    lastErrorMessage = "Export failed",
                ),
            )
        }
        return exported
    }

    private fun buildConcatList(
        outputDir: File,
        trackId: String,
        segments: List<com.rnandroidhlsapp.downloader.Segment>,
    ): File {
        val listFile = File(outputDir, "concat_${trackId}.txt")
        val segmentFiles = segments.map { File(outputDir, "segment_${it.fileKey}.bin") }
        val initFile =
            segments.firstOrNull { it.map != null }?.map?.let {
                File(outputDir, "map_${it.fileKey}.bin")
            }
        return ConcatListWriter.write(listFile, segmentFiles, initFile)
    }

    private fun mergeSubtitleSegments(
        outputDir: File,
        segments: List<com.rnandroidhlsapp.downloader.Segment>,
        jobId: String,
    ): File {
        val subtitleFiles = segments.map { File(outputDir, "segment_${it.fileKey}.bin") }
        val output = File(outputDir, "subtitles_${jobId}.srt")
        return SubtitleMerger.mergeSrtSegments(subtitleFiles, output)
    }

    private fun exportToSaf(
        exportTreeUri: String,
        sourceFile: File,
    ): Boolean {
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
            applicationContext,
            android.net.Uri.parse(exportTreeUri),
        ) ?: return false
        val displayName = sourceFile.name.removeSuffix(".mp4")
        val target = tree.createFile("video/mp4", displayName) ?: return false
        return try {
            applicationContext.contentResolver.openOutputStream(target.uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createForegroundInfo(
        jobId: String,
        percent: Int,
        progress: JobProgress?,
    ): ForegroundInfo {
        ensureChannel()
        val content = if (progress != null) {
            val bytes = progress.bytesDownloaded
            val total = progress.totalBytes
            if (total != null && total > 0) {
                "Downloaded ${bytes}/${total} bytes"
            } else {
                "Downloaded ${bytes} bytes"
            }
        } else {
            "Preparing download"
        }
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Downloader95")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, progress == null)
                .build()
        return ForegroundInfo(NOTIFICATION_ID_BASE + jobId.hashCode(), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_PLAN_JSON = "plan_json"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID_BASE = 1000
        const val POLL_INTERVAL_MS = 1000L
    }
}

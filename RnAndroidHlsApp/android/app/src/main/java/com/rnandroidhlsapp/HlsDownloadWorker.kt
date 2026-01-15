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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.util.Log

class HlsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val stateStore: DownloadStateStore = FileDownloadStateStore(appContext)
    private val planStore = PlanFileStore(appContext)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()

        // Load plan from file (avoids WorkManager's 10KB input data limit)
        val planJson = planStore.load(jobId)
        if (planJson == null) {
            Log.e("HlsDownloadWorker", "Failed to load plan for job $jobId")
            return Result.failure()
        }

        val parsed = HlsPlanParser.parse(applicationContext, planJson)
        setForeground(createForegroundInfo(jobId, 0, null))

        val progressListener =
            object : ProgressListener {
                override fun onProgress(
                    jobId: String,
                    progress: JobProgress,
                ) {
                    val total = progress.totalBytes ?: 0
                    val percent = if (total > 0) {
                        (progress.bytesDownloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
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
                parentScope = null,
            )

        // Await completion directly - no polling!
        val downloadResult = downloader.start(parsed.request, parsed.segments)

        val result = when (downloadResult) {
            is com.rnandroidhlsapp.downloader.DownloadResult.Success -> {
                if (assembleAndExport(parsed)) {
                    Result.success()
                } else {
                    Result.failure()
                }
            }
            is com.rnandroidhlsapp.downloader.DownloadResult.Failure -> Result.failure()
            is com.rnandroidhlsapp.downloader.DownloadResult.Cancelled -> Result.failure()
        }

        // Clean up plan file after job completes (success or failure)
        planStore.delete(jobId)

        return result
    }

    private suspend fun assembleAndExport(parsed: ParsedPlan): Boolean {
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
        val exported = exportToSaf(exportUri, outputFile, parsed.request.id)
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

    private suspend fun exportToSaf(
        exportTreeUri: String,
        sourceFile: File,
        jobId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val tree =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(
                        applicationContext,
                        android.net.Uri.parse(exportTreeUri),
                    ) ?: return@withContext false

                val displayName = sourceFile.name.removeSuffix(".mp4")
                val target = tree.createFile("video/mp4", displayName) ?: return@withContext false

                val totalSize = sourceFile.length()
                var copiedBytes = 0L
                val buffer = ByteArray(16 * 1024) // 16KB buffer

                applicationContext.contentResolver.openOutputStream(target.uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                Log.w("ExportSAF", "Export cancelled")
                                return@withContext false
                            }
                            output.write(buffer, 0, bytesRead)
                            copiedBytes += bytesRead
                            // Progress reporting: export phase shows in notification
                            val exportPercent = if (totalSize > 0) {
                                (copiedBytes.toDouble() / totalSize * 100).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            setForegroundAsync(createForegroundInfo(jobId, exportPercent, null))
                        }
                    }
                } ?: return@withContext false

                Log.i("ExportSAF", "Export completed: ${sourceFile.name}")
                true
            } catch (e: IOException) {
                Log.e("ExportSAF", "I/O error during export: ${sourceFile.path}", e)
                false
            } catch (e: SecurityException) {
                Log.e("ExportSAF", "Permission error during export", e)
                false
            } catch (e: Exception) {
                Log.e("ExportSAF", "Unexpected error during export", e)
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
        const val KEY_JOB_ID = "job_id"
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID_BASE = 1000
    }
}

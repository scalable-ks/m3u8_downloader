package com.rnandroidhlsapp.downloader

import java.io.File

enum class JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
}

data class SegmentKey(
    val method: String,
    val uri: String?,
    val iv: String?,
)

data class SegmentMap(
    val uri: String,
    val byteRange: ByteRange? = null,
)

data class ByteRange(
    val length: Long,
    val offset: Long? = null,
)

data class Segment(
    val uri: String,
    val duration: Double,
    val sequence: Long,
    val byteRange: ByteRange? = null,
    val key: SegmentKey? = null,
    val map: SegmentMap? = null,
)

data class SegmentState(
    val uri: String,
    val sequence: Long,
    val status: SegmentStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
)

enum class SegmentStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

data class DownloadJobState(
    val id: String,
    val playlistUri: String,
    val playlistMetadata: String? = null,
    val state: JobState,
    val segments: List<SegmentState>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class JobProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val segmentsDownloaded: Int,
    val totalSegments: Int,
)

data class JobConstraints(
    val requiresUnmetered: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresIdle: Boolean = false,
    val requiresStorageNotLow: Boolean = false,
)

data class CleanupPolicy(
    val deleteOnFailure: Boolean = true,
    val deleteOnCancel: Boolean = true,
    val deleteOnSuccess: Boolean = false,
)

data class ConstraintResult(
    val allowed: Boolean,
    val reason: String? = null,
)

interface ConstraintChecker {
    fun check(
        constraints: JobConstraints,
        request: DownloadRequest,
    ): ConstraintResult
}

object NoopConstraintChecker : ConstraintChecker {
    override fun check(
        constraints: JobConstraints,
        request: DownloadRequest,
    ): ConstraintResult = ConstraintResult(allowed = true)
}

data class DownloadRequest(
    val id: String,
    val playlistUri: String,
    val outputDir: File,
    val headers: Map<String, String> = emptyMap(),
    val requiredBytes: Long? = null,
    val playlistMetadata: String? = null,
    val exportTreeUri: String? = null,
    val constraints: JobConstraints = JobConstraints(),
    val cleanupPolicy: CleanupPolicy = CleanupPolicy(),
)

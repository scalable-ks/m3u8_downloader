package com.rnandroidhlsapp.downloader

import android.util.Log
import java.io.File
import java.net.URI

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
    val fileKey: String,
)

data class ByteRange(
    val length: Long,
    val offset: Long? = null,
)

data class Segment(
    val uri: String,
    val duration: Double,
    val sequence: Long,
    val fileKey: String,
    val byteRange: ByteRange? = null,
    val key: SegmentKey? = null,
    val map: SegmentMap? = null,
)

/**
 * Validates that a segment has safe values for byte ranges, file keys, and URIs.
 *
 * @param segment The segment to validate
 * @return true if valid, false if validation fails
 */
fun validateSegment(segment: Segment): Boolean {
    // Validate byte range
    if (segment.byteRange != null) {
        val length = segment.byteRange.length
        val offset = segment.byteRange.offset

        if (length < 0) {
            Log.e("SegmentValidation", "Invalid byte range length: $length for segment ${segment.sequence}")
            return false
        }

        if (offset != null && offset < 0) {
            Log.e("SegmentValidation", "Invalid byte range offset: $offset for segment ${segment.sequence}")
            return false
        }
    }

    // Validate file key (prevent path traversal)
    if (segment.fileKey.contains("..") || segment.fileKey.startsWith("/")) {
        Log.e("SegmentValidation", "Invalid file key (path traversal attempt): ${segment.fileKey}")
        return false
    }

    // Validate URI
    try {
        val uri = URI(segment.uri)
        if (uri.scheme !in setOf("http", "https")) {
            Log.e("SegmentValidation", "Invalid URI scheme: ${uri.scheme} for segment ${segment.sequence}")
            return false
        }
        // Security warning for HTTP (not HTTPS)
        if (uri.scheme == "http") {
            Log.w(
                "SegmentValidation",
                "SECURITY WARNING: Using HTTP (not HTTPS) for segment ${segment.sequence}. " +
                    "HTTP connections are vulnerable to man-in-the-middle attacks and credential interception. " +
                    "URI: ${segment.uri}",
            )
        }
    } catch (e: Exception) {
        Log.e("SegmentValidation", "Malformed URI: ${segment.uri} for segment ${segment.sequence}", e)
        return false
    }

    // Validate map segment if present
    if (segment.map != null) {
        val map = segment.map

        // Validate map byte range
        if (map.byteRange != null) {
            val length = map.byteRange.length
            val offset = map.byteRange.offset

            if (length < 0) {
                Log.e("SegmentValidation", "Invalid map byte range length: $length")
                return false
            }

            if (offset != null && offset < 0) {
                Log.e("SegmentValidation", "Invalid map byte range offset: $offset")
                return false
            }
        }

        // Validate map file key
        if (map.fileKey.contains("..") || map.fileKey.startsWith("/")) {
            Log.e("SegmentValidation", "Invalid map file key (path traversal attempt): ${map.fileKey}")
            return false
        }

        // Validate map URI
        try {
            val mapUri = URI(map.uri)
            if (mapUri.scheme !in setOf("http", "https")) {
                Log.e("SegmentValidation", "Invalid map URI scheme: ${mapUri.scheme}")
                return false
            }
            // Security warning for HTTP (not HTTPS)
            if (mapUri.scheme == "http") {
                Log.w(
                    "SegmentValidation",
                    "SECURITY WARNING: Using HTTP (not HTTPS) for map segment. " +
                        "HTTP connections are vulnerable to man-in-the-middle attacks. " +
                        "URI: ${map.uri}",
                )
            }
        } catch (e: Exception) {
            Log.e("SegmentValidation", "Malformed map URI: ${map.uri}", e)
            return false
        }
    }

    return true
}

data class SegmentState(
    val uri: String,
    val sequence: Long,
    val fileKey: String,
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
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastErrorDetail: String? = null,
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

package com.rnandroidhlsapp.downloader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

class AndroidConstraintChecker(
    private val context: Context,
) : ConstraintChecker {
    override fun check(
        constraints: JobConstraints,
        request: DownloadRequest,
    ): ConstraintResult {
        if (constraints.requiresUnmetered && isMeteredNetwork()) {
            return ConstraintResult(allowed = false, reason = "Requires unmetered network")
        }
        if (constraints.requiresCharging && !isCharging()) {
            return ConstraintResult(allowed = false, reason = "Requires charging")
        }
        if (constraints.requiresIdle && !isIdle()) {
            return ConstraintResult(allowed = false, reason = "Requires idle device")
        }
        if (constraints.requiresStorageNotLow && isStorageLow(request)) {
            return ConstraintResult(allowed = false, reason = "Storage is low")
        }
        return ConstraintResult(allowed = true)
    }

    private fun isMeteredNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.isActiveNetworkMetered
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            return false
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isIdle(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isDeviceIdleMode
    }

    /**
     * Checks if storage is low for the download.
     *
     * Strategy:
     * 1. Check internal storage (where temp segments are downloaded) - require minimum 100MB
     * 2. If exportTreeUri is provided (SD card), check that location too
     * 3. Be permissive if export location check fails (better to try than block incorrectly)
     */
    private fun isStorageLow(request: DownloadRequest): Boolean {
        val minInternalStorageMB = 100L * 1024 * 1024 // 100MB minimum for temp downloads

        // Check internal storage where segments are temporarily downloaded
        val internalStats = try {
            StatFs(request.outputDir.absolutePath)
        } catch (e: Exception) {
            Log.e("AndroidConstraintChecker", "Failed to get internal storage stats", e)
            return false // Be permissive on error
        }

        val internalAvailable = internalStats.availableBytes
        Log.d("AndroidConstraintChecker", "Internal storage available: ${internalAvailable / 1024 / 1024}MB")

        if (internalAvailable < minInternalStorageMB) {
            Log.w("AndroidConstraintChecker", "Internal storage too low: ${internalAvailable / 1024 / 1024}MB < ${minInternalStorageMB / 1024 / 1024}MB")
            return true
        }

        // If export location is provided (SD card), check that too
        val exportUri = request.exportTreeUri
        if (exportUri != null) {
            val exportAvailable = getAvailableSpaceForUri(exportUri)
            if (exportAvailable != null) {
                Log.d("AndroidConstraintChecker", "Export location available: ${exportAvailable / 1024 / 1024}MB")

                // For export location, require at least 200MB (for the final assembled file)
                val minExportStorageMB = 200L * 1024 * 1024
                if (exportAvailable < minExportStorageMB) {
                    Log.w("AndroidConstraintChecker", "Export location storage too low: ${exportAvailable / 1024 / 1024}MB < ${minExportStorageMB / 1024 / 1024}MB")
                    return true
                }
            } else {
                Log.w("AndroidConstraintChecker", "Could not determine export location storage, proceeding optimistically")
                // Be permissive - if we can't check the SD card, assume it has space
                // Better to try and fail gracefully at export time than to block incorrectly
            }
        }

        return false
    }

    /**
     * Attempts to get available space for a content:// URI.
     * Returns null if unable to determine (e.g., complex SAF URIs).
     */
    private fun getAvailableSpaceForUri(uriString: String): Long? {
        return try {
            val uri = Uri.parse(uriString)

            // For content:// URIs from Storage Access Framework
            if (uri.scheme == "content") {
                // Try to resolve to a storage volume
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

                // Parse the URI to see if we can determine the storage volume
                // Content URIs from SAF typically look like:
                // content://com.android.externalstorage.documents/tree/primary:...
                // content://com.android.externalstorage.documents/tree/<volume-uuid>:...

                val authority = uri.authority
                if (authority == "com.android.externalstorage.documents") {
                    val documentId = uri.lastPathSegment
                    if (documentId != null) {
                        // Extract volume from document ID (format: "volumeId:path/to/folder")
                        val volumeId = documentId.substringBefore(':')

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val volumes = storageManager.storageVolumes
                            for (volume in volumes) {
                                // Check if this is the primary volume or matches the UUID
                                val isPrimary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    volume.isPrimary && volumeId == "primary"
                                } else {
                                    false
                                }

                                val matchesUuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    volume.uuid == volumeId
                                } else {
                                    false
                                }

                                if (isPrimary || matchesUuid) {
                                    // Found the volume, get its stats
                                    val volumePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        volume.directory?.absolutePath
                                    } else {
                                        // Fallback for older API levels
                                        null
                                    }

                                    if (volumePath != null) {
                                        val stats = StatFs(volumePath)
                                        return stats.availableBytes
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // If we couldn't determine storage via SAF, return null
            null
        } catch (e: Exception) {
            Log.w("AndroidConstraintChecker", "Failed to get storage for URI: $uriString", e)
            null
        }
    }
}

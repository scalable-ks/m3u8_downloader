package com.rnandroidhlsapp.downloader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log

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
     * - If exporting to SD card: only require 50MB internal (just for temp segments)
     * - If keeping files internal: require 100MB
     * - Trust user's SD card selection - if export fails, it will fail gracefully with clear error
     */
    private fun isStorageLow(request: DownloadRequest): Boolean {
        // Determine required internal storage based on whether user selected external export location
        val hasExportLocation = !request.exportTreeUri.isNullOrBlank()
        val minInternalStorageMB = if (hasExportLocation) {
            50L * 1024 * 1024  // 50MB if exporting to SD card (just for temp segments)
        } else {
            100L * 1024 * 1024  // 100MB if keeping files internally
        }

        // Check internal storage where segments are temporarily downloaded
        val internalStats = try {
            StatFs(request.outputDir.absolutePath)
        } catch (e: Exception) {
            Log.e("AndroidConstraintChecker", "Failed to get internal storage stats", e)
            return false // Be permissive on error
        }

        val internalAvailable = internalStats.availableBytes
        val minInternalMB = minInternalStorageMB / 1024 / 1024
        val availableMB = internalAvailable / 1024 / 1024

        Log.d(
            "AndroidConstraintChecker",
            "Storage check: internal=${availableMB}MB, required=${minInternalMB}MB, hasExportLocation=$hasExportLocation"
        )

        if (internalAvailable < minInternalStorageMB) {
            Log.w(
                "AndroidConstraintChecker",
                "Internal storage too low: ${availableMB}MB < ${minInternalMB}MB"
            )
            return true
        }

        return false
    }

}

package com.rnandroidhlsapp.downloader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
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
        if (constraints.requiresStorageNotLow && isStorageLow(request.outputDir)) {
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

    private fun isStorageLow(outputDir: File): Boolean {
        val stats = StatFs(outputDir.absolutePath)
        val available = stats.availableBytes
        val total = stats.totalBytes
        val lowThreshold = total / 20
        return available <= lowThreshold
    }
}

package com.rnandroidhlsapp

import android.content.Context
import java.io.File

/**
 * Stores large download plan JSON files to disk to avoid WorkManager's 10KB input data limit.
 * Plans are saved to app-private internal storage and cleaned up after job completion.
 */
class PlanFileStore(private val context: Context) {
    private val plansDir: File
        get() = File(context.filesDir, "plans").also { it.mkdirs() }

    /**
     * Saves a plan JSON string to disk.
     * @param jobId The unique job ID to use as filename
     * @param planJson The complete plan JSON string
     * @return true if save succeeded, false otherwise
     */
    fun save(jobId: String, planJson: String): Boolean {
        return try {
            val file = File(plansDir, "plan_$jobId.json")
            file.writeText(planJson)
            true
        } catch (e: Exception) {
            android.util.Log.e("PlanFileStore", "Failed to save plan for job $jobId", e)
            false
        }
    }

    /**
     * Loads a plan JSON string from disk.
     * @param jobId The unique job ID
     * @return The plan JSON string, or null if not found or error occurred
     */
    fun load(jobId: String): String? {
        return try {
            val file = File(plansDir, "plan_$jobId.json")
            if (file.exists()) {
                file.readText()
            } else {
                android.util.Log.w("PlanFileStore", "Plan file not found for job $jobId")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("PlanFileStore", "Failed to load plan for job $jobId", e)
            null
        }
    }

    /**
     * Deletes a plan file from disk.
     * @param jobId The unique job ID
     */
    fun delete(jobId: String) {
        try {
            val file = File(plansDir, "plan_$jobId.json")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("PlanFileStore", "Failed to delete plan for job $jobId", e)
        }
    }

    /**
     * Cleans up old plan files that may have been left behind.
     * Deletes files older than 24 hours.
     */
    fun cleanupOldPlans() {
        try {
            val now = System.currentTimeMillis()
            val maxAge = 24 * 60 * 60 * 1000L // 24 hours
            plansDir.listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified()) > maxAge) {
                    file.delete()
                    android.util.Log.d("PlanFileStore", "Cleaned up old plan file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlanFileStore", "Failed to cleanup old plans", e)
        }
    }
}

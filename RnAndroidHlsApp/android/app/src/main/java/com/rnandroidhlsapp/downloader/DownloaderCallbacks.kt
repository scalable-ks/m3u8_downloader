package com.rnandroidhlsapp.downloader

interface ProgressListener {
    fun onProgress(
        jobId: String,
        progress: JobProgress,
    )
}

interface ErrorListener {
    fun onError(
        jobId: String,
        code: String,
        message: String,
        detail: String? = null,
    )
}

interface CompletionListener {
    fun onComplete(
        jobId: String,
        state: JobState,
    )
}

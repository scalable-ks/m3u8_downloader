package com.rnandroidhls.muxing

interface FfmpegRunner {
    fun run(command: String): FfmpegResult
}

data class FfmpegResult(
    val success: Boolean,
    val returnCode: Int,
    val output: String? = null,
)

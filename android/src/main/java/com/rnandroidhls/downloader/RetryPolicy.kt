package com.rnandroidhls.downloader

import kotlin.math.pow
import kotlin.math.roundToLong

class RetryPolicy(
    private val maxAttempts: Int,
    private val baseDelayMs: Long,
    private val maxDelayMs: Long,
    private val jitterRatio: Double = 0.2,
) {
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    fun nextDelayMs(attempt: Int): Long {
        val exp = 2.0.pow(attempt.toDouble()).roundToLong()
        val raw = baseDelayMs * exp
        val jitter = (raw * jitterRatio).roundToLong().coerceAtLeast(0)
        val jittered = raw + (0..jitter).random()
        return jittered.coerceAtMost(maxDelayMs)
    }

    companion object {
        fun default() = RetryPolicy(maxAttempts = 5, baseDelayMs = 500, maxDelayMs = 10_000)
    }
}

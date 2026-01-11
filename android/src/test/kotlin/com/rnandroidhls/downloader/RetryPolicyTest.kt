package com.rnandroidhls.downloader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryPolicyTest {
    @Test
    fun `should allow retry up to max attempts`() {
        // ARRANGE
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, maxDelayMs = 1_000)

        // ACT
        val attempt0 = policy.shouldRetry(0)
        val attempt1 = policy.shouldRetry(1)
        val attempt2 = policy.shouldRetry(2)
        val attempt3 = policy.shouldRetry(3)

        // ASSERT
        assertTrue(attempt0)
        assertTrue(attempt1)
        assertTrue(attempt2)
        assertFalse(attempt3)
    }

    @Test
    fun `should cap exponential backoff at max delay`() {
        // ARRANGE
        val policy = RetryPolicy(maxAttempts = 5, baseDelayMs = 200, maxDelayMs = 1_000)

        // ACT
        val delay0 = policy.nextDelayMs(0)
        val delay2 = policy.nextDelayMs(2)
        val delay4 = policy.nextDelayMs(4)

        // ASSERT
        assertTrue(delay0 >= 200)
        assertTrue(delay2 >= 800)
        assertEquals(1_000, delay4)
    }
}

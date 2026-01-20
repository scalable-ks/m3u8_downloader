package com.rnandroidhlsapp.downloader

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Factory for creating shared OkHttpClient instances with proper configuration.
 *
 * IMPORTANT: OkHttpClient instances should be shared across the application
 * to benefit from connection pooling, which significantly reduces:
 * - Network latency (reuses TCP connections)
 * - Battery consumption (avoids repeated connection establishment)
 * - Memory usage (shared thread pools and connection pools)
 *
 * Creating multiple OkHttpClient instances defeats these benefits and wastes resources.
 */
object HttpClientFactory {
    /**
     * Shared OkHttpClient instance configured for HLS streaming.
     *
     * Configuration rationale:
     * - connectTimeout (15s): Reasonable for establishing connections on mobile networks
     * - readTimeout (30s): Accommodates large HLS segments on slow networks
     * - writeTimeout (10s): Default is sufficient for uploads
     * - Connection pooling: Automatic with shared client instance
     * - HTTP/2: Enabled by default for efficiency
     */
    @Volatile
    private var sharedClient: OkHttpClient? = null

    /**
     * Gets or creates the shared OkHttpClient instance.
     * Thread-safe with double-checked locking.
     */
    fun getSharedClient(): OkHttpClient {
        return sharedClient ?: synchronized(this) {
            sharedClient ?: createClient().also { sharedClient = it }
        }
    }

    /**
     * Interceptor that adds browser-like headers to all requests.
     * Many HLS servers require User-Agent and Referer headers to prevent scraping.
     */
    private val headerInterceptor = Interceptor { chain ->
        val original = chain.request()
        val url = original.url.toString()

        // Extract domain for Referer header
        val referer = original.url.scheme + "://" + original.url.host + "/"

        val request = original.newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Referer", referer)
            .build()

        chain.proceed(request)
    }

    /**
     * Creates a new OkHttpClient with appropriate configuration for HLS streaming.
     * Should only be called once to create the shared instance.
     */
    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(headerInterceptor)
            // Connection pool is configured automatically with reasonable defaults:
            // - 5 idle connections kept alive
            // - 5 minute keep-alive duration
            .build()
    }

    /**
     * For testing: allows resetting the shared client.
     * Should not be used in production code.
     */
    @Synchronized
    internal fun resetForTesting() {
        sharedClient = null
    }
}

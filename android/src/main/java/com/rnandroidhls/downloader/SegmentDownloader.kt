package com.rnandroidhls.downloader

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

open class SegmentDownloader(
    private val client: OkHttpClient,
) {
    @Throws(IOException::class)
    open fun downloadSegment(
        segment: Segment,
        destination: File,
        headers: Map<String, String>,
        resumeBytes: Long,
    ): Long {
        val requestBuilder = Request.Builder().url(segment.uri)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        if (resumeBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=$resumeBytes-")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val sink = destination.sink(append = resumeBytes > 0).buffer()
            body.source().use { source ->
                sink.use {
                    it.writeAll(source)
                }
            }
            return (resumeBytes + body.contentLength().coerceAtLeast(0))
        }
    }
}

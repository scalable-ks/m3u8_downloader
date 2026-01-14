package com.rnandroidhlsapp.downloader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class SegmentDownloader(
    private val client: OkHttpClient,
) {
    // LRU cache for decryption keys with max size of 100 entries
    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            val shouldRemove = size > MAX_KEY_CACHE_SIZE
            if (shouldRemove && eldest != null) {
                Log.d("SegmentDownloader", "Evicting key from cache: ${eldest.key}, cache size: $size")
            }
            return shouldRemove
        }
    }
    private val keyFetchLocks = ConcurrentHashMap<String, Any>()

    companion object {
        private const val MAX_KEY_CACHE_SIZE = 100
    }

    /**
     * Downloads a segment to the destination file.
     *
     * IMPORTANT: This is a suspend function that explicitly uses Dispatchers.IO
     * for all blocking operations (network, file I/O). This ensures blocking
     * operations never run on compute-bound dispatcher threads.
     */
    @Throws(IOException::class)
    open suspend fun downloadSegment(
        segment: Segment,
        destination: File,
        headers: Map<String, String>,
        resumeBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        val isEncrypted = segment.key?.method == "AES-128" && segment.key.uri != null
        val effectiveResume = if (isEncrypted) 0 else resumeBytes
        if (isEncrypted && resumeBytes > 0 && destination.exists()) {
            destination.delete()
        }
        val requestBuilder = Request.Builder().url(segment.uri)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val byteRange = segment.byteRange
        if (byteRange != null) {
            val offset = byteRange.offset ?: 0
            val start = offset + effectiveResume
            val end = offset + byteRange.length - 1
            requestBuilder.addHeader("Range", "bytes=$start-$end")
        } else if (effectiveResume > 0) {
            requestBuilder.addHeader("Range", "bytes=$effectiveResume-")
        }
        return@withContext client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val sink = destination.sink(append = effectiveResume > 0).buffer()
            if (isEncrypted) {
                val decrypted = decryptStream(body.byteStream(), segment, headers)
                decrypted.use { stream ->
                    sink.use { out ->
                        out.writeAll(stream.source().buffer())
                    }
                }
                destination.length()
            } else {
                body.source().use { source ->
                    sink.use {
                        it.writeAll(source)
                    }
                }
                effectiveResume + body.contentLength().coerceAtLeast(0)
            }
        }
    }

    @Throws(IOException::class)
    private fun decryptStream(
        input: java.io.InputStream,
        segment: Segment,
        headers: Map<String, String>,
    ): CipherInputStream {
        val keyInfo = segment.key ?: throw IOException("Missing key info")
        val keyUri = keyInfo.uri ?: throw IOException("Missing key URI")
        val keyBytes = fetchKeyBytes(keyUri, headers)
        val ivBytes = buildIv(keyInfo.iv, segment.sequence)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
        } catch (e: GeneralSecurityException) {
            throw IOException("Cipher init failed", e)
        }
        return CipherInputStream(input, cipher)
    }

    @Synchronized
    private fun fetchKeyBytes(
        uri: String,
        headers: Map<String, String>,
    ): ByteArray {
        // Check cache first (synchronized method ensures thread-safe access to LinkedHashMap)
        keyCache[uri]?.let {
            Log.d("SegmentDownloader", "Key cache hit: $uri, cache size: ${keyCache.size}")
            return it
        }

        // Get or create lock object for this URI
        val lock = keyFetchLocks.computeIfAbsent(uri) { Any() }

        // Double-checked locking with synchronized
        synchronized(lock) {
            // Check again after acquiring lock
            keyCache[uri]?.let { return it }

            // Only one thread reaches here per unique URI
            val requestBuilder = Request.Builder().url(uri)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            val bytes = client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Key HTTP ${response.code}")
                }
                response.body?.bytes() ?: throw IOException("Empty key body")
            }
            keyCache[uri] = bytes
            Log.d("SegmentDownloader", "Key cached: $uri, cache size: ${keyCache.size}")
            return bytes
        }
    }

    private fun buildIv(
        ivText: String?,
        sequence: Long,
    ): ByteArray {
        if (!ivText.isNullOrBlank()) {
            return parseHexIv(ivText)
        }
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(0)
        buffer.putLong(sequence)
        return buffer.array()
    }

    private fun parseHexIv(ivText: String): ByteArray {
        val normalized = ivText.removePrefix("0x").removePrefix("0X")
        if (normalized.length % 2 != 0) {
            throw IOException("Invalid IV length")
        }
        val output = ByteArray(normalized.length / 2)
        var index = 0
        while (index < normalized.length) {
            val byteValue = normalized.substring(index, index + 2).toInt(16)
            output[index / 2] = byteValue.toByte()
            index += 2
        }
        return output
    }
}

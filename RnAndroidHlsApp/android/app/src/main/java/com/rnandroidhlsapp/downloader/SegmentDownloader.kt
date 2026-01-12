package com.rnandroidhlsapp.downloader

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
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    @Throws(IOException::class)
    open fun downloadSegment(
        segment: Segment,
        destination: File,
        headers: Map<String, String>,
        resumeBytes: Long,
    ): Long {
        val isEncrypted = segment.key?.method == "AES-128" && segment.key.uri != null
        val effectiveResume = if (isEncrypted) 0 else resumeBytes
        if (isEncrypted && resumeBytes > 0 && destination.exists()) {
            destination.delete()
        }
        val requestBuilder = Request.Builder().url(segment.uri)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        if (effectiveResume > 0) {
            requestBuilder.addHeader("Range", "bytes=$effectiveResume-")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
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
                return destination.length()
            }
            body.source().use { source ->
                sink.use {
                    it.writeAll(source)
                }
            }
            return (effectiveResume + body.contentLength().coerceAtLeast(0))
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

    private fun fetchKeyBytes(
        uri: String,
        headers: Map<String, String>,
    ): ByteArray {
        return keyCache[uri] ?: run {
            val requestBuilder = Request.Builder().url(uri)
            headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Key HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("Empty key body")
                keyCache[uri] = bytes
                bytes
            }
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

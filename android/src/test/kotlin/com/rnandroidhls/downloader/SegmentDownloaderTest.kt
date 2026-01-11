package com.rnandroidhls.downloader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory

class SegmentDownloaderTest {
    @Test
    fun `downloads segment to file`() {
        // ARRANGE
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        val client = OkHttpClient()
        val downloader = SegmentDownloader(client)
        val tempDir = createTempDirectory().toFile()
        val file = File(tempDir, "seg.bin")
        val segment =
            Segment(
                uri = server.url("/seg.ts").toString(),
                duration = 6.0,
                sequence = 1,
            )

        // ACT
        val bytes = downloader.downloadSegment(segment, file, emptyMap(), 0)

        // ASSERT
        assertEquals(5, bytes)
        assertTrue(file.exists())
        assertEquals("hello", file.readText())

        server.shutdown()
    }

    @Test
    fun `resumes segment download with range header`() {
        // ARRANGE
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("world"))
        server.start()
        val client = OkHttpClient()
        val downloader = SegmentDownloader(client)
        val tempDir = createTempDirectory().toFile()
        val file = File(tempDir, "seg.bin")
        file.sink(append = false).buffer().use { it.writeUtf8("he") }
        val segment =
            Segment(
                uri = server.url("/seg.ts").toString(),
                duration = 6.0,
                sequence = 1,
            )

        // ACT
        val bytes = downloader.downloadSegment(segment, file, emptyMap(), 2)

        // ASSERT
        val request = server.takeRequest()
        assertEquals("bytes=2-", request.getHeader("Range"))
        assertEquals(7, bytes)
        assertEquals("heworld", file.readText())

        server.shutdown()
    }

    @Test
    fun `throws on non successful response`() {
        // ARRANGE
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val client = OkHttpClient()
        val downloader = SegmentDownloader(client)
        val tempDir = createTempDirectory().toFile()
        val file = File(tempDir, "seg.bin")
        val segment =
            Segment(
                uri = server.url("/seg.ts").toString(),
                duration = 6.0,
                sequence = 1,
            )

        // ACT + ASSERT
        assertThrows(IOException::class.java) {
            downloader.downloadSegment(segment, file, emptyMap(), 0)
        }

        server.shutdown()
    }

    @Test
    fun `decrypts AES-128 segment`() {
        // ARRANGE
        val keyBytes = "0123456789abcdef".toByteArray()
        val ivBytes = ByteArray(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes),
        )
        val plaintext = "secret"
        val encrypted = cipher.doFinal(plaintext.toByteArray())

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(encrypted)))
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))
        server.start()

        val client = OkHttpClient()
        val downloader = SegmentDownloader(client)
        val tempDir = createTempDirectory().toFile()
        val file = File(tempDir, "seg.bin")
        val segment =
            Segment(
                uri = server.url("/seg.ts").toString(),
                duration = 6.0,
                sequence = 1,
                key =
                    SegmentKey(
                        method = "AES-128",
                        uri = server.url("/key").toString(),
                        iv = "0x00000000000000000000000000000000",
                    ),
            )

        // ACT
        downloader.downloadSegment(segment, file, emptyMap(), 0)

        // ASSERT
        assertEquals(plaintext, file.readText())

        server.shutdown()
    }
}

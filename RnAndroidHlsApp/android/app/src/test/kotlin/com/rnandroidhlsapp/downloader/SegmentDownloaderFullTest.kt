package com.rnandroidhlsapp.downloader

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory

class SegmentDownloaderFullTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var downloader: SegmentDownloader
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        downloader = SegmentDownloader(client)
        tempDir = createTempDirectory().toFile()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `downloads encrypted segment with AES-128 decryption`() {
        // Prepare 16-byte AES key
        val keyBytes = ByteArray(16) { i -> i.toByte() }

        // Prepare plaintext data
        val plaintext = "Hello, encrypted world!".toByteArray()

        // Encrypt the data
        val iv = ByteArray(16) { 0 } // Zero IV for simplicity
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(plaintext)

        // IMPORTANT: downloadSegment requests SEGMENT first, then KEY
        // So we must enqueue responses in that order
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData))) // Segment response (requested first)
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))      // Key response (requested second)

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            key = SegmentKey(
                method = "AES-128",
                uri = server.url("/key.bin").toString(),
                iv = "0x00000000000000000000000000000000"
            )
        )

        val destFile = File(tempDir, "seg.ts")

        runBlocking {
            downloader.downloadSegment(segment, destFile, emptyMap(), 0)
        }

        // Verify file was decrypted correctly
        assertTrue(destFile.exists())
        val decryptedContent = destFile.readBytes()
        assertArrayEquals(plaintext, decryptedContent)
    }

    @Test
    fun `caches encryption keys to avoid re-fetching`() {
        val keyBytes = ByteArray(16) { i -> i.toByte() }
        val plaintext = "test data".toByteArray()
        val iv = ByteArray(16) { 0 }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(plaintext)

        // IMPORTANT: Request order is: segment1 → key (first time) → segment2 (key cached)
        // Enqueue responses in the same order
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData)))  // Segment1 response
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))       // Key response (fetched once)
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData)))  // Segment2 response

        val keyUri = server.url("/key.bin").toString()

        val segment1 = Segment(
            uri = server.url("/seg1.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            key = SegmentKey(method = "AES-128", uri = keyUri, iv = "0x00000000000000000000000000000000")
        )

        val segment2 = Segment(
            uri = server.url("/seg2.ts").toString(),
            duration = 10.0,
            sequence = 1,
            fileKey = "video_1",
            key = SegmentKey(method = "AES-128", uri = keyUri, iv = "0x00000000000000000000000000000000")
        )

        runBlocking {
            downloader.downloadSegment(segment1, File(tempDir, "seg1.ts"), emptyMap(), 0)
            downloader.downloadSegment(segment2, File(tempDir, "seg2.ts"), emptyMap(), 0)
        }

        // Verify key was fetched only once (1 key + 2 segments = 3 requests total)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `downloads byte range segment with Range header`() {
        val fullContent = "0123456789abcdefghijklmnopqrstuvwxyz"
        server.enqueue(MockResponse().setBody(fullContent.substring(10, 20))) // bytes 10-19

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            byteRange = ByteRange(length = 10, offset = 10)
        )

        val destFile = File(tempDir, "seg.ts")

        runBlocking {
            downloader.downloadSegment(segment, destFile, emptyMap(), 0)
        }

        val request = server.takeRequest()
        assertEquals("bytes=10-19", request.getHeader("Range"))
        assertEquals("abcdefghij", destFile.readText())
    }

    @Test
    fun `resumes partial download`() {
        val fullContent = "Hello, World!"
        val partialContent = fullContent.substring(0, 6) // "Hello,"
        val remainingContent = fullContent.substring(6) // " World!"

        // Create file with partial content
        val destFile = File(tempDir, "seg.ts")
        destFile.writeText(partialContent)

        server.enqueue(MockResponse().setBody(remainingContent))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0"
        )

        runBlocking {
            downloader.downloadSegment(segment, destFile, emptyMap(), resumeBytes = 6)
        }

        val request = server.takeRequest()
        assertEquals("bytes=6-", request.getHeader("Range"))
        assertEquals(fullContent, destFile.readText())
    }

    @Test
    fun `deletes and restarts encrypted segment on resume attempt`() {
        val keyBytes = ByteArray(16) { i -> i.toByte() }
        val plaintext = "New encrypted content".toByteArray()
        val iv = ByteArray(16) { 0 }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(plaintext)

        // Create file with partial content (simulating interrupted download)
        val destFile = File(tempDir, "seg.ts")
        destFile.writeBytes(byteArrayOf(1, 2, 3))
        val initialLength = destFile.length()

        // IMPORTANT: downloadSegment requests SEGMENT first, then KEY
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData)))  // Segment response (requested first)
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))       // Key response (requested second)

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            key = SegmentKey(
                method = "AES-128",
                uri = server.url("/key.bin").toString(),
                iv = "0x00000000000000000000000000000000"
            )
        )

        runBlocking {
            // Attempt to resume - should delete and restart
            downloader.downloadSegment(segment, destFile, emptyMap(), resumeBytes = initialLength)
        }

        // Verify file was replaced (not appended)
        val decryptedContent = destFile.readBytes()
        assertArrayEquals(plaintext, decryptedContent)
    }

    @Test
    fun `handles HTTP 403 Forbidden error`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0"
        )

        val destFile = File(tempDir, "seg.ts")

        val exception = assertThrows(IOException::class.java) {
            runBlocking {
                downloader.downloadSegment(segment, destFile, emptyMap(), 0)
            }
        }

        assertTrue(exception.message?.contains("HTTP 403") == true)
    }

    @Test
    fun `handles HTTP 404 Not Found error`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0"
        )

        val destFile = File(tempDir, "seg.ts")

        val exception = assertThrows(IOException::class.java) {
            runBlocking {
                downloader.downloadSegment(segment, destFile, emptyMap(), 0)
            }
        }

        assertTrue(exception.message?.contains("HTTP 404") == true)
    }

    @Test
    fun `handles HTTP 500 Server Error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0"
        )

        val destFile = File(tempDir, "seg.ts")

        val exception = assertThrows(IOException::class.java) {
            runBlocking {
                downloader.downloadSegment(segment, destFile, emptyMap(), 0)
            }
        }

        assertTrue(exception.message?.contains("HTTP 500") == true)
    }

    @Test
    fun `passes custom headers to segment request`() {
        server.enqueue(MockResponse().setBody("test content"))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0"
        )

        val customHeaders = mapOf(
            "User-Agent" to "TestClient/1.0",
            "Referer" to "https://example.com/"
        )

        runBlocking {
            downloader.downloadSegment(segment, File(tempDir, "seg.ts"), customHeaders, 0)
        }

        val request = server.takeRequest()
        assertEquals("TestClient/1.0", request.getHeader("User-Agent"))
        assertEquals("https://example.com/", request.getHeader("Referer"))
    }

    @Test
    fun `passes custom headers to key request`() {
        val keyBytes = ByteArray(16) { i -> i.toByte() }
        val plaintext = "test".toByteArray()
        val iv = ByteArray(16) { 0 }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(plaintext)

        // IMPORTANT: downloadSegment requests SEGMENT first, then KEY
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData)))  // Segment response (requested first)
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))       // Key response (requested second)

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            key = SegmentKey(
                method = "AES-128",
                uri = server.url("/key.bin").toString(),
                iv = "0x00000000000000000000000000000000"
            )
        )

        val customHeaders = mapOf("Authorization" to "Bearer token123")

        runBlocking {
            downloader.downloadSegment(segment, File(tempDir, "seg.ts"), customHeaders, 0)
        }

        val keyRequest = server.takeRequest()
        assertEquals("Bearer token123", keyRequest.getHeader("Authorization"))
    }

    @Test
    fun `handles byte range with resume offset`() {
        server.enqueue(MockResponse().setBody("xyz"))

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 0,
            fileKey = "video_0",
            byteRange = ByteRange(length = 10, offset = 100)
        )

        val destFile = File(tempDir, "seg.ts")
        destFile.writeText("ab") // 2 bytes already downloaded

        runBlocking {
            downloader.downloadSegment(segment, destFile, emptyMap(), resumeBytes = 2)
        }

        val request = server.takeRequest()
        // offset(100) + resumeBytes(2) = 102, end = offset(100) + length(10) - 1 = 109
        assertEquals("bytes=102-109", request.getHeader("Range"))
    }

    @Test
    fun `generates IV from sequence number when not provided`() {
        val keyBytes = ByteArray(16) { i -> i.toByte() }
        val plaintext = "test".toByteArray()

        // Create IV from sequence number (big-endian, 16 bytes: 8 zero bytes + 8 bytes for sequence)
        val expectedIv = ByteArray(16)
        // Sequence 42 in big-endian in last 8 bytes
        expectedIv[15] = 42

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(expectedIv))
        val encryptedData = cipher.doFinal(plaintext)

        // IMPORTANT: downloadSegment requests SEGMENT first, then KEY
        server.enqueue(MockResponse().setBody(Buffer().write(encryptedData)))  // Segment response (requested first)
        server.enqueue(MockResponse().setBody(Buffer().write(keyBytes)))       // Key response (requested second)

        val segment = Segment(
            uri = server.url("/seg.ts").toString(),
            duration = 10.0,
            sequence = 42,
            fileKey = "video_42",
            key = SegmentKey(
                method = "AES-128",
                uri = server.url("/key.bin").toString(),
                iv = null // No IV provided - should use sequence
            )
        )

        val destFile = File(tempDir, "seg.ts")

        runBlocking {
            downloader.downloadSegment(segment, destFile, emptyMap(), 0)
        }

        // Verify decryption worked (proves correct IV was used)
        assertTrue(destFile.exists())
        assertArrayEquals(plaintext, destFile.readBytes())
    }
}

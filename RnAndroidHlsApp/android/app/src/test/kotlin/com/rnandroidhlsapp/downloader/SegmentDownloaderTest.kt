package com.rnandroidhlsapp.downloader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SegmentDownloaderTest {
    @Test
    fun `uses byte range when provided`() {
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
                fileKey = "video_1",
                byteRange = ByteRange(length = 5, offset = 10),
            )

        // ACT
        downloader.downloadSegment(segment, file, emptyMap(), 2)

        // ASSERT
        val request = server.takeRequest()
        assertEquals("bytes=12-14", request.getHeader("Range"))

        server.shutdown()
    }
}

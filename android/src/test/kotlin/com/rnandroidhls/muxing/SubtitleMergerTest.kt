package com.rnandroidhls.muxing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SubtitleMergerTest {
    @Test
    fun `normalizes srt timestamps and line endings`() {
        // ARRANGE
        val input = "1\r\n00:00:01.000 --> 00:00:02.000\r\nHello\r\n"

        // ACT
        val normalized = SubtitleMerger.normalizeSrt(input)

        // ASSERT
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n", normalized)
    }

    @Test
    fun `merges srt segments with spacing`() {
        // ARRANGE
        val tempDir = createTempDirectory().toFile()
        val first = File(tempDir, "a.srt")
        val second = File(tempDir, "b.srt")
        first.writeText("1\n00:00:01,000 --> 00:00:02,000\nA\n")
        second.writeText("2\n00:00:03,000 --> 00:00:04,000\nB\n")
        val output = File(tempDir, "out.srt")

        // ACT
        SubtitleMerger.mergeSrtSegments(listOf(first, second), output)

        // ASSERT
        val content = output.readText()
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:03,000 --> 00:00:04,000\nB",
            content,
        )
    }
}

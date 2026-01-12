package com.rnandroidhlsapp.muxing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SubtitleMergerTest {
    @Test
    fun `normalizes srt timestamps and concatenates`() {
        // ARRANGE
        val tempDir = createTempDirectory().toFile()
        val partA =
            File(tempDir, "a.srt").apply {
                writeText("1\n00:00:01.000 --> 00:00:02.000\nHello\n")
            }
        val partB =
            File(tempDir, "b.srt").apply {
                writeText("2\n00:00:03.000 --> 00:00:04.000\nWorld\n")
            }
        val out = File(tempDir, "out.srt")

        // ACT
        SubtitleMerger.mergeSrtSegments(listOf(partA, partB), out)

        // ASSERT
        val expected =
            "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n" +
                "2\n00:00:03,000 --> 00:00:04,000\nWorld"
        assertEquals(expected, out.readText())
    }
}

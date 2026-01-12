package com.rnandroidhlsapp.muxing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ConcatListWriterTest {
    @Test
    fun `writes concat list with init file first`() {
        // ARRANGE
        val tempDir = createTempDirectory().toFile()
        val output = File(tempDir, "list.txt")
        val initFile = File(tempDir, "init.mp4").apply { writeText("init") }
        val segmentA = File(tempDir, "segA.ts").apply { writeText("a") }
        val segmentB = File(tempDir, "segB.ts").apply { writeText("b") }

        // ACT
        ConcatListWriter.write(output, listOf(segmentA, segmentB), initFile)

        // ASSERT
        val expected =
            listOf(
                "file '${initFile.absolutePath}'",
                "file '${segmentA.absolutePath}'",
                "file '${segmentB.absolutePath}'",
            ).joinToString("\n")
        assertEquals(expected, output.readText())
    }
}

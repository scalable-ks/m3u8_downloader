package com.rnandroidhls.muxing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ConcatListWriterTest {
    @Test
    fun `writes concat list with init segment`() {
        // ARRANGE
        val tempDir = createTempDirectory().toFile()
        val initFile = File(tempDir, "init.mp4")
        val segment = File(tempDir, "seg1.m4s")
        initFile.writeText("init")
        segment.writeText("seg")
        val output = File(tempDir, "list.txt")

        // ACT
        ConcatListWriter.write(output, listOf(segment), initFile)

        // ASSERT
        val content = output.readText()
        assertTrue(content.contains("file '${initFile.absolutePath}'"))
        assertTrue(content.contains("file '${segment.absolutePath}'"))
    }
}

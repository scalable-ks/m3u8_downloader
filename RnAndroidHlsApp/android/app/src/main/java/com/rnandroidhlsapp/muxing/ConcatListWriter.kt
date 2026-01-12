package com.rnandroidhlsapp.muxing

import java.io.File

object ConcatListWriter {
    fun write(
        outputFile: File,
        segmentFiles: List<File>,
        initFile: File? = null,
    ): File {
        outputFile.parentFile?.mkdirs()
        val lines = mutableListOf<String>()
        if (initFile != null) {
            lines.add("file '${initFile.absolutePath}'")
        }
        segmentFiles.forEach { segment ->
            lines.add("file '${segment.absolutePath}'")
        }
        outputFile.writeText(lines.joinToString("\n"))
        return outputFile
    }
}

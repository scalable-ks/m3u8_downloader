package com.rnandroidhlsapp.muxing

import java.io.File

object SubtitleMerger {
    fun mergeSrtSegments(
        inputFiles: List<File>,
        outputFile: File,
    ): File {
        outputFile.parentFile?.mkdirs()
        val merged = StringBuilder()
        inputFiles.forEachIndexed { index, file ->
            if (!file.exists()) {
                return@forEachIndexed
            }
            val normalized = normalizeSrt(file.readText())
            merged.append(normalized.trimEnd())
            if (index < inputFiles.lastIndex) {
                merged.append("\n\n")
            }
        }
        outputFile.writeText(merged.toString())
        return outputFile
    }

    fun normalizeSrt(input: String): String {
        val normalizedLineEndings = input.replace("\r\n", "\n").replace("\r", "\n")
        return normalizedLineEndings.replace(
            Regex("(\\d{2}:\\d{2}:\\d{2})[\\.,](\\d{3})"),
            "$1,$2",
        )
    }
}

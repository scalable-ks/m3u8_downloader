package com.rnandroidhlsapp.muxing

import java.io.File

class Mp4Assembler(
    private val runner: FfmpegRunner,
    private val commandBuilder: FfmpegCommandBuilder = FfmpegCommandBuilder(),
) {
    fun assemble(request: MuxRequest): FfmpegResult {
        val outputFile = File(request.outputPath)
        outputFile.parentFile?.mkdirs()
        val command = commandBuilder.build(request)
        return runner.run(command)
    }
}

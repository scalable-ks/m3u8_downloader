package com.rnandroidhls.muxing

class FfmpegCommandBuilder {
    fun build(request: MuxRequest): String {
        val args = mutableListOf("-y")

        appendInput(args, request.video)
        appendInput(args, request.audio)

        if (request.subtitles != null) {
            args.addAll(listOf("-i", request.subtitles.path))
        }

        val maps = mutableListOf("-map", "0:v:0", "-map", "1:a:0")
        if (request.subtitles != null) {
            maps.addAll(listOf("-map", "2:s:0"))
        }

        val codecs = mutableListOf<String>()
        codecs.addAll(selectVideoCodec(request.transcodeVideo))
        codecs.addAll(selectAudioCodec(request.transcodeAudio))
        if (request.subtitles != null) {
            codecs.addAll(listOf("-c:s", "mov_text"))
        }

        val metadata = mutableListOf<String>()
        if (request.subtitles != null) {
            metadata.addAll(listOf("-metadata:s:s:0", "language=${request.subtitles.language}"))
        }

        return (args + maps + codecs + metadata + request.outputPath)
            .joinToString(" ")
    }

    private fun appendInput(
        args: MutableList<String>,
        input: TrackInput,
    ) {
        if (input.isConcatList) {
            args.addAll(listOf("-f", "concat", "-safe", "0"))
        }
        args.addAll(listOf("-i", input.path))
    }

    private fun selectVideoCodec(transcode: Boolean): List<String> {
        return if (transcode) {
            listOf("-c:v", "libx264")
        } else {
            listOf("-c:v", "copy")
        }
    }

    private fun selectAudioCodec(transcode: Boolean): List<String> {
        return if (transcode) {
            listOf("-c:a", "aac", "-b:a", "192k")
        } else {
            listOf("-c:a", "copy")
        }
    }
}

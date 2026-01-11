package com.rnandroidhls.muxing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FfmpegCommandBuilderTest {
    @Test
    fun `builds remux command with subtitles`() {
        // ARRANGE
        val builder = FfmpegCommandBuilder()
        val request =
            MuxRequest(
                video = TrackInput(path = "video.txt", isConcatList = true),
                audio = TrackInput(path = "audio.txt", isConcatList = true),
                subtitles = SubtitleInput(path = "subs.srt", language = "eng"),
                outputPath = "out.mp4",
                transcodeVideo = false,
                transcodeAudio = true,
            )

        // ACT
        val command = builder.build(request)

        // ASSERT
        assertTrue(command.contains("-f concat"))
        assertTrue(command.contains("-i video.txt"))
        assertTrue(command.contains("-i audio.txt"))
        assertTrue(command.contains("-i subs.srt"))
        assertTrue(command.contains("-c:v copy"))
        assertTrue(command.contains("-c:a aac"))
        assertTrue(command.contains("-c:s mov_text"))
        assertTrue(command.contains("-metadata:s:s:0 language=eng"))
    }
}

package com.rnandroidhlsapp.muxing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FfmpegCommandBuilderTest {
    @Test
    fun `builds command with audio and subtitles`() {
        // ARRANGE
        val builder = FfmpegCommandBuilder()
        val request =
            MuxRequest(
                video = TrackInput("/tmp/video.txt", isConcatList = true),
                audio = TrackInput("/tmp/audio.txt", isConcatList = true),
                subtitles = SubtitleInput("/tmp/subs.srt", "eng"),
                outputPath = "/tmp/out.mp4",
            )

        // ACT
        val command = builder.build(request)

        // ASSERT
        assertTrue(command.contains("-f concat -safe 0 -i /tmp/video.txt"))
        assertTrue(command.contains("-f concat -safe 0 -i /tmp/audio.txt"))
        assertTrue(command.contains("-i /tmp/subs.srt"))
        assertTrue(command.contains("-map 0:v:0 -map 1:a:0 -map 2:s:0"))
        assertTrue(command.contains("-c:s mov_text"))
    }

    @Test
    fun `builds command without audio`() {
        // ARRANGE
        val builder = FfmpegCommandBuilder()
        val request =
            MuxRequest(
                video = TrackInput("/tmp/video.txt", isConcatList = true),
                audio = null,
                outputPath = "/tmp/out.mp4",
            )

        // ACT
        val command = builder.build(request)

        // ASSERT
        assertTrue(command.contains("-map 0:v:0"))
        assertTrue(command.contains(" -an "))
    }
}

package com.rnandroidhlsapp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class HlsPlanParserTest {
    @Test
    fun `parses plan into track-keyed segments and constraints`() {
        // ARRANGE
        val planJson =
            """
            {
              "id": "job-1",
              "masterPlaylistUri": "https://example.com/master.m3u8",
              "tracks": { "subtitle": { "language": "eng" } },
              "video": {
                "playlistUri": "https://example.com/video.m3u8",
                "segments": [
                  { "uri": "https://example.com/v1.ts", "duration": 4.0, "sequence": 1 }
                ]
              },
              "audio": {
                "playlistUri": "https://example.com/audio.m3u8",
                "segments": [
                  { "uri": "https://example.com/a1.ts", "duration": 4.0, "sequence": 1 }
                ]
              },
              "subtitles": {
                "playlistUri": "https://example.com/subs.m3u8",
                "segments": [
                  { "uri": "https://example.com/s1.srt", "duration": 4.0, "sequence": 1 }
                ]
              },
              "constraints": { "requiresUnmetered": true },
              "cleanupPolicy": { "deleteOnSuccess": true }
            }
            """.trimIndent()

        // ACT
        val outputDir = createTempDirectory().toFile()
        val parsed = HlsPlanParser.parse(planJson, outputDir)

        // ASSERT
        assertEquals("job-1", parsed.request.id)
        assertTrue(parsed.request.constraints.requiresUnmetered)
        assertTrue(parsed.request.cleanupPolicy.deleteOnSuccess)
        assertEquals("video_1", parsed.videoSegments.first().fileKey)
        assertEquals("audio_1", parsed.audioSegments.first().fileKey)
        assertEquals("subtitle_1", parsed.subtitleSegments.first().fileKey)
        assertEquals("eng", parsed.subtitleLanguage)
    }
}

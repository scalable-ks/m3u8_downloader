package com.rnandroidhls.muxing

data class TrackInput(
    val path: String,
    val isConcatList: Boolean,
)

data class SubtitleInput(
    val path: String,
    val language: String,
)

data class MuxRequest(
    val video: TrackInput,
    val audio: TrackInput,
    val subtitles: SubtitleInput? = null,
    val outputPath: String,
    val transcodeVideo: Boolean = false,
    val transcodeAudio: Boolean = false,
)

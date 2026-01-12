package com.rnandroidhlsapp

import android.content.Context
import com.rnandroidhlsapp.downloader.ByteRange
import com.rnandroidhlsapp.downloader.CleanupPolicy
import com.rnandroidhlsapp.downloader.DownloadRequest
import com.rnandroidhlsapp.downloader.JobConstraints
import com.rnandroidhlsapp.downloader.Segment
import com.rnandroidhlsapp.downloader.SegmentKey
import com.rnandroidhlsapp.downloader.SegmentMap
import com.rnandroidhlsapp.downloader.StorageLocator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ParsedPlan(
    val request: DownloadRequest,
    val segments: List<Segment>,
    val videoSegments: List<Segment>,
    val audioSegments: List<Segment>,
    val subtitleSegments: List<Segment>,
    val subtitleLanguage: String?,
    val exportTreeUri: String?,
)

object HlsPlanParser {
    fun parse(
        context: Context,
        planJson: String,
    ): ParsedPlan {
        val plan = JSONObject(planJson)
        val jobId = plan.getString("id")
        val outputDir = File(StorageLocator.tempDir(context), "job_$jobId").apply { mkdirs() }
        return parse(plan, outputDir)
    }

    fun parse(
        planJson: String,
        outputDir: File,
    ): ParsedPlan {
        val plan = JSONObject(planJson)
        return parse(plan, outputDir)
    }

    private fun parse(
        plan: JSONObject,
        outputDir: File,
    ): ParsedPlan {
        val jobId = plan.getString("id")
        val headers = readHeaders(plan.optJSONObject("headers"))
        val exportTreeUri = plan.optString("exportTreeUri", null)
        val videoSegments = readSegments(plan.getJSONObject("video").getJSONArray("segments"), "video")
        val audioSegments =
            plan.optJSONObject("audio")?.optJSONArray("segments")?.let { audioArray ->
                readSegments(audioArray, "audio")
            } ?: emptyList()
        val subtitleSegments =
            plan.optJSONObject("subtitles")?.optJSONArray("segments")?.let { subtitleArray ->
                readSegments(subtitleArray, "subtitle")
            } ?: emptyList()
        val subtitleLanguage =
            plan.optJSONObject("tracks")
                ?.optJSONObject("subtitle")
                ?.optString("language", null)
        val segments = videoSegments + audioSegments + subtitleSegments
        val request =
            DownloadRequest(
                id = jobId,
                playlistUri = plan.getString("masterPlaylistUri"),
                outputDir = outputDir,
                headers = headers,
                playlistMetadata = plan.toString(),
                exportTreeUri = exportTreeUri,
                constraints = readConstraints(plan.optJSONObject("constraints")),
                cleanupPolicy = readCleanupPolicy(plan.optJSONObject("cleanupPolicy")),
            )
        return ParsedPlan(
            request = request,
            segments = segments,
            videoSegments = videoSegments,
            audioSegments = audioSegments,
            subtitleSegments = subtitleSegments,
            subtitleLanguage = subtitleLanguage,
            exportTreeUri = exportTreeUri,
        )
    }

    private fun readHeaders(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.getString(key)
        }
        return result
    }

    private fun readSegments(
        array: JSONArray,
        trackId: String,
    ): List<Segment> {
        val output = mutableListOf<Segment>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val keyJson = item.optJSONObject("key")
            val mapJson = item.optJSONObject("map")
            val key =
                if (keyJson != null) {
                    SegmentKey(
                        method = keyJson.optString("method"),
                        uri = keyJson.optString("uri", null),
                        iv = keyJson.optString("iv", null),
                    )
                } else {
                    null
                }
            val map =
                if (mapJson != null) {
                    val mapUri = mapJson.optString("uri", null)
                    if (mapUri != null) {
                        SegmentMap(
                            uri = mapUri,
                            byteRange = readByteRange(mapJson.optJSONObject("byteRange")),
                            fileKey = mapFileKey(trackId, mapUri),
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            val sequence = item.getLong("sequence")
            output.add(
                Segment(
                    uri = item.getString("uri"),
                    duration = item.getDouble("duration"),
                    sequence = sequence,
                    fileKey = segmentFileKey(trackId, sequence),
                    byteRange = readByteRange(item.optJSONObject("byteRange")),
                    key = key,
                    map = map,
                ),
            )
        }
        return output
    }

    private fun readByteRange(json: JSONObject?): ByteRange? {
        if (json == null) return null
        val length = json.optLong("length", -1)
        if (length <= 0) return null
        val offset = json.optLong("offset", -1).takeIf { it >= 0 }
        return ByteRange(length = length, offset = offset)
    }

    private fun readConstraints(json: JSONObject?): JobConstraints {
        if (json == null) return JobConstraints()
        return JobConstraints(
            requiresUnmetered = json.optBoolean("requiresUnmetered", false),
            requiresCharging = json.optBoolean("requiresCharging", false),
            requiresIdle = json.optBoolean("requiresIdle", false),
            requiresStorageNotLow = json.optBoolean("requiresStorageNotLow", false),
        )
    }

    private fun readCleanupPolicy(json: JSONObject?): CleanupPolicy {
        if (json == null) return CleanupPolicy()
        return CleanupPolicy(
            deleteOnFailure = json.optBoolean("deleteOnFailure", true),
            deleteOnCancel = json.optBoolean("deleteOnCancel", true),
            deleteOnSuccess = json.optBoolean("deleteOnSuccess", false),
        )
    }

    private fun segmentFileKey(
        trackId: String,
        sequence: Long,
    ): String = "${trackId}_$sequence"

    private fun mapFileKey(
        trackId: String,
        uri: String,
    ): String = "${trackId}_${uri.hashCode()}"
}

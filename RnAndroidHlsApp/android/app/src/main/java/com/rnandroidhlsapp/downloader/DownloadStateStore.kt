package com.rnandroidhlsapp.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface DownloadStateStore {
    fun get(jobId: String): DownloadJobState?

    fun list(): List<DownloadJobState>

    fun save(state: DownloadJobState)

    fun updateSegment(
        jobId: String,
        segment: SegmentState,
    )

    fun delete(jobId: String)
}

class FileDownloadStateStore(
    private val baseDir: File,
) : DownloadStateStore {
    constructor(context: Context) : this(context.filesDir)

    override fun get(jobId: String): DownloadJobState? {
        val file = stateFile(jobId)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return decodeState(json)
    }

    override fun list(): List<DownloadJobState> {
        val dir = stateDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { decodeState(JSONObject(file.readText())) }.getOrNull()
            }
            ?: emptyList()
    }

    @Synchronized
    override fun save(state: DownloadJobState) {
        val file = stateFile(state.id)
        file.writeText(encodeState(state).toString())
    }

    @Synchronized
    override fun updateSegment(
        jobId: String,
        segment: SegmentState,
    ) {
        val existing = get(jobId) ?: return
        val updated =
            existing.copy(
                segments = existing.segments.map { if (it.fileKey == segment.fileKey) segment else it },
                updatedAt = System.currentTimeMillis(),
            )
        save(updated)
    }

    @Synchronized
    override fun delete(jobId: String) {
        val file = stateFile(jobId)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun stateFile(jobId: String): File {
        val dir = stateDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$jobId.json")
    }

    private fun stateDir(): File = File(baseDir, "download_state")

    private fun encodeState(state: DownloadJobState): JSONObject {
        val segments = JSONArray()
        state.segments.forEach { segment ->
            val totalBytes = segment.totalBytes
            segments.put(
                JSONObject()
                    .put("uri", segment.uri)
                    .put("sequence", segment.sequence)
                    .put("fileKey", segment.fileKey)
                    .put("status", segment.status.name)
                    .put("bytesDownloaded", segment.bytesDownloaded)
                    .put("totalBytes", totalBytes ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("id", state.id)
            .put("playlistUri", state.playlistUri)
            .put("playlistMetadata", state.playlistMetadata ?: JSONObject.NULL)
            .put("state", state.state.name)
            .put("segments", segments)
            .put("createdAt", state.createdAt)
            .put("updatedAt", state.updatedAt)
    }

    private fun decodeState(json: JSONObject): DownloadJobState {
        val segmentsJson = json.getJSONArray("segments")
        val segments = mutableListOf<SegmentState>()
        for (i in 0 until segmentsJson.length()) {
            val item = segmentsJson.getJSONObject(i)
            segments.add(
                SegmentState(
                    uri = item.getString("uri"),
                    sequence = item.getLong("sequence"),
                    fileKey = item.optString("fileKey", item.getLong("sequence").toString()),
                    status = SegmentStatus.valueOf(item.getString("status")),
                    bytesDownloaded = item.getLong("bytesDownloaded"),
                    totalBytes = item.optLong("totalBytes").takeIf { it > 0 },
                ),
            )
        }
        return DownloadJobState(
            id = json.getString("id"),
            playlistUri = json.getString("playlistUri"),
            playlistMetadata =
                json.opt("playlistMetadata")
                    .takeIf { it != JSONObject.NULL }
                    ?.toString(),
            state = JobState.valueOf(json.getString("state")),
            segments = segments,
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
        )
    }
}

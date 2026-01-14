package com.rnandroidhlsapp.downloader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

        return try {
            val json = JSONObject(file.readText())
            decodeState(json)
        } catch (e: JSONException) {
            Log.e("StateStore", "Corrupted state file for job $jobId, deleting", e)
            file.delete()
            null
        } catch (e: IOException) {
            Log.e("StateStore", "I/O error reading state for job $jobId", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e("StateStore", "Invalid state data for job $jobId, deleting", e)
            file.delete()
            null
        } catch (e: Exception) {
            Log.e("StateStore", "Unexpected error reading state for job $jobId, deleting", e)
            file.delete()
            null
        }
    }

    override fun list(): List<DownloadJobState> {
        val dir = stateDir()
        if (!dir.exists()) return emptyList()

        var corruptedCount = 0
        val states =
            dir.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapNotNull { file ->
                    runCatching {
                        decodeState(JSONObject(file.readText()))
                    }.onFailure { e ->
                        Log.w("StateStore", "Skipping corrupted file: ${file.name}", e)
                        corruptedCount++
                        file.delete()
                    }.getOrNull()
                }
                ?: emptyList()

        if (corruptedCount > 0) {
            Log.w("StateStore", "Removed $corruptedCount corrupted state files")
        }

        return states
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
            .put("lastErrorCode", state.lastErrorCode ?: JSONObject.NULL)
            .put("lastErrorMessage", state.lastErrorMessage ?: JSONObject.NULL)
            .put("lastErrorDetail", state.lastErrorDetail ?: JSONObject.NULL)
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
            lastErrorCode = json.opt("lastErrorCode").takeIf { it != JSONObject.NULL }?.toString(),
            lastErrorMessage = json.opt("lastErrorMessage").takeIf { it != JSONObject.NULL }?.toString(),
            lastErrorDetail = json.opt("lastErrorDetail").takeIf { it != JSONObject.NULL }?.toString(),
        )
    }
}

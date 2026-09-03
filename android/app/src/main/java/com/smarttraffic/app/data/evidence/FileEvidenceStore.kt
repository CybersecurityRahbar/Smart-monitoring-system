package com.smarttraffic.app.data.evidence

import android.content.Context
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import com.smarttraffic.app.domain.analysis.EvidenceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable local evidence vault. Records contain source/time/frame references rather
 * than opaque UI state, so a local-video frame can be deterministically re-opened later.
 */
class FileEvidenceStore(context: Context) : EvidenceStore {
    private val file = context.getFileStreamPath(FILE_NAME)

    override suspend fun save(record: EvidenceRecord): EvidenceRecord {
        validateRecord(record)
        val records = readRecords().toMutableList()
        records.removeAll { it.id == record.id }
        records += record
        writeRecords(records.sortedByDescending { it.createdAtMs })
        return record
    }

    override suspend fun list(): List<EvidenceRecord> = readRecords()

    override suspend fun clear() {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Unable to delete evidence store: ${file.absolutePath}")
        }
    }

    private fun readRecords(): List<EvidenceRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val record = fromJson(array.getJSONObject(i))
                    validateRecord(record)
                    add(record)
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Evidence store is unreadable: ${file.absolutePath}",
                error,
            )
        }
    }

    private fun writeRecords(records: List<EvidenceRecord>) {
        val temporary = file.resolveSibling("$FILE_NAME.tmp")
        try {
            temporary.writeText(JSONArray().apply { records.forEach { put(toJson(it)) } }.toString())
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Unable to replace existing evidence store")
            }
            if (!temporary.renameTo(file)) {
                throw IllegalStateException("Unable to atomically publish evidence store")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validateRecord(record: EvidenceRecord) {
        require(record.id.isNotBlank()) { "Evidence id must not be blank" }
        require(record.eventId.isNotBlank()) { "Evidence eventId must not be blank" }
        require(record.sourceId.isNotBlank()) { "Evidence sourceId must not be blank" }
        require(record.sourceUri.isNotBlank()) { "Evidence sourceUri must not be blank" }
        require(record.frameIndex >= 0L) { "Evidence frameIndex must be non-negative" }
        require(record.timestampMs >= 0L) { "Evidence timestampMs must be non-negative" }
        require(record.eventType.isNotBlank()) { "Evidence eventType must not be blank" }
        require(record.measuredSpeedKmh.isFinite() && record.measuredSpeedKmh >= 0.0) {
            "Evidence speed must be finite and non-negative"
        }
        require(record.thresholdKmh.isFinite() && record.thresholdKmh > 0.0) {
            "Evidence threshold must be finite and positive"
        }
        require(record.confidence.isFinite() && record.confidence in 0f..1f) {
            "Evidence confidence must be within [0,1]"
        }
        require(record.trackId > 0L) { "Evidence trackId must be positive" }
        require(record.detectorModel.isNotBlank()) { "Evidence detectorModel must not be blank" }
        require(record.tracker.isNotBlank()) { "Evidence tracker must not be blank" }
        require(record.createdAtMs > 0L) { "Evidence createdAtMs must be positive" }
    }

    private fun toJson(record: EvidenceRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("eventId", record.eventId)
        put("sourceId", record.sourceId)
        put("sourceUri", record.sourceUri)
        put("frameIndex", record.frameIndex)
        put("timestampMs", record.timestampMs)
        put("eventType", record.eventType)
        put("measuredSpeedKmh", record.measuredSpeedKmh)
        put("thresholdKmh", record.thresholdKmh)
        put("confidence", record.confidence.toDouble())
        put("trackId", record.trackId)
        put("plateText", record.plateText ?: JSONObject.NULL)
        put("calibrationId", record.calibrationId ?: JSONObject.NULL)
        put("calibrationVersion", record.calibrationVersion ?: JSONObject.NULL)
        put("detectorModel", record.detectorModel)
        put("tracker", record.tracker)
        put("createdAtMs", record.createdAtMs)
    }

    private fun fromJson(json: JSONObject): EvidenceRecord = EvidenceRecord(
        id = json.getString("id"),
        eventId = json.getString("eventId"),
        sourceId = json.getString("sourceId"),
        sourceUri = json.getString("sourceUri"),
        frameIndex = json.getLong("frameIndex"),
        timestampMs = json.getLong("timestampMs"),
        eventType = json.getString("eventType"),
        measuredSpeedKmh = json.getDouble("measuredSpeedKmh"),
        thresholdKmh = json.getDouble("thresholdKmh"),
        confidence = json.getDouble("confidence").toFloat(),
        trackId = json.getLong("trackId"),
        plateText = json.optString("plateText").takeUnless { it.isBlank() || it == "null" },
        calibrationId = json.optString("calibrationId").takeUnless { it.isBlank() || it == "null" },
        calibrationVersion = if (json.isNull("calibrationVersion")) null else json.getInt("calibrationVersion"),
        detectorModel = json.getString("detectorModel"),
        tracker = json.getString("tracker"),
        createdAtMs = json.getLong("createdAtMs"),
    )

    private companion object {
        const val FILE_NAME = "traffic_evidence.json"
    }
}

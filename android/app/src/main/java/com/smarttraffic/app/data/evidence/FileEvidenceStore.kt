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
        val records = readRecords().toMutableList()
        records.removeAll { it.id == record.id }
        records += record
        writeRecords(records.sortedByDescending { it.createdAtMs })
        return record
    }

    override suspend fun list(): List<EvidenceRecord> = readRecords()

    override suspend fun clear() {
        if (file.exists()) file.delete()
    }

    private fun readRecords(): List<EvidenceRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList(array.length()) {
                for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
            }.sortedByDescending { it.createdAtMs }
        }.getOrDefault(emptyList())
    }

    private fun writeRecords(records: List<EvidenceRecord>) {
        file.writeText(JSONArray().apply { records.forEach { put(toJson(it)) } }.toString())
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

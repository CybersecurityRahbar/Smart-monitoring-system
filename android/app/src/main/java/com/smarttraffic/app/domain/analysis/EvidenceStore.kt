package com.smarttraffic.app.domain.analysis

/** Persistent forensic reference generated from a confirmed traffic event. */
data class EvidenceRecord(
    val id: String,
    val eventId: String,
    val sourceId: String,
    val sourceUri: String,
    val frameIndex: Long,
    val timestampMs: Long,
    val eventType: String,
    val measuredSpeedKmh: Double,
    val thresholdKmh: Double,
    val confidence: Float,
    val trackId: Long,
    val plateText: String? = null,
    val calibrationId: String? = null,
    val calibrationVersion: Int? = null,
    val detectorModel: String,
    val tracker: String,
    val createdAtMs: Long,
)

/** Storage contract deliberately kept separate from the analysis engine. */
interface EvidenceStore {
    suspend fun save(record: EvidenceRecord): EvidenceRecord
    suspend fun list(): List<EvidenceRecord>
    suspend fun clear()
}

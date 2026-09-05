package com.smarttraffic.app.domain.analysis

/** Persisted image artifacts associated with a traffic event. */
data class EvidenceArtifacts(
    val frameJpeg: ByteArray,
    val vehicleCropJpeg: ByteArray,
    val plateCropJpeg: ByteArray? = null,
) {
    init {
        require(frameJpeg.isNotEmpty()) { "Evidence frame artifact must not be empty" }
        require(vehicleCropJpeg.isNotEmpty()) { "Evidence vehicle crop artifact must not be empty" }
        if (plateCropJpeg != null) require(plateCropJpeg.isNotEmpty()) { "Evidence plate crop artifact must not be empty" }
    }
}

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
    val frameSha256: String? = null,
    val vehicleCropSha256: String? = null,
    val plateCropSha256: String? = null,
)

/** Storage contract deliberately kept separate from the analysis engine. */
interface EvidenceStore {
    suspend fun save(record: EvidenceRecord, artifacts: EvidenceArtifacts? = null): EvidenceRecord
    suspend fun list(): List<EvidenceRecord>
    suspend fun clear()
}

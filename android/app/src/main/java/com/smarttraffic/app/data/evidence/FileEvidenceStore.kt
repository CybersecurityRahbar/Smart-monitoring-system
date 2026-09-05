package com.smarttraffic.app.data.evidence

import android.content.Context
import com.smarttraffic.app.domain.analysis.EvidenceArtifacts
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import com.smarttraffic.app.domain.analysis.EvidenceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Durable local evidence vault with metadata plus bounded JPEG artifacts.
 *
 * Metadata and artifacts are persisted behind one coroutine mutex, with temporary files published
 * before the metadata index is replaced. Retention prevents indefinite device growth.
 */
class FileEvidenceStore(
    context: Context,
    private val maxRecords: Int = 256,
    private val maxTotalArtifactBytes: Long = 256L * 1024L * 1024L,
) : EvidenceStore {
    private val file = context.getFileStreamPath(FILE_NAME)
    private val artifactDir = context.getDir(ARTIFACT_DIR, Context.MODE_PRIVATE)
    private val ioMutex = Mutex()

    init {
        require(maxRecords >= 1) { "maxRecords must be >= 1" }
        require(maxTotalArtifactBytes >= 1L) { "maxTotalArtifactBytes must be positive" }
    }

    override suspend fun save(record: EvidenceRecord, artifacts: EvidenceArtifacts?): EvidenceRecord = ioMutex.withLock {
        validateRecord(record)
        val current = readRecords().filterNot { it.id == record.id }.toMutableList()

        val persisted = if (artifacts != null) {
            val frameSha = sha256(artifacts.frameJpeg)
            val vehicleSha = sha256(artifacts.vehicleCropJpeg)
            val plateSha = artifacts.plateCropJpeg?.let(::sha256)
            writeArtifact(record.id, "frame", frameSha, artifacts.frameJpeg)
            writeArtifact(record.id, "vehicle", vehicleSha, artifacts.vehicleCropJpeg)
            if (artifacts.plateCropJpeg != null) {
                writeArtifact(record.id, "plate", requireNotNull(plateSha), artifacts.plateCropJpeg)
            }
            record.copy(
                frameSha256 = frameSha,
                vehicleCropSha256 = vehicleSha,
                plateCropSha256 = plateSha,
            )
        } else record

        current += persisted
        val retained = enforceRetention(current.sortedByDescending { it.createdAtMs })
        writeRecords(retained)
        return persisted
    }

    override suspend fun list(): List<EvidenceRecord> = ioMutex.withLock {
        readRecords()
    }

    override suspend fun clear(): Unit = ioMutex.withLock {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Unable to delete evidence store: ${file.absolutePath}")
        }
        artifactDir.listFiles()?.forEach { child ->
            if (!child.delete()) {
                throw IllegalStateException("Unable to delete evidence artifact: ${child.absolutePath}")
            }
        }
    }

    /** Reads a persisted artifact only after its SHA-256 reference has been validated. */
    suspend fun readArtifact(record: EvidenceRecord, kind: ArtifactKind): ByteArray? = ioMutex.withLock {
        val hash = when (kind) {
            ArtifactKind.FRAME -> record.frameSha256
            ArtifactKind.VEHICLE -> record.vehicleCropSha256
            ArtifactKind.PLATE -> record.plateCropSha256
        } ?: return@withLock null
        val artifact = artifactFile(record.id, kind.name.lowercase(), hash)
        if (!artifact.isFile) return@withLock null
        val bytes = artifact.readBytes()
        require(sha256(bytes).equals(hash, ignoreCase = true)) {
            "Evidence artifact hash mismatch: ${artifact.name}"
        }
        bytes
    }

    enum class ArtifactKind { FRAME, VEHICLE, PLATE }

    private fun readRecords(): List<EvidenceRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val record = fromJson(array.getJSONObject(i))
                    validateRecord(record)
                    validateArtifactReferences(record)
                    add(record)
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (error: Throwable) {
            throw IllegalStateException("Evidence store is unreadable: ${file.absolutePath}", error)
        }
    }

    private fun enforceRetention(records: List<EvidenceRecord>): List<EvidenceRecord> {
        val retained = ArrayList<EvidenceRecord>(min(records.size, maxRecords))
        var totalBytes = 0L
        for (record in records.sortedByDescending { it.createdAtMs }) {
            if (retained.size >= maxRecords) continue
            val bytes = artifactBytes(record)
            if (retained.isNotEmpty() && totalBytes + bytes > maxTotalArtifactBytes) continue
            retained += record
            totalBytes += bytes
        }

        val keep = retained.asSequence().map { sanitize(it.id) }.toSet()
        artifactDir.listFiles()?.forEach { child ->
            val ownerId = ownerIdFromArtifactName(child.name)
            if (ownerId != null && ownerId !in keep && !child.delete()) {
                throw IllegalStateException("Unable to remove stale evidence artifact: ${child.absolutePath}")
            }
        }
        return retained
    }

    private fun artifactBytes(record: EvidenceRecord): Long {
        var total = 0L
        val artifacts = buildList {
            record.frameSha256?.let { add(it to "frame") }
            record.vehicleCropSha256?.let { add(it to "vehicle") }
            record.plateCropSha256?.let { add(it to "plate") }
        }
        for ((hash, kind) in artifacts) {
            val artifact = artifactFile(record.id, kind, hash)
            if (!artifact.exists()) {
                throw IllegalStateException("Evidence artifact missing for ${record.id}: ${artifact.name}")
            }
            total += artifact.length()
        }
        return total
    }

    private fun writeArtifact(id: String, kind: String, sha256: String, bytes: ByteArray) {
        val target = artifactFile(id, kind, sha256)
        val temporary = File(target.parentFile, target.name + ".tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveReplace(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeRecords(records: List<EvidenceRecord>) {
        val temporary = file.resolveSibling("$FILE_NAME.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(JSONArray().apply { records.forEach { put(toJson(it)) } }.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveReplace(temporary, file)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveReplace(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        try {
            java.nio.file.Files.move(
                sourcePath,
                targetPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                sourcePath,
                targetPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
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
        require(record.measuredSpeedKmh.isFinite() && record.measuredSpeedKmh >= 0.0) { "Evidence speed must be finite and non-negative" }
        require(record.thresholdKmh.isFinite() && record.thresholdKmh > 0.0) { "Evidence threshold must be finite and positive" }
        require(record.confidence.isFinite() && record.confidence in 0f..1f) { "Evidence confidence must be within [0,1]" }
        require(record.trackId > 0L) { "Evidence trackId must be positive" }
        require(record.detectorModel.isNotBlank()) { "Evidence detectorModel must not be blank" }
        require(record.tracker.isNotBlank()) { "Evidence tracker must not be blank" }
        require(record.createdAtMs > 0L) { "Evidence createdAtMs must be positive" }
        listOf(record.frameSha256, record.vehicleCropSha256, record.plateCropSha256).forEach { hash ->
            if (hash != null) require(hash.matches(SHA256_REGEX)) { "Invalid evidence SHA-256" }
        }
    }

    private fun validateArtifactReferences(record: EvidenceRecord) {
        val artifacts = buildList {
            record.frameSha256?.let { add(it to "frame") }
            record.vehicleCropSha256?.let { add(it to "vehicle") }
            record.plateCropSha256?.let { add(it to "plate") }
        }
        for ((hash, kind) in artifacts) {
            val artifact = artifactFile(record.id, kind, hash)
            require(artifact.isFile) { "Missing evidence artifact: ${artifact.name}" }
            require(sha256(artifact.readBytes()).equals(hash, ignoreCase = true)) {
                "Evidence artifact hash mismatch: ${artifact.name}"
            }
        }
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
        put("frameSha256", record.frameSha256 ?: JSONObject.NULL)
        put("vehicleCropSha256", record.vehicleCropSha256 ?: JSONObject.NULL)
        put("plateCropSha256", record.plateCropSha256 ?: JSONObject.NULL)
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
        frameSha256 = json.optString("frameSha256").takeUnless { it.isBlank() || it == "null" },
        vehicleCropSha256 = json.optString("vehicleCropSha256").takeUnless { it.isBlank() || it == "null" },
        plateCropSha256 = json.optString("plateCropSha256").takeUnless { it.isBlank() || it == "null" },
    )

    private fun artifactFile(id: String, kind: String, sha256: String): File {
        val safeId = sanitize(id)
        val safeKind = sanitize(kind)
        return File(artifactDir, "${safeId}_${safeKind}_$sha256.jpg")
    }

    private fun ownerIdFromArtifactName(name: String): String? =
        ARTIFACT_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9.-]"), "_").take(64)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILE_NAME = "traffic_evidence.json"
        const val ARTIFACT_DIR = "traffic_evidence"
        val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        val ARTIFACT_NAME_REGEX = Regex("^(.+)_(?:frame|vehicle|plate)_[0-9a-fA-F]{64}\\.jpg$")
    }
}

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
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.min

/**
 * Durable local evidence vault with metadata plus bounded JPEG artifacts.
 *
 * Artifacts are content-addressed by SHA-256 and published before the metadata index is replaced.
 * Metadata and artifact operations are serialized, and orphaned/unreferenced artifacts are pruned.
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
        val previous = readRecords()
        val current = previous.filterNot { it.id == record.id }.toMutableList()
        val newlyPublished = mutableListOf<String>()

        try {
            val persisted = if (artifacts != null) {
                val frameSha = sha256(artifacts.frameJpeg)
                val vehicleSha = sha256(artifacts.vehicleCropJpeg)
                val plateSha = artifacts.plateCropJpeg?.let(::sha256)
                writeArtifact(frameSha, "frame", artifacts.frameJpeg)
                newlyPublished += artifactName(frameSha, "frame")
                writeArtifact(vehicleSha, "vehicle", artifacts.vehicleCropJpeg)
                newlyPublished += artifactName(vehicleSha, "vehicle")
                if (plateSha != null) {
                    writeArtifact(plateSha, "plate", artifacts.plateCropJpeg!!)
                    newlyPublished += artifactName(plateSha, "plate")
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
            return@withLock persisted
        } catch (error: Throwable) {
            val referencedByPrevious = referencedArtifactNames(previous)
            newlyPublished.forEach { name ->
                if (name !in referencedByPrevious) File(artifactDir, name).delete()
            }
            throw error
        }
    }

    override suspend fun list(): List<EvidenceRecord> = ioMutex.withLock {
        readRecords()
    }

    override suspend fun clear() = ioMutex.withLock {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Unable to delete evidence store: ${file.absolutePath}")
        }
        artifactDir.listFiles()?.forEach { child ->
            if (!child.delete()) throw IllegalStateException("Unable to delete evidence artifact: ${child.absolutePath}")
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
                    verifyReferencedArtifacts(record)
                    add(record)
                }
            }.sortedByDescending { it.createdAtMs }
        } catch (error: Throwable) {
            throw IllegalStateException("Evidence store is unreadable or corrupted: ${file.absolutePath}", error)
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

        val keepFiles = referencedArtifactNames(retained)
        artifactDir.listFiles()?.forEach { child ->
            if (child.name !in keepFiles) child.delete()
        }
        return retained
    }

    private fun artifactBytes(record: EvidenceRecord): Long =
        listOf(
            artifactFile(record.frameSha256, "frame"),
            artifactFile(record.vehicleCropSha256, "vehicle"),
            artifactFile(record.plateCropSha256, "plate"),
        ).filterNotNull().sumOf { it.length() }

    private fun writeArtifact(sha: String, kind: String, bytes: ByteArray) {
        val target = artifactFile(sha, kind) ?: error("Invalid artifact hash")
        val temporary = File(target.parentFile, target.name + ".tmp")
        if (target.exists()) {
            verifyFileHash(target, sha)
            return
        }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            publish(temporary, target)
            verifyFileHash(target, sha)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeRecords(records: List<EvidenceRecord>) {
        val temporary = file.resolveSibling("$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                val bytes = JSONArray().apply { records.forEach { put(toJson(it)) } }.toString().toByteArray(Charsets.UTF_8)
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            publish(temporary, file)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun publish(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    private fun verifyReferencedArtifacts(record: EvidenceRecord) {
        verifyOptionalArtifact(record.frameSha256, "frame")
        verifyOptionalArtifact(record.vehicleCropSha256, "vehicle")
        verifyOptionalArtifact(record.plateCropSha256, "plate")
    }

    private fun verifyOptionalArtifact(sha: String?, kind: String) {
        if (sha == null) return
        val target = artifactFile(sha, kind) ?: error("Invalid artifact hash")
        if (!target.isFile) throw IllegalStateException("Missing evidence artifact: ${target.name}")
        verifyFileHash(target, sha)
    }

    private fun verifyFileHash(file: File, expectedSha: String) {
        val actual = sha256(file.readBytes())
        if (!actual.equals(expectedSha, ignoreCase = true)) {
            throw IllegalStateException("Evidence artifact hash mismatch: ${file.name}")
        }
    }

    private fun referencedArtifactNames(records: List<EvidenceRecord>): Set<String> = buildSet {
        records.forEach { record ->
            record.frameSha256?.let { add(artifactName(it, "frame")) }
            record.vehicleCropSha256?.let { add(artifactName(it, "vehicle")) }
            record.plateCropSha256?.let { add(artifactName(it, "plate")) }
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

    private fun artifactFile(sha: String?, kind: String): File? = sha?.let { File(artifactDir, artifactName(it, kind)) }

    private fun artifactName(sha: String, kind: String): String = "${sha.lowercase()}_${kind}.jpg"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(FILE_HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FILE_NAME = "traffic_evidence.json"
        const val ARTIFACT_DIR = "traffic_evidence"
        const val FILE_HASH_BUFFER_SIZE = 64 * 1024
        val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

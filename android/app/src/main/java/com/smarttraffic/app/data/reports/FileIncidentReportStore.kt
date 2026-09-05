package com.smarttraffic.app.data.reports

import android.content.Context
import com.smarttraffic.app.domain.analysis.IncidentReport
import com.smarttraffic.app.domain.analysis.IncidentReportStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class FileIncidentReportStore(
    context: Context,
    private val maxRecords: Int = 256,
) : IncidentReportStore {
    private val file = context.getFileStreamPath(FILE_NAME)
    private val mutex = Mutex()

    init {
        require(maxRecords >= 1)
    }

    override suspend fun save(report: IncidentReport): IncidentReport = mutex.withLock {
        validate(report)
        val persisted = if (report.id.isBlank()) report.copy(id = UUID.randomUUID().toString()) else report
        validate(persisted)
        val records = readInternal().filterNot { it.id == persisted.id }.toMutableList()
        records += persisted
        writeInternal(records.sortedByDescending { it.createdAtMs }.take(maxRecords))
        persisted
    }

    override suspend fun list(): List<IncidentReport> = mutex.withLock {
        readInternal().sortedByDescending { it.createdAtMs }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Unable to clear incident reports: ${file.absolutePath}")
        }
    }

    private fun readInternal(): List<IncidentReport> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val report = fromJson(array.getJSONObject(index))
                    validate(report)
                    add(report)
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException("Incident report store is unreadable: ${file.absolutePath}", error)
        }
    }

    private fun writeInternal(reports: List<IncidentReport>) {
        val temporary = file.resolveSibling("$FILE_NAME.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(JSONArray().apply { reports.forEach { put(toJson(it)) } }.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validate(report: IncidentReport) {
        require(report.id.isNotBlank()) { "Incident report id must not be blank" }
        require(report.type.isNotBlank()) { "Incident report type must not be blank" }
        require(report.location.isNotBlank()) { "Incident report location must not be blank" }
        require(report.priority in PRIORITIES) { "Unknown incident report priority: ${report.priority}" }
        require(report.details.isNotBlank()) { "Incident report details must not be blank" }
        require(report.createdAtMs > 0L) { "Incident report createdAtMs must be positive" }
    }

    private fun toJson(report: IncidentReport): JSONObject = JSONObject().apply {
        put("id", report.id)
        put("type", report.type)
        put("location", report.location)
        put("plate", report.plate ?: JSONObject.NULL)
        put("priority", report.priority)
        put("details", report.details)
        put("createdAtMs", report.createdAtMs)
    }

    private fun fromJson(json: JSONObject): IncidentReport = IncidentReport(
        id = json.getString("id"),
        type = json.getString("type"),
        location = json.getString("location"),
        plate = json.optString("plate").takeUnless { it.isBlank() || it == "null" },
        priority = json.getString("priority"),
        details = json.getString("details"),
        createdAtMs = json.getLong("createdAtMs"),
    )

    private companion object {
        const val FILE_NAME = "incident_reports.json"
        val PRIORITIES = setOf("Low", "Normal", "High")
    }
}

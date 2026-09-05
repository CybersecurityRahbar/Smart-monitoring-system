package com.smarttraffic.app.domain.analysis

data class IncidentReport(
    val id: String,
    val type: String,
    val location: String,
    val plate: String?,
    val priority: String,
    val details: String,
    val createdAtMs: Long,
)

interface IncidentReportStore {
    suspend fun save(report: IncidentReport): IncidentReport
    suspend fun list(): List<IncidentReport>
    suspend fun clear()
}

package com.smarttraffic.app.domain.analysis

/** Persistence contract for validated camera calibration profiles. */
interface CalibrationStore {
    suspend fun save(profile: CalibrationProfile): CalibrationProfile
    suspend fun get(id: String): CalibrationProfile?
    suspend fun list(): List<CalibrationProfile>
    suspend fun delete(id: String)
}

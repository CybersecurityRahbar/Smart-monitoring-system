package com.smarttraffic.app.data.analysis

import android.content.Context
import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.CalibrationStore
import org.json.JSONArray
import org.json.JSONObject

/** Durable local store for versioned calibration profiles. */
class FileCalibrationStore(context: Context) : CalibrationStore {
    private val file = context.getFileStreamPath(FILE_NAME)

    override suspend fun save(profile: CalibrationProfile): CalibrationProfile {
        require(
            com.smarttraffic.app.domain.analysis.CalibrationValidator.validate(profile).accepted,
        ) { "Only structurally valid calibration profiles may be persisted" }
        val profiles = readProfiles().toMutableList()
        profiles.removeAll { it.id == profile.id }
        profiles += profile
        file.writeText(JSONArray().apply { profiles.forEach { put(toJson(it)) } }.toString())
        return profile
    }

    override suspend fun get(id: String): CalibrationProfile? =
        readProfiles().firstOrNull { it.id == id }

    override suspend fun list(): List<CalibrationProfile> =
        readProfiles().sortedByDescending { it.version }

    override suspend fun delete(id: String) {
        val remaining = readProfiles().filterNot { it.id == id }
        if (remaining.isEmpty()) file.delete()
        else file.writeText(JSONArray().apply { remaining.forEach { put(toJson(it)) } }.toString())
    }

    private fun readProfiles(): List<CalibrationProfile> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return buildList(array.length()) {
            for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }

    private fun toJson(p: CalibrationProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("imageWidth", p.imageWidth)
        put("imageHeight", p.imageHeight)
        put("homography", JSONArray().apply { p.homography.forEach(::put) })
        put("intrinsicMatrix", p.intrinsicMatrix?.let { JSONArray().apply { it.forEach(::put) } } ?: JSONObject.NULL)
        put("distortionCoefficients", p.distortionCoefficients?.let { JSONArray().apply { it.forEach(::put) } } ?: JSONObject.NULL)
        put("reprojectionErrorPixels", p.reprojectionErrorPixels ?: JSONObject.NULL)
        put("reprojectionErrorTargetUnits", p.reprojectionErrorTargetUnits ?: JSONObject.NULL)
        put("version", p.version)
        put("homographyInlierCount", p.homographyInlierCount ?: JSONObject.NULL)
        put("homographyInlierRatio", p.homographyInlierRatio ?: JSONObject.NULL)
    }

    private fun fromJson(j: JSONObject): CalibrationProfile = CalibrationProfile(
        id = j.getString("id"),
        imageWidth = j.getInt("imageWidth"),
        imageHeight = j.getInt("imageHeight"),
        homography = j.getJSONArray("homography").let { a -> List(a.length()) { a.getDouble(it) } },
        intrinsicMatrix = j.optJSONArray("intrinsicMatrix")?.let { a -> List(a.length()) { a.getDouble(it) } },
        distortionCoefficients = j.optJSONArray("distortionCoefficients")?.let { a -> List(a.length()) { a.getDouble(it) } },
        reprojectionErrorPixels = j.optDouble("reprojectionErrorPixels", Double.NaN).takeUnless(Double::isNaN),
        reprojectionErrorTargetUnits = j.optDouble("reprojectionErrorTargetUnits", Double.NaN).takeUnless(Double::isNaN),
        version = j.optInt("version", 1),
        homographyInlierCount = if (j.isNull("homographyInlierCount")) null else j.optInt("homographyInlierCount"),
        homographyInlierRatio = j.optDouble("homographyInlierRatio", Double.NaN).takeUnless(Double::isNaN),
    )

    private companion object { const val FILE_NAME = "camera_calibrations.json" }
}

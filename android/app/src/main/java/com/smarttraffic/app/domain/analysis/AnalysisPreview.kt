package com.smarttraffic.app.domain.analysis

import android.graphics.Bitmap

data class RadarBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    init {
        require(minX.isFinite() && maxX.isFinite() && minY.isFinite() && maxY.isFinite())
        require(maxX > minX && maxY > minY)
    }
}

data class AnalysisPreviewFrame(
    val frame: AnalysisFrame,
    val bitmap: Bitmap,
    val detections: List<Detection>,
    val tracks: List<Track>,
    val speedEstimates: Map<Long, SpeedEstimate>,
    val calibrated: Boolean,
    /** Fixed coordinate viewport used by the radar; it must not be recomputed from current tracks. */
    val radarBounds: RadarBounds? = null,
    /** Local recorded-video URI used by the independent playback surface; null for images/live. */
    val videoUri: String? = null,
)

fun interface AnalysisPreviewObserver {
    suspend fun onFrame(preview: AnalysisPreviewFrame)
}

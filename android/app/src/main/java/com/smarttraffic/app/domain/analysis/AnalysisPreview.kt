package com.smarttraffic.app.domain.analysis

import android.graphics.Bitmap

data class AnalysisPreviewFrame(
    val frame: AnalysisFrame,
    val bitmap: Bitmap,
    val detections: List<Detection>,
    val tracks: List<Track>,
    val speedEstimates: Map<Long, SpeedEstimate>,
    val calibrated: Boolean,
)

fun interface AnalysisPreviewObserver {
    suspend fun onFrame(preview: AnalysisPreviewFrame)
}

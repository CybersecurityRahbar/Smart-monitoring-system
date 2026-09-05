package com.smarttraffic.app.domain.analysis

import android.graphics.Bitmap

data class AnalysisPreviewFrame(
    val frame: AnalysisFrame,
    val bitmap: Bitmap,
    val detections: List<Detection>,
    val tracks: List<Track>,
    val speedEstimates: Map<Long, SpeedEstimate>,
    val calibrated: Boolean,
    /** Local recorded-video URI used by the independent playback surface; null for images/live. */
    val videoUri: String? = null,
)

fun interface AnalysisPreviewObserver {
    suspend fun onFrame(preview: AnalysisPreviewFrame)
}

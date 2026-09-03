package com.smarttraffic.app.data.vision

import android.graphics.Bitmap
import com.smarttraffic.app.data.tracking.AppearanceSignature
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.ObjectDetector

/** Adds a deterministic image-derived appearance signature without changing detector geometry. */
class AppearanceAugmentingDetector(
    private val delegate: ObjectDetector,
) : ObjectDetector {
    override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> {
        val detections = delegate.detect(frame, timestampMs, frameIndex)
        val bitmap = frame as? Bitmap ?: return detections
        return detections.map { detection ->
            if (detection.appearanceSignature != null) detection
            else detection.copy(appearanceSignature = AppearanceSignature.fromBitmap(bitmap, detection))
        }
    }
}

package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.AnalysisPipelineRunner
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.MediaSource
import com.smarttraffic.app.domain.analysis.ObjectDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCalibrationSafetyTest {
    @Test
    fun missingCalibrationNeverEnablesPhysicalProjectionWhenValidationIsRelaxed() = runBlocking {
        val source = OneFrameSource()
        val detector = object : ObjectDetector {
            override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> = emptyList()
        }

        val result = AnalysisPipelineRunner(detector, com.smarttraffic.app.data.tracking.ByteTrack())
            .run(
                source,
                AnalysisConfig(
                    useGroundPlane = true,
                    requireValidatedCalibration = false,
                ),
            )

        assertTrue(result.speedEstimates.isEmpty())
        assertTrue(result.metrics.timestampPrecision == result.source.timestampPrecision)
    }

    private class OneFrameSource : FrameSource {
        private var emitted = false
        override val source = MediaSource("test", "synthetic://one", timestampPrecision = com.smarttraffic.app.domain.analysis.FrameTimestampPrecision.EXACT_SOURCE_CLOCK)
        override suspend fun nextFrame(): AnalysisFrame? {
            if (emitted) return null
            emitted = true
            return AnalysisFrame(0L, 0L, Any(), 1920, 1080)
        }
        override suspend fun close() = Unit
    }
}

package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.AnalysisPipelineRunner
import com.smarttraffic.app.domain.analysis.ByteTrack
import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.ObjectDetector
import com.smarttraffic.app.domain.analysis.SpeedEstimateMode
import com.smarttraffic.app.domain.analysis.SpeedRejectionReason
import com.smarttraffic.app.domain.analysis.SyntheticFrameSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficReliabilityTest {
    @Test
    fun pipelineRefusesUnvalidatedCalibrationForPhysicalSpeed() = runBlocking {
        val detector = object : ObjectDetector {
            override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> =
                listOf(detection(frameIndex, frameIndex * 10f, confidence = 0.95f, width = 40f))
        }
        val source = SyntheticFrameSource(
            (0L..19L).map { index -> AnalysisFrame(index, index * 100L, index, 1920, 1080) },
            FrameTimestampPrecision.EXACT_SOURCE_CLOCK,
        )
        val result = AnalysisPipelineRunner(detector, ByteTrack()).run(source, AnalysisConfig(
            minimumSpeedSamples = 8,
            minimumTrackDurationMs = 500L,
            calibration = CalibrationProfile(
                id = "unvalidated", imageWidth = 1920, imageHeight = 1080,
                homography = listOf(0.01, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            ),
        ))
        assertTrue(result.speedEstimates.isNotEmpty())
        assertEquals(SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE, result.speedEstimates.values.single().mode)
        assertEquals(0L, result.metrics.rejectedSpeedEstimates)
        assertEquals(SpeedRejectionReason.CALIBRATION_INVALID, result.speedRejectionReasons[1L])
    }

    @Test
    fun pipelineBlocksPhysicalSpeedForRequestedSampleTimestamps() = runBlocking {
        val detector = object : ObjectDetector {
            override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> =
                listOf(detection(frameIndex, frameIndex * 10f, confidence = 0.95f, width = 40f))
        }
        val source = SyntheticFrameSource(
            (0L..19L).map { index -> AnalysisFrame(index, index * 100L, index, 1920, 1080) },
            FrameTimestampPrecision.REQUESTED_SAMPLE_TIME,
        )
        val result = AnalysisPipelineRunner(detector, ByteTrack()).run(source, validCalibration())
        assertEquals(FrameTimestampPrecision.REQUESTED_SAMPLE_TIME, result.metrics.timestampPrecision)
        assertTrue(result.speedEstimates.isNotEmpty())
        assertEquals(SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE, result.speedEstimates.values.single().mode)
        assertEquals(0L, result.metrics.rejectedSpeedEstimates)
        assertEquals(SpeedRejectionReason.TIMESTAMP_INVALID, result.speedRejectionReasons[1L])
    }

    private fun validCalibration(): AnalysisConfig = AnalysisConfig(
        minimumSpeedSamples = 8,
        minimumTrackDurationMs = 500L,
        calibration = CalibrationProfile(
            id = "test", imageWidth = 1920, imageHeight = 1080,
            homography = listOf(
                0.01, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
            reprojectionErrorPixels = 0.0,
            homographyInlierCount = 4,
            homographyInlierRatio = 1.0,
        ),
    )
}

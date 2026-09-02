package com.smarttraffic.app

import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.AnalysisPipelineRunner
import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.GroundPoint
import com.smarttraffic.app.domain.analysis.HomographyEstimator
import com.smarttraffic.app.domain.analysis.MediaSource
import com.smarttraffic.app.domain.analysis.ObjectDetector
import com.smarttraffic.app.domain.analysis.RobustSpeedEstimator
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficReliabilityTest {
    @Test
    fun robustSpeedRejectsSingleBadPositionAndRecoversOneMeterPerSecond() {
        val observations = (0 until 20).map { i ->
            val trueX = i * 0.1
            val noise = if (i == 11) 8.0 else if (i % 2 == 0) 0.01 else -0.01
            TrackObservation(
                frameIndex = i.toLong(),
                timestampMs = i * 100L,
                detection = detection(i.toLong(), i * 0.1f),
                groundPoint = GroundPoint(trueX + noise, 0.0, trueX, 0.0),
            )
        }

        val result = RobustSpeedEstimator(
            minimumSamples = 8,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 100.0,
        ).estimate(observations)

        assertTrue(result != null)
        assertTrue("expected speed near 3.6 km/h, was ${result?.kilometersPerHour}",
            kotlin.math.abs(result!!.kilometersPerHour - 3.6) < 0.35)
        assertTrue(result.positionResidualMeters != null)
        assertTrue(result.errorKmh != null)
    }

    @Test
    fun stationaryJitterDoesNotBecomeLargePhysicalSpeed() {
        val observations = (0 until 16).map { i ->
            TrackObservation(
                frameIndex = i.toLong(),
                timestampMs = i * 100L,
                detection = detection(i.toLong(), 0f),
                groundPoint = GroundPoint(
                    if (i % 2 == 0) 0.01 else -0.01,
                    if (i % 3 == 0) 0.01 else -0.01,
                    0.0,
                    0.0,
                ),
            )
        }

        val result = RobustSpeedEstimator(minimumSamples = 8, minimumDurationMs = 500L)
            .estimate(observations)

        assertTrue(result != null)
        assertTrue("stationary jitter produced ${result!!.kilometersPerHour} km/h",
            result.kilometersPerHour < 2.0)
    }

    @Test
    fun homographyRansacRejectsOutlierAndRecoversTransform() {
        val source = listOf(
            HomographyEstimator.Point(0.0, 0.0),
            HomographyEstimator.Point(10.0, 0.0),
            HomographyEstimator.Point(10.0, 10.0),
            HomographyEstimator.Point(0.0, 10.0),
            HomographyEstimator.Point(4.0, 2.0),
            HomographyEstimator.Point(8.0, 6.0),
            HomographyEstimator.Point(2.0, 7.0),
            HomographyEstimator.Point(6.0, 9.0),
        )
        val target = source.mapIndexed { index, p ->
            if (index == 7) HomographyEstimator.Point(90.0, -30.0)
            else HomographyEstimator.Point(2.0 * p.x + 5.0, 3.0 * p.y + 7.0)
        }

        val result = HomographyEstimator.estimateRansac(
            source,
            target,
            reprojectionThreshold = 0.01,
            iterations = 300,
            minimumInliers = 7,
        )

        assertEquals(7, result.inlierCount)
        assertTrue(result.inlierRatio >= 0.875)
        assertTrue(result.meanError < 0.01)
    }

    @Test
    fun byteTrackPreservesIdentityWhenDetectionOrderChanges() {
        val tracker = ByteTrack()
        val first = listOf(
            detection(0, 0f, classId = 2, confidence = 0.90f),
            detection(0, 40f, classId = 2, confidence = 0.80f),
        )
        tracker.update(first, 0L, 0L)
        val second = listOf(
            detection(1, 40.5f, classId = 2, confidence = 0.80f),
            detection(1, 0.5f, classId = 2, confidence = 0.90f),
        )
        val tracks = tracker.update(second, 1L, 100L)

        assertEquals(2, tracks.size)
        val leftTrack = tracks.first { it.observations.single().detection.left < 10f }
        val rightTrack = tracks.first { it.observations.single().detection.left > 30f }
        assertEquals(1L, leftTrack.id)
        assertEquals(2L, rightTrack.id)
    }

    @Test
    fun byteTrackUsesLowConfidenceSecondStageForRecovery() {
        val tracker = ByteTrack()
        tracker.update(listOf(detection(0, 0f, confidence = 0.95f)), 0L, 0L)
        val recovered = tracker.update(listOf(detection(1, 0.2f, confidence = 0.20f)), 1L, 100L)

        assertEquals(1, recovered.size)
        assertEquals(1L, recovered.single().id)
    }

    @Test
    fun pipelineReportsFrameGapsAndProducesMeasuredSpeed() = runBlocking {
        val frames = (0..19).filter { it != 5 }.map { index ->
            AnalysisFrame(index.toLong(), index * 100L, index, 1920, 1080)
        }
        val detector = object : ObjectDetector {
            override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> =
                listOf(detection(frameIndex, frameIndex * 10f, confidence = 0.90f, width = 40f))
        }
        val source = SyntheticFrameSource(frames)
        val runner = AnalysisPipelineRunner(detector, ByteTrack())
        val result = runner.run(
            source,
            AnalysisConfig(
                trackerInputMinimumConfidence = 0.10f,
                minimumDetectionConfidence = 0.25f,
                minimumSpeedSamples = 8,
                minimumTrackDurationMs = 500L,
                calibration = CalibrationProfile(
                    id = "test",
                    imageWidth = 1920,
                    imageHeight = 1080,
                    homography = listOf(
                        0.01, 0.0, 0.0,
                        0.0, 1.0, 0.0,
                        0.0, 0.0, 1.0,
                    ),
                    reprojectionErrorPixels = 0.0,
                    homographyInlierCount = 4,
                    homographyInlierRatio = 1.0,
                ),
            ),
        )

        assertEquals(19L, result.metrics.framesProcessed)
        assertEquals(1L, result.metrics.droppedFrames)
        assertTrue(result.metrics.inferenceMedianLatencyMs != null)
        assertTrue(result.metrics.inferenceP95LatencyMs != null)
        assertTrue(result.metrics.peakActiveTracks >= 1)
        assertEquals(1, result.speedEstimates.size)
        assertTrue(kotlin.math.abs(result.speedEstimates.values.single().kilometersPerHour - 3.6) < 0.35)
        assertEquals(0L, result.metrics.rejectedSpeedEstimates)
        assertTrue(source.closed)
    }

    @Test
    fun pipelineRefusesUnvalidatedCalibrationForPhysicalSpeed() = runBlocking {
        val detector = object : ObjectDetector {
            override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> =
                listOf(detection(frameIndex, frameIndex * 10f, confidence = 0.95f, width = 40f))
        }
        val source = SyntheticFrameSource((0L..19L).map { index ->
            AnalysisFrame(index, index * 100L, index, 1920, 1080)
        })
        val runner = AnalysisPipelineRunner(detector, ByteTrack())
        val result = runner.run(
            source,
            AnalysisConfig(
                minimumSpeedSamples = 8,
                minimumTrackDurationMs = 500L,
                calibration = CalibrationProfile(
                    id = "unvalidated",
                    imageWidth = 1920,
                    imageHeight = 1080,
                    homography = listOf(0.01, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                ),
            ),
        )

        assertTrue(result.speedEstimates.isEmpty())
        assertEquals(1L, result.metrics.rejectedSpeedEstimates)
    }

    private fun detection(
        frameIndex: Long,
        left: Float,
        classId: Int = 2,
        confidence: Float = 0.90f,
        width: Float = 10f,
    ): Detection = Detection(
        classId = classId,
        className = when (classId) { 2 -> "car"; 3 -> "motorcycle"; 5 -> "bus"; else -> "truck" },
        confidence = confidence,
        left = left,
        top = 0f,
        right = left + width,
        bottom = 100f,
        frameIndex = frameIndex,
        timestampMs = frameIndex * 100L,
    )

    private class SyntheticFrameSource(
        private val frames: List<AnalysisFrame>,
    ) : FrameSource {
        private var cursor = 0
        var closed = false
            private set

        override val source: MediaSource = MediaSource("test", "synthetic://traffic")

        override suspend fun nextFrame(): AnalysisFrame? = frames.getOrNull(cursor++)

        override fun close() {
            closed = true
        }
    }
}

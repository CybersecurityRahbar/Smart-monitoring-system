package com.smarttraffic.app.domain.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ModularAnalysisEngineLifecycleTest {
    @Test
    fun directFrameSourceIsOwnedByCaller() = runBlocking {
        val source = CountingFrameSource()
        val engine = createEngine()

        engine.analyze(source, AnalysisConfig(useGroundPlane = false))

        assertEquals(0, source.closeCount.get())
        source.close()
        assertEquals(1, source.closeCount.get())
    }

    @Test
    fun factoryCreatedFrameSourceIsClosedByMediaSourceOverload() = runBlocking {
        val created = CountingFrameSource()
        val mediaSource = MediaSource(
            id = "factory-test",
            uri = "test://factory",
            width = 320,
            height = 240,
        )
        val engine = ModularAnalysisEngine(
            detector = EmptyDetector(),
            tracker = EmptyTracker(),
            frameSourceFactory = FrameSourceFactory { created },
        )

        val result = engine.analyze(mediaSource, AnalysisConfig(useGroundPlane = false))

        assertTrue(result.source.id == mediaSource.id)
        assertEquals(1, created.closeCount.get())
    }

    private fun createEngine(): ModularAnalysisEngine = ModularAnalysisEngine(
        detector = EmptyDetector(),
        tracker = EmptyTracker(),
    )

    private class EmptyDetector : ObjectDetector {
        override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> = emptyList()
    }

    private class EmptyTracker : MultiObjectTracker {
        override fun update(detections: List<Detection>, frameIndex: Long, timestampMs: Long): List<Track> = emptyList()
        override fun reset() = Unit
    }

    private class CountingFrameSource : FrameSource {
        val closeCount = AtomicInteger(0)
        private var emitted = false

        override val source = MediaSource(
            id = "counting-source",
            uri = "test://counting",
            width = 1,
            height = 1,
        )

        override suspend fun nextFrame(): AnalysisFrame? {
            if (emitted) return null
            emitted = true
            return AnalysisFrame(
                index = 0L,
                timestampMs = 0L,
                payload = Any(),
                width = 1,
                height = 1,
            )
        }

        override suspend fun close() {
            closeCount.incrementAndGet()
        }
    }
}

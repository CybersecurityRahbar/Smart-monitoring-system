package com.smarttraffic.app.domain.analysis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UnifiedAnalysisSessionTest {
    @Test
    fun stopClosesSourceAndRuntimeExactlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = UnifiedAnalysisSession(scope)
        val source = BlockingSource()
        val runtime = CountingCloseable()
        val engine = BlockingEngine()

        try {
            assertTrue(session.start(source, engine, AnalysisConfig(), runtime = runtime))
            assertTrue(engine.started.await())

            session.stop()

            assertEquals(1, source.closeCount.get())
            assertEquals(1, runtime.closeCount.get())
            assertEquals(AnalysisSessionPhase.STOPPED, session.state.value.phase)
        } finally {
            scope.coroutineContext[SupervisorJob]?.cancel()
        }
    }

    @Test
    fun onlyOneAnalysisCanBeActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = UnifiedAnalysisSession(scope)
        val firstSource = BlockingSource()
        val secondSource = BlockingSource()
        val engine = BlockingEngine()

        try {
            assertTrue(session.start(firstSource, engine, AnalysisConfig()))
            assertTrue(engine.started.await())
            assertFalse(session.start(secondSource, engine, AnalysisConfig()))
            session.stop()
            assertEquals(1, firstSource.closeCount.get())
            assertEquals(0, secondSource.closeCount.get())
        } finally {
            scope.coroutineContext[SupervisorJob]?.cancel()
        }
    }

    @Test
    fun resetReturnsSessionToIdleAfterStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = UnifiedAnalysisSession(scope)
        val source = BlockingSource()
        val engine = BlockingEngine()

        try {
            assertTrue(session.start(source, engine, AnalysisConfig()))
            assertTrue(engine.started.await())
            session.stop()
            session.reset()
            assertEquals(AnalysisSessionPhase.IDLE, session.state.value.phase)
            assertEquals(null, session.state.value.result)
        } finally {
            scope.coroutineContext[SupervisorJob]?.cancel()
        }
    }

    private class BlockingEngine : AnalysisEngine {
        val started = CompletableDeferred<Boolean>()

        override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult =
            error("MediaSource overload is not used in this test")

        override suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult {
            started.complete(true)
            awaitCancellation()
        }
    }

    private class BlockingSource : FrameSource {
        val closeCount = AtomicInteger(0)
        override val source = MediaSource("test", "test://source")

        override suspend fun nextFrame(): AnalysisFrame? = awaitCancellation()

        override suspend fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class CountingCloseable : AutoCloseable {
        val closeCount = AtomicInteger(0)

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}

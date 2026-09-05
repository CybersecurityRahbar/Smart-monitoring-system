package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisEngine
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.MediaSource
import com.smarttraffic.app.domain.analysis.UnifiedAnalysisSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UnifiedAnalysisSessionTest {
    private val config = AnalysisConfig()
    private val result = AnalysisResult(source = MediaSource("test", "test://source"))

    @Test
    fun completedSessionClosesSourceAndRuntimeOnceAndCanRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = UnifiedAnalysisSession(scope)
            val source1 = CountingSource()
            val runtime1 = CountingCloseable()
            assertTrue(session.start(source1, ImmediateEngine(result), config, runtime = runtime1))
            session.awaitCompletion()
            assertEquals(1, source1.closeCount.get())
            assertEquals(1, runtime1.closeCount.get())
            assertEquals("COMPLETED", session.state.value.phase.name)

            val source2 = CountingSource()
            val runtime2 = CountingCloseable()
            assertTrue(session.start(source2, ImmediateEngine(result), config, runtime = runtime2))
            session.awaitCompletion()
            assertEquals(1, source2.closeCount.get())
            assertEquals(1, runtime2.closeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activeSessionRejectsSecondStartAndStopClosesCurrentResourcesOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = UnifiedAnalysisSession(scope)
            val gate = CompletableDeferred<Unit>()
            val source1 = CountingSource()
            val runtime1 = CountingCloseable()
            assertTrue(session.start(source1, BlockingEngine(gate, result), config, runtime = runtime1))
            delay(50)

            assertFalse(session.start(CountingSource(), ImmediateEngine(result), config, runtime = CountingCloseable()))
            session.stop()
            assertEquals(1, source1.closeCount.get())
            assertEquals(1, runtime1.closeCount.get())
            assertEquals("Analysis session stopped.", session.state.value.message)
        } finally {
            scope.cancel()
        }
    }

    private class CountingSource : FrameSource {
        val closeCount = AtomicInteger(0)
        override val source = MediaSource("test", "test://source")
        override suspend fun nextFrame(): AnalysisFrame? = null
        override suspend fun close() { closeCount.incrementAndGet() }
    }

    private class CountingCloseable : AutoCloseable {
        val closeCount = AtomicInteger(0)
        override fun close() { closeCount.incrementAndGet() }
    }

    private class ImmediateEngine(private val result: AnalysisResult) : AnalysisEngine {
        override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult = result
        override suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult = result.copy(source = source.source)
    }

    private class BlockingEngine(
        private val gate: CompletableDeferred<Unit>,
        private val result: AnalysisResult,
    ) : AnalysisEngine {
        override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult = result
        override suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult {
            try {
                gate.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            return result.copy(source = source.source)
        }
    }
}

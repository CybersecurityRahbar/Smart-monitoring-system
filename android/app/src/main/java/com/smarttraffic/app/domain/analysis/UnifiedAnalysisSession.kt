package com.smarttraffic.app.domain.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lifecycle phases for every local/live analysis execution. */
enum class AnalysisSessionPhase {
    IDLE,
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class AnalysisSessionState(
    val phase: AnalysisSessionPhase = AnalysisSessionPhase.IDLE,
    val message: String? = null,
    val accelerator: String? = null,
    val preview: AnalysisPreviewFrame? = null,
    val result: AnalysisResult? = null,
)

/**
 * Single lifecycle owner for a real traffic-analysis execution.
 *
 * Local video, ESP32 MJPEG and future camera sources all enter the same session contract. The
 * session owns the execution Job and source close/cancellation boundary; callers only supply a
 * configured engine, source and configuration.
 */
class UnifiedAnalysisSession(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AnalysisSessionState())
    val state: StateFlow<AnalysisSessionState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeSource: FrameSource? = null
    private var activeCloseable: AutoCloseable? = null

    suspend fun start(
        source: FrameSource,
        engine: AnalysisEngine,
        config: AnalysisConfig,
        accelerator: String? = null,
        runtime: AutoCloseable? = null,
    ): Boolean = mutex.withLock {
        if (activeJob?.isActive == true) return@withLock false

        activeSource = source
        activeCloseable = runtime
        _state.value = AnalysisSessionState(
            phase = AnalysisSessionPhase.STARTING,
            message = "Starting shared traffic-analysis session…",
            accelerator = accelerator,
        )

        activeJob = scope.launch {
            _state.value = AnalysisSessionState(
                phase = AnalysisSessionPhase.RUNNING,
                message = "Running the shared detector, tracker, geometry and radar pipeline…",
                accelerator = accelerator,
            )
            try {
                val result = engine.analyze(source, config)
                _state.value = AnalysisSessionState(
                    phase = AnalysisSessionPhase.COMPLETED,
                    message = "Analysis session completed.",
                    accelerator = accelerator,
                    preview = _state.value.preview,
                    result = result,
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(
                    phase = AnalysisSessionPhase.STOPPED,
                    message = "Analysis session stopped.",
                )
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    phase = AnalysisSessionPhase.FAILED,
                    message = error.message ?: error::class.java.simpleName,
                )
            } finally {
                runCatching { source.close() }
                runCatching { runtime?.close() }
                mutex.withLock {
                    activeJob = null
                    activeSource = null
                    activeCloseable = null
                }
            }
        }
        true
    }

    fun publishPreview(frame: AnalysisPreviewFrame) {
        _state.value = _state.value.copy(
            phase = AnalysisSessionPhase.RUNNING,
            preview = frame,
        )
    }

    suspend fun stop() = mutex.withLock {
        val job = activeJob ?: return@withLock
        runCatching { activeSource?.close() }
        job.cancel()
        activeJob = null
        activeSource = null
        runCatching { activeCloseable?.close() }
        activeCloseable = null
        _state.value = _state.value.copy(
            phase = AnalysisSessionPhase.STOPPED,
            message = "Analysis session stopped.",
        )
    }

    suspend fun awaitCompletion() {
        activeJob?.join()
    }

    fun reset() {
        require(activeJob?.isActive != true) { "Cannot reset an active analysis session" }
        _state.value = AnalysisSessionState()
    }
}

package com.smarttraffic.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.UUID

/** Durable marker around high-risk ML/media startup so process death can be diagnosed next launch. */
object AnalysisDiagnostics {
    private const val PREFS = "analysis_diagnostics"
    private const val RUN_ID = "run_id"
    private const val STAGE = "stage"
    private const val TIME = "time_ms"
    private const val MODEL = "model"
    private const val ACCELERATOR = "accelerator"
    private const val MEDIA = "media"
    private const val FRAME_INFO = "frame_info"
    private const val COMPLETED = "completed"

    enum class Stage { IDLE, MODEL_VALIDATE, MODEL_INITIALIZE, MEDIA_OPEN, PIPELINE_START, RUNNING, COMPLETED, FAILED, STOPPED }

    data class Marker(
        val runId: String,
        val stage: Stage,
        val timestampMs: Long,
        val modelId: String?,
        val accelerator: String?,
        val mediaDescription: String?,
        val frameInfo: String?,
        val completed: Boolean,
    )

    data class ProcessExit(val reason: Int, val description: String, val timestampMs: Long)

    fun newRun(context: Context): String {
        val id = UUID.randomUUID().toString()
        mark(context, id, Stage.IDLE, completed = false)
        return id
    }

    fun mark(
        context: Context,
        runId: String,
        stage: Stage,
        modelId: String? = null,
        accelerator: String? = null,
        mediaDescription: String? = null,
        frameInfo: String? = null,
        completed: Boolean = stage == Stage.COMPLETED || stage == Stage.STOPPED,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(RUN_ID, runId)
            .putString(STAGE, stage.name)
            .putLong(TIME, System.currentTimeMillis())
            .putString(MODEL, modelId)
            .putString(ACCELERATOR, accelerator)
            .putString(MEDIA, mediaDescription)
            .putString(FRAME_INFO, frameInfo)
            .putBoolean(COMPLETED, completed)
            .apply()
    }

    fun lastMarker(context: Context): Marker? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = p.getString(STAGE, null)?.let { runCatching { Stage.valueOf(it) }.getOrNull() } ?: return null
        return Marker(
            runId = p.getString(RUN_ID, "") ?: "",
            stage = stage,
            timestampMs = p.getLong(TIME, 0L),
            modelId = p.getString(MODEL, null),
            accelerator = p.getString(ACCELERATOR, null),
            mediaDescription = p.getString(MEDIA, null),
            frameInfo = p.getString(FRAME_INFO, null),
            completed = p.getBoolean(COMPLETED, false),
        )
    }

    fun lastProcessExit(context: Context): ProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val marker = lastMarker(context) ?: return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .asSequence()
            .filter { it.timestamp >= marker.timestampMs }
            .maxByOrNull { it.timestamp }
            ?.let { ProcessExit(it.reason, it.description?.toString().orEmpty(), it.timestamp) }
    }

    fun startupWarning(context: Context): String? {
        val marker = lastMarker(context) ?: return null
        if (marker.completed || marker.stage == Stage.IDLE) return null
        val exit = lastProcessExit(context) ?: return null
        return "Previous analysis ended during ${marker.stage}: ${exitDescription(exit.reason)} (run ${marker.runId})"
    }

    fun exitDescription(reason: Int): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        when (reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
            android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            else -> "reason-$reason"
        }
    } else "reason-$reason"
}

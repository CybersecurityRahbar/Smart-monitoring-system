package com.smarttraffic.app.core

import com.smarttraffic.app.domain.analysis.UnifiedAnalysisSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped owner of the single active analysis execution.
 * ViewModels observe/control this host instead of owning competing analysis sessions.
 */
class AnalysisHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session: UnifiedAnalysisSession = UnifiedAnalysisSession(scope)
}

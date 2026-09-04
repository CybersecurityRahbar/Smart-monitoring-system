package com.smarttraffic.app

import android.app.Application
import com.smarttraffic.app.core.AnalysisDiagnostics
import com.smarttraffic.app.core.AnalysisHost

class SmartTrafficApplication : Application() {
    val analysisHost = AnalysisHost()
    var previousAnalysisWarning: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        previousAnalysisWarning = AnalysisDiagnostics.startupWarning(this)
    }
}

package com.smarttraffic.app.domain.analysis

/**
 * Session-local process holder used to bridge the existing Android runtime factory and the
 * long-lived ModularAnalysisEngine constructor without changing every ViewModel call site.
 *
 * AnalysisHost serializes analysis sessions, so at most one active pose runtime is expected.
 * The holder is always cleared by the owning runtime on close; analytics remain independent of
 * render-only state and detector identity tracking.
 */
object VehicleKeypointRuntimeHolder {
    @Volatile
    var active: VehicleKeypointEstimator? = null
}

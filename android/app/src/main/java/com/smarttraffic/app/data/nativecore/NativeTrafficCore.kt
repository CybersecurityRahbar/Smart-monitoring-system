package com.smarttraffic.app.data.nativecore

/**
 * JNI facade for performance-sensitive vision math implemented in C++.
 *
 * Native loading is deliberately explicit and lazy. Merely referencing this class must not load a
 * native library during ordinary application startup. Callers can probe [isAvailable] first and
 * only opt into native processing after the capability boundary has succeeded.
 */
object NativeTrafficCore {
    @Volatile
    private var loadState: LoadState = LoadState.NOT_ATTEMPTED

    enum class LoadState { NOT_ATTEMPTED, AVAILABLE, UNAVAILABLE }

    /** Attempts to load the native library once. Safe to call repeatedly. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        when (loadState) {
            LoadState.AVAILABLE -> return true
            LoadState.UNAVAILABLE -> return false
            LoadState.NOT_ATTEMPTED -> Unit
        }

        loadState = try {
            System.loadLibrary("smarttraffic_native")
            LoadState.AVAILABLE
        } catch (_: UnsatisfiedLinkError) {
            LoadState.UNAVAILABLE
        } catch (_: SecurityException) {
            LoadState.UNAVAILABLE
        }
        return loadState == LoadState.AVAILABLE
    }

    fun isAvailable(): Boolean = loadState == LoadState.AVAILABLE || ensureLoaded()

    fun projectHomography(h9: DoubleArray, x: Double, y: Double): DoubleArray? {
        check(ensureLoaded()) { "Native traffic core is unavailable" }
        return nativeProjectHomography(h9, x, y)
    }

    /** Returns [metersPerSecond, confidence, errorKmh, inlierSamples, velocityX, velocityY, residual]. */
    fun estimateRobustSpeed(
        xMeters: DoubleArray,
        yMeters: DoubleArray,
        timestampsMs: LongArray,
        minimumSamples: Int,
    ): DoubleArray? {
        check(ensureLoaded()) { "Native traffic core is unavailable" }
        return nativeEstimateRobustSpeed(xMeters, yMeters, timestampsMs, minimumSamples)
    }

    private external fun nativeProjectHomography(
        h9: DoubleArray,
        x: Double,
        y: Double,
    ): DoubleArray?

    private external fun nativeEstimateRobustSpeed(
        xMeters: DoubleArray,
        yMeters: DoubleArray,
        timestampsMs: LongArray,
        minimumSamples: Int,
    ): DoubleArray?
}

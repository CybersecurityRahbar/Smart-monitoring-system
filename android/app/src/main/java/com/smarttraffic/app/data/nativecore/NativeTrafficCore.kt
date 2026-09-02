package com.smarttraffic.app.data.nativecore

/** JNI facade for performance-sensitive vision math implemented in C++. */
object NativeTrafficCore {
    init {
        System.loadLibrary("smarttraffic_native")
    }

    external fun projectHomography(h9: DoubleArray, x: Double, y: Double): DoubleArray?

    /** Returns [metersPerSecond, confidence, errorKmh, inlierSamples]. */
    external fun estimateRobustSpeed(
        xMeters: DoubleArray,
        yMeters: DoubleArray,
        timestampsMs: LongArray,
        minimumSamples: Int,
    ): DoubleArray?
}

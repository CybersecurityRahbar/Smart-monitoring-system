#include <jni.h>

#include "traffic_core.h"

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_smarttraffic_app_data_nativecore_NativeTrafficCore_projectHomography(
    JNIEnv* env, jclass, jdoubleArray h9, jdouble x, jdouble y) {
    if (h9 == nullptr || env->GetArrayLength(h9) != 9) return nullptr;
    jdouble* values = env->GetDoubleArrayElements(h9, nullptr);
    if (values == nullptr) return nullptr;
    const auto point = smarttraffic::project_homography(values, x, y);
    env->ReleaseDoubleArrayElements(h9, values, JNI_ABORT);

    const jdouble out[2] = {point.x, point.y};
    jdoubleArray result = env->NewDoubleArray(2);
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, 2, out);
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_smarttraffic_app_data_nativecore_NativeTrafficCore_estimateRobustSpeed(
    JNIEnv* env, jclass, jdoubleArray xMeters, jdoubleArray yMeters,
    jlongArray timestampsMs, jint minimumSamples) {
    if (xMeters == nullptr || yMeters == nullptr || timestampsMs == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(xMeters);
    if (env->GetArrayLength(yMeters) != count || env->GetArrayLength(timestampsMs) != count) return nullptr;

    jdouble* xs = env->GetDoubleArrayElements(xMeters, nullptr);
    jdouble* ys = env->GetDoubleArrayElements(yMeters, nullptr);
    jlong* ts = env->GetLongArrayElements(timestampsMs, nullptr);
    if (xs == nullptr || ys == nullptr || ts == nullptr) {
        if (xs != nullptr) env->ReleaseDoubleArrayElements(xMeters, xs, JNI_ABORT);
        if (ys != nullptr) env->ReleaseDoubleArrayElements(yMeters, ys, JNI_ABORT);
        if (ts != nullptr) env->ReleaseLongArrayElements(timestampsMs, ts, JNI_ABORT);
        return nullptr;
    }

    const auto result = smarttraffic::robust_speed(
        xs, ys, reinterpret_cast<const long long*>(ts),
        static_cast<std::size_t>(count),
        static_cast<std::size_t>(minimumSamples < 2 ? 2 : minimumSamples));

    env->ReleaseDoubleArrayElements(xMeters, xs, JNI_ABORT);
    env->ReleaseDoubleArrayElements(yMeters, ys, JNI_ABORT);
    env->ReleaseLongArrayElements(timestampsMs, ts, JNI_ABORT);

    const jdouble out[4] = {
        result.meters_per_second,
        result.confidence,
        result.error_kmh,
        static_cast<jdouble>(result.sample_count),
    };
    jdoubleArray array = env->NewDoubleArray(4);
    if (array != nullptr) env->SetDoubleArrayRegion(array, 0, 4, out);
    return array;
}

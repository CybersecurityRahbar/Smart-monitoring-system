package com.smarttraffic.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smarttraffic.app.data.analysis.AnalysisRuntimeFactory
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.domain.analysis.Detection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level smoke test for the exact high-risk path that previously terminated the app:
 * model asset validation -> LiteRT CPU initialization -> one real inference -> deterministic close.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtStartupSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var runtime: AnalysisRuntimeFactory.DetectorRuntime? = null

    @Before
    fun validateModelContract() {
        val spec = DetectorModelRegistry.requireSpec("yolo26n")
        assertTrue("YOLO26n model asset must be packaged", DetectorModelRegistry.isInstalled(context, spec))
    }

    @Test
    fun cpuDetector_initializes_and_runsOneFrame() = runBlocking {
        val spec = DetectorModelRegistry.requireSpec("yolo26n")
        runtime = AnalysisRuntimeFactory.createDetector(
            context = context,
            modelId = spec.id,
            useAppearanceAssociation = false,
        )

        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            val detections = runtime!!.detector.detect(bitmap, timestampMs = 0L, frameIndex = 0L)
            assertTrue("Detector invocation must complete without throwing", detections is List<Detection>)
            detections.forEach { detection ->
                assertTrue(detection.confidence in 0f..1f)
                assertTrue(detection.right > detection.left)
                assertTrue(detection.bottom > detection.top)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun closeRuntime() {
        runtime?.close()
        runtime = null
    }
}

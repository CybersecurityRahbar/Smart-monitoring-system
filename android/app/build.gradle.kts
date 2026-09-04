import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val yolo26nAsset = layout.projectDirectory.file("src/main/assets/models/yolo26n.tflite").asFile
val yolo26nSha256 = "d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73"

val verifyYolo26nModel by tasks.registering {
    inputs.file(yolo26nAsset)
    doLast {
        check(yolo26nAsset.isFile) {
            "Missing YOLO26n model asset: ${yolo26nAsset.path}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        yolo26nAsset.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(yolo26nSha256, ignoreCase = true)) {
            "Invalid yolo26n.tflite SHA-256: $actual; expected $yolo26nSha256"
        }
        logger.lifecycle("Verified yolo26n.tflite SHA-256: $actual (${yolo26nAsset.length()} bytes)")
    }
}

android {
    namespace = "com.smarttraffic.app"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.smarttraffic.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyYolo26nModel)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Match the currently published Ultralytics Android LiteRT runtime baseline.
    implementation("com.google.ai.edge.litert:litert:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

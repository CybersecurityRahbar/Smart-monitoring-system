# Android CI / APK Workflow

The repository uses GitHub Actions to build and test the Android application automatically.

## What happens on each push or pull request

1. GitHub checks out the repository.
2. JDK 17 is installed.
3. Gradle 9.3.1 is provisioned.
4. The Android debug APK is built from `android/`.
5. JVM unit tests are executed.
6. The generated debug APK is uploaded as a workflow artifact.
7. Test reports are uploaded when available, including on failed builds.

## Where to find the APK

Open the repository's **Actions** tab, select the `Android CI` workflow run, and download the `smart-traffic-debug-apk` artifact.

## Build system

The current Android project uses:
- Android Gradle Plugin 9.1.2
- Kotlin Compose plugin 2.2.10
- Java 17
- Gradle 9.3.1
- compileSdk 37

AGP 9.1 requires at least Gradle 9.3.1, so CI provisions that version explicitly.

## Why there is no cloud service for the APK

GitHub Actions is only the build/verification layer. The application itself remains a normal Android application. Later we can add release workflows for signed APKs, versioned releases, and optional internal testing builds.

## Future CI stages

As the project grows we can add:
- lint/static analysis;
- instrumented UI tests on an Android emulator;
- native C++ build/tests for the vision engine;
- Python algorithm evaluation jobs;
- release APK/AAB generation;
- GitHub Release publishing;
- build status summaries and regression benchmarks.

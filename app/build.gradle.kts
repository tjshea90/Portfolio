plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tj.portfolio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tj.portfolio"
        minSdk = 26
        targetSdk = 36
        versionCode = 55
        versionName = "6.8"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.jks")
            storePassword = "portfolio"
            keyAlias = "portfolio"
            keyPassword = "portfolio"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    /**
     * LINT IS PART OF THE BUILD NOW.
     *
     * Release builds only ever ran `lintVitalRelease`, which checks FATAL-severity issues
     * only. A real NewApi ERROR sat in Storage.kt from v4.7 to v5.7 and no build ever
     * mentioned it, because NewApi is an error rather than a fatal. Run `./gradlew :app:lint`
     * and it now aborts on anything genuine.
     *
     * The disabled checks are the version-upgrade nags, and they are disabled DELIBERATELY:
     * CHECKPOINT.md section 3 pins these libraries to the last releases that compile against
     * compileSdk 36. Every "a newer version is available" here wants compileSdk 37 and AGP
     * 9.1+, which is precisely the change that section says to undo if it ever appears.
     * Leaving the warnings on would train whoever runs lint next to ignore its output.
     */
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf(
            "GradleDependency",          // pinned to compileSdk 36 - see CHECKPOINT.md s.3
            "NewerVersionAvailable",     // same
            "AndroidGradlePluginVersion" // same
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    /**
     * UNIT TESTS. These do NOT affect the release APK - `assembleRelease` never compiles the
     * test source set, and nothing here is a runtime dependency. Robolectric runs the real
     * Android framework classes on the JVM, which is what finally lets data/Db.kt be tested
     * against a REAL SQLite database instead of a Python model of one.
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    /**
     * UNIT TESTS RUN ON THE DEBUG VARIANT ONLY.
     *
     * The Compose tests need `ui-test-manifest`, which supplies the host Activity the test
     * rule launches into. That is a `debugImplementation`, and it MUST stay one - adding it
     * to release would package a test Activity into the shipped APK. So the release variant
     * has no host Activity, and every UiTest failed there with a bare RuntimeException out
     * of Robolectric's instrumentation while the identical debug run passed.
     *
     * Rather than leave `./gradlew :app:test` failing for a reason that has nothing to do
     * with the app - a trap for whoever runs it next - the release unit-test variant is
     * turned off. `:app:test` and `:app:testDebugUnitTest` now mean the same thing.
     */
    androidComponents {
        beforeVariants(selector().withBuildType("release")) { variant ->
            @Suppress("DEPRECATION")
            variant.enableUnitTest = false
        }
    }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // test-only; never packaged into the APK
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    // renders real Compose trees on the JVM, so the screens can be exercised without a device
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Guard against the Kotlin initialisation-order bug that crashed v4.6 on startup: a property
 * declared below `init` does not exist yet while init runs. Wired into the build rather than
 * left as a script someone has to remember, because the second time it shipped was precisely
 * because nothing checked. See checkinit.py.
 */
val checkInitOrder = tasks.register<Exec>("checkInitOrder") {
    workingDir = rootProject.projectDir
    commandLine("python3", "checkinit.py")
}
tasks.named("preBuild") { dependsOn(checkInitOrder) }

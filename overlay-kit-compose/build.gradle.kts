import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "io.github.l2hyunwoo.overlaykit"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources/assets/manifest on the unit-test
            // classpath; createComposeRule() also resolves its host Activity from the manifest.
            isIncludeAndroidResources = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI tests run on the JVM via Robolectric (no emulator). createComposeRule() drives the
    // same TestCoroutineScheduler-backed MainTestClock that advances AnimatedVisibility transitions
    // and LaunchedEffect snapshotFlow collectors deterministically, so enter/exit completion is
    // observable here. The test BOM keeps ui-test aligned with the runtime compose versions.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.material3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    // ui-test-manifest supplies the empty ComponentActivity that createComposeRule() hosts content in.
    debugImplementation(libs.compose.ui.test.manifest)
}

// Publishing skeleton only — coordinates wired, actual release is deferred.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.l2hyunwoo"
            artifactId = "overlay-kit-compose"
            version = "0.1.0-SNAPSHOT"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("overlay-kit-compose")
                description.set("Imperative overlay management for Jetpack Compose.")
                url.set("https://github.com/l2hyunwoo/overlay-kit-compose")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

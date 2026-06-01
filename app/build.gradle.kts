plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)

    id("com.google.gms.google-services")
}

android {
    namespace = "com.cybersiren.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cybersiren.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 31
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {

        includeInApk = false

        includeInBundle = false
    }

    buildTypes {
        debug {

            applicationIdSuffix = ".test"

            resValue("string", "app_name", "CyberSiren")
            resValue("string", "v2v_launcher_label", "CyberSiren")
            ndk {

                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.accompanist.permissions)

    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    implementation(libs.bundles.cryptography)

    implementation(libs.gson)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.nordic.ble)

    implementation(libs.okhttp)

    implementation(libs.gms.location)

    implementation(libs.androidx.security.crypto)

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.car.app:app:1.4.0")

    implementation("com.google.guava:guava:31.1-android")

    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

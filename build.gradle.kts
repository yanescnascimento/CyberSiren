plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false

    id("com.google.gms.google-services") version "4.4.0" apply false
}

tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

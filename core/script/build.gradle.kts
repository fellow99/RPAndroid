plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.managedfloating.core.script"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Node 引擎仅 arm64-v8a
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

dependencies {
    implementation(libs.node.bridge)
    runtimeOnly(libs.node.runtime)
}

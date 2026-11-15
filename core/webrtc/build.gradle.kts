plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.fellow99.rpauto.core.webrtc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // C-6：全工程仅 arm64（Node 引擎同约束）；webrtc AAR 只留 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

dependencies {
    implementation(project(":core:controller"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.webrtc)
}

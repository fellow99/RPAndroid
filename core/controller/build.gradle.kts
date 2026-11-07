plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.managedfloating.core.controller"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

dependencies {
    implementation(project(":core:shell"))
    implementation(libs.kotlinx.coroutines.core)
}

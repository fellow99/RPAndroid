plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.fellow99.RPAndroid.core.shell"
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
    implementation(libs.kotlinx.coroutines.core)
}

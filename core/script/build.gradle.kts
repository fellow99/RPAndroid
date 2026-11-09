plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.fellow99.RPAndroid.core.script"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.node.bridge)
    runtimeOnly(libs.node.runtime)

    testImplementation("junit:junit:4.13.2")
}

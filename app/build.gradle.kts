plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.managedfloating.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.managedfloating.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Node 引擎仅 arm64-v8a
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    // 模块占位依赖
    implementation(project(":core:controller"))
    implementation(project(":core:server"))
    implementation(project(":core:shell"))
    implementation(project(":core:script"))

    // 引擎包从 :core:script 透传；此处再声明一次确保打进 APK
    runtimeOnly(libs.node.runtime)

    implementation(libs.androidx.core.ktx)
}

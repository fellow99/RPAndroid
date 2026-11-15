plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ── 签名材料不入库（对齐 pi-desktop-android 先例）──────────────────────────
// keystore.properties（已 gitignore；模板见 keystore.properties.sample）提供
// storeFile/storePassword/keyAlias/keyPassword；文件缺失 ⇒ release 产生未签名包。
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "top.fellow99.rpauto"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "top.fellow99.rpauto"
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
            // 签名材料本地持有、不入库；GitHub Actions 经 secrets 注入生成 keystore.properties
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
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
        compose = true
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
    implementation(libs.kotlinx.coroutines.android)

    // Compose UI（101-main-ui 里程碑启用）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

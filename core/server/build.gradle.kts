plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.fellow99.rpandroid.core.server"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    implementation(project(":core:controller"))
    implementation(project(":core:shell"))
    implementation(project(":core:webrtc"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.websockets)
    implementation(libs.jackson.module.kotlin)

    testImplementation("junit:junit:4.13.2")
}

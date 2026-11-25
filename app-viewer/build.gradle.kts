plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.mastersoft.velocast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mastersoft.velocast"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":launcher-adb"))
    implementation(project(":designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

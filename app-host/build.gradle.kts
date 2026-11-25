plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.mastersoft.velocast.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mastersoft.velocast.host"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"
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
    implementation(project(":server-core"))
    implementation(project(":launcher-shizuku"))
    implementation(project(":launcher-root"))
    implementation(project(":designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

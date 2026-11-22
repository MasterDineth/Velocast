plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mastersoft.velocast.daemon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mastersoft.velocast.daemon"
        minSdk = 30
        targetSdk = 34
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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.doLast {
        val src = file("build/intermediates/apk/debug/server-debug.apk")
        val dest = file("build/outputs/apk/debug/server-debug.apk")
        if (src.exists()) {
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
    }
}

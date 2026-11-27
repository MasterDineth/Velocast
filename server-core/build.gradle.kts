import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.mastersoft.velocast.servercore"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(project(":protocol"))
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val dexOutputJar = layout.buildDirectory.file("outputs/dex/velocast-server.jar")
val dexExtractDir = layout.buildDirectory.dir("tmp/assembleDexJar/extracted")

tasks.register("assembleDexJar") {
    group = "build"
    description = "Packages :server-core and its runtime classpath into a standalone dex jar."
    dependsOn("syncReleaseLibJars")

    val mainClassesJar = layout.buildDirectory.file(
        "intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"
    )
    val runtimeArtifacts = configurations.named("releaseRuntimeClasspath")
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory

    inputs.file(mainClassesJar)
    inputs.files(runtimeArtifacts)
    outputs.file(dexOutputJar)

    doLast {
        val sdkRoot = sdkDirectory.get().asFile
        val isWindows = System.getProperty("os.name").orEmpty()
            .lowercase(Locale.ROOT)
            .contains("win")
        val d8Name = if (isWindows) "d8.bat" else "d8"
        val buildToolsDir = File(sdkRoot, "build-tools")
        val d8Exe = buildToolsDir.listFiles()
            ?.filter { it.isDirectory && File(it, d8Name).isFile }
            ?.maxByOrNull { it.name }
            ?.let { File(it, d8Name) }
            ?: throw GradleException("Could not find $d8Name under ${buildToolsDir.absolutePath}")
        val androidJar = File(sdkRoot, "platforms/android-36/android.jar")
        if (!androidJar.isFile) {
            throw GradleException("Could not find ${androidJar.absolutePath}")
        }

        val extractRoot = dexExtractDir.get().asFile
        extractRoot.deleteRecursively()
        extractRoot.mkdirs()

        val inputJars = mutableListOf<File>()
        inputJars += mainClassesJar.get().asFile

        var aarIndex = 0
        for (artifact in runtimeArtifacts.get().files) {
            if (artifact.extension.equals("jar", ignoreCase = true)) {
                inputJars += artifact
            } else if (artifact.extension.equals("aar", ignoreCase = true)) {
                val targetDir = File(extractRoot, "aar-${aarIndex++}")
                targetDir.mkdirs()
                ZipFile(artifact).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory &&
                            (
                                entry.name == "classes.jar" ||
                                    (entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))
                                )
                        ) {
                            val outFile = File(targetDir, File(entry.name).name)
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            inputJars += outFile
                        }
                    }
                }
            }
        }

        val outJar = dexOutputJar.get().asFile
        outJar.parentFile.mkdirs()
        if (outJar.exists()) {
            outJar.delete()
        }

        val cmd = mutableListOf(
            d8Exe.absolutePath,
            "--release",
            "--min-api",
            "30",
            "--lib",
            androidJar.absolutePath,
            "--output",
            outJar.absolutePath
        )
        cmd.addAll(inputJars.map { it.absolutePath })

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("d8 failed with exit code $exitCode:\n$output")
        }
    }
}

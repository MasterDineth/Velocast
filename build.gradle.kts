import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

subprojects {
    pluginManager.withPlugin("dev.detekt") {
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            val baselineFile = rootProject.file(
                "config/detekt/baselines/${project.name}-baseline.xml"
            )
            baseline.set(baselineFile)
        }
    }
}

abstract class VerifyModuleDependenciesTask : DefaultTask() {
    @get:Input
    abstract val modulePlugins: MapProperty<String, List<String>>

    @get:Input
    abstract val moduleProjectDeps: MapProperty<String, List<String>>

    @get:Input
    abstract val moduleExternalGroups: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val pluginsByModule = modulePlugins.get()
        val projectDepsByModule = moduleProjectDeps.get()
        val externalGroupsByModule = moduleExternalGroups.get()

        val allowedProjectDeps = mapOf(
            ":protocol" to emptySet(),
            ":server-core" to setOf(":protocol"),
            ":client-core" to setOf(":protocol"),
            ":launcher-adb" to setOf(":protocol", ":client-core"),
            ":launcher-shizuku" to setOf(":protocol"),
            ":launcher-root" to setOf(":protocol"),
            ":designsystem" to emptySet(),
            ":app-viewer" to setOf(":protocol", ":client-core", ":launcher-adb", ":designsystem"),
            ":app-host" to
                setOf(
                    ":protocol",
                    ":server-core",
                    ":launcher-shizuku",
                    ":launcher-root",
                    ":designsystem"
                )
        )

        val violations = mutableListOf<String>()

        val protocolPlugins = pluginsByModule[":protocol"].orEmpty()
        if (protocolPlugins.any { it.startsWith("com.android.") }) {
            violations += ":protocol must not apply any Android plugin (found: $protocolPlugins)"
        }
        val protocolGroups = externalGroupsByModule[":protocol"].orEmpty()
        val forbiddenProtocolGroups = protocolGroups.filter {
            it.startsWith("com.android.") || it.startsWith("androidx.") ||
                it.startsWith("com.google.android.")
        }
        if (forbiddenProtocolGroups.isNotEmpty()) {
            violations +=
                ":protocol must not depend on Android libraries (found: $forbiddenProtocolGroups)"
        }

        val serverCoreGroups = externalGroupsByModule[":server-core"].orEmpty()
        val forbiddenServerCoreGroups = serverCoreGroups.filter {
            it == "androidx.appcompat" ||
                it == "com.google.android.material" ||
                it.startsWith("androidx.compose") ||
                it == "androidx.activity" ||
                it == "androidx.fragment"
        }
        if (forbiddenServerCoreGroups.isNotEmpty()) {
            violations +=
                ":server-core must not depend on UI libraries (found: $forbiddenServerCoreGroups)"
        }

        for ((modulePath, allowed) in allowedProjectDeps) {
            val actual = projectDepsByModule[modulePath].orEmpty().toSet()
            val disallowed = actual - allowed
            if (disallowed.isNotEmpty()) {
                violations +=
                    "$modulePath has disallowed project dependencies: $disallowed (allowed: $allowed)"
            }
        }

        for (launcher in listOf(":launcher-adb", ":launcher-shizuku", ":launcher-root")) {
            val actual = projectDepsByModule[launcher].orEmpty()
            val appDeps = actual.filter { it.startsWith(":app-") }
            if (appDeps.isNotEmpty()) {
                violations += "$launcher must never depend on app modules (found: $appDeps)"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module dependency rule violation(s):\n" +
                    violations.joinToString("\n") { " - $it" }
            )
        }
    }
}

val verifyModuleDependencies = tasks.register<VerifyModuleDependenciesTask>(
    "verifyModuleDependencies"
) {
    group = "verification"
    description = "Verifies module dependency rules from docs/architecture.md."
}

gradle.projectsEvaluated {
    val knownPluginIds = listOf(
        "com.android.application",
        "com.android.library",
        "com.android.kotlin.multiplatform.library",
        "org.jetbrains.kotlin.jvm",
        "org.jetbrains.kotlin.android"
    )

    val pluginsMap = mutableMapOf<String, List<String>>()
    val projectDepsMap = mutableMapOf<String, List<String>>()
    val externalGroupsMap = mutableMapOf<String, List<String>>()

    for (sub in subprojects) {
        pluginsMap[sub.path] = knownPluginIds.filter { sub.pluginManager.hasPlugin(it) }

        val projectDeps = linkedSetOf<String>()
        val externalGroups = linkedSetOf<String>()
        for (cfg in sub.configurations) {
            for (dep in cfg.dependencies) {
                when (dep) {
                    is ProjectDependency -> if (dep.path != sub.path) projectDeps += dep.path
                    is ExternalModuleDependency -> dep.group?.let { externalGroups += it }
                }
            }
        }
        projectDepsMap[sub.path] = projectDeps.toList()
        externalGroupsMap[sub.path] = externalGroups.toList()
    }

    verifyModuleDependencies.configure {
        modulePlugins.set(pluginsMap)
        moduleProjectDeps.set(projectDepsMap)
        moduleExternalGroups.set(externalGroupsMap)
    }
}

tasks.named("check") {
    dependsOn(verifyModuleDependencies)
    dependsOn(tasks.named("spotlessCheck"))
}

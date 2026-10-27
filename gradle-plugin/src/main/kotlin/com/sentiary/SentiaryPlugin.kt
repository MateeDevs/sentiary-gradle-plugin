package com.sentiary

import org.gradle.api.GradleException
import org.gradle.api.IsolatedAction
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.util.GradleVersion
import java.io.File

class SentiaryPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        require(GradleVersion.current() >= GradleVersion.version("9.1.0")) { "Sentiary 2 requires Gradle 9.1.0 or newer" }
        val extension = settings.extensions.create("sentiary", SentiaryExtension::class.java)
        settings.gradle.settingsEvaluated {
            val bindings = listOf("android" to extension.android, "compose" to extension.compose).flatMap { (kind, entries) ->
                entries.map { entry ->
                    require(entry.modulePath.startsWith(":")) { "${entry.name}: modulePath must be absolute" }
                    require(settings.findProject(entry.modulePath) != null) { "Unknown module ${entry.modulePath}" }
                    require(
                        entry.output.isNotBlank() && entry.resourceRoot.isNotBlank(),
                    ) { "${entry.name}: output and resourceRoot are required" }
                    require(entry.sourceSet.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "Invalid sourceSet" }
                    require(!File(entry.resourceRoot).isAbsolute) { "resourceRoot must be relative to the build root" }
                    require(entry.localDirectories.all { !File(it).isAbsolute }) { "localDirectories must be relative to the build root" }
                    if (kind == "android") {
                        require(entry.localDirectories.isEmpty()) { "Android local resources come from its source set" }
                        require(
                            !entry.sourceSet.startsWith("test", ignoreCase = true) &&
                                !entry.sourceSet.startsWith("androidTest", ignoreCase = true),
                        ) { "Android test source sets are not supported" }
                    }
                    Binding(
                        entry.name,
                        kind,
                        entry.modulePath,
                        entry.output,
                        entry.sourceSet,
                        File(settings.rootDir, entry.resourceRoot).canonicalPath,
                        entry.localDirectories.map { File(settings.rootDir, it).canonicalPath },
                    )
                }
            }
            val duplicate = bindings.groupBy { Triple(it.modulePath, it.kind, it.sourceSet) }.values.firstOrNull { it.size > 1 }
            if (duplicate !=
                null
            ) {
                throw GradleException("Only one Sentiary binding per source set is supported: ${duplicate.first().sourceSet}")
            }
            settings.gradle.lifecycle.beforeProject(
                ConfigureProject(
                    settings.rootDir.absolutePath,
                    settings.gradle.gradleUserHomeDir
                        .resolve("caches/sentiary/cli")
                        .absolutePath,
                    settings.providers.gradleProperty("sentiary.config").orNull,
                    settings.providers.gradleProperty("sentiary.cliVersion").getOrElse("v2.0.0"),
                    settings.providers.gradleProperty("sentiary.cliExecutable").orNull,
                    settings.gradle.startParameter.isOffline,
                    bindings,
                ),
            )
        }
    }
}

private class ConfigureProject(
    private val root: String,
    private val cliCache: String,
    private val config: String?,
    private val version: String,
    private val executable: String?,
    private val offline: Boolean,
    private val bindings: List<Binding>,
) : IsolatedAction<Project> {
    override fun execute(project: Project) {
        if (project.path == ":") {
            project.tasks.register("sentiaryPull", SentiaryPull::class.java) {
                buildRoot.set(root)
                cliCache.set(this@ConfigureProject.cliCache)
                configPath.set(config ?: "")
                cliVersion.set(version)
                cliExecutable.set(executable ?: "")
                offline.set(this@ConfigureProject.offline)
                bindings.set(this@ConfigureProject.bindings.map(Protocol::binding))
                resultFile.set(File(root, ".gradle/sentiary/result.json"))
            }
        }
        bindings.filter { it.modulePath == project.path }.forEach { binding ->
            project.afterEvaluate {
                val present = when (binding.kind) {
                    "android" -> pluginManager.hasPlugin("com.android.application") || pluginManager.hasPlugin("com.android.library")
                    "compose" -> pluginManager.hasPlugin("org.jetbrains.compose")
                    else -> false
                }
                require(present) {
                    "${binding.name}: ${binding.kind} resource binding requires the corresponding framework plugin in ${binding.modulePath}"
                }
            }
            when (binding.kind) {
                "android" -> AndroidResources.attach(project, binding, root)
                "compose" -> ComposeResources.attach(project, binding, root)
            }
        }
    }
}

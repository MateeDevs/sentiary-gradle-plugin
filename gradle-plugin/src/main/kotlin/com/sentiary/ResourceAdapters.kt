package com.sentiary

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

// Load only public signatures from the optional framework plugin's classloader.
private class PublicApi(
    private val loader: ClassLoader,
) {
    fun type(name: String): Class<*> = loader.loadClass(name)

    fun call(
        owner: String,
        receiver: Any,
        method: String,
        vararg parameters: Pair<Class<*>, Any>,
    ): Any? {
        val signature = type(owner).getMethod(method, *parameters.map { it.first }.toTypedArray())
        return signature.invoke(receiver, *parameters.map { it.second }.toTypedArray())
    }
}

private fun stage(
    project: Project,
    binding: Binding,
    root: String,
): TaskProvider<StageResources> =
    project.tasks.register(
        "sentiaryStage${binding.kind.replaceFirstChar(Char::uppercase)}${binding.sourceSet.replaceFirstChar(Char::uppercase)}",
        StageResources::class.java,
    ) {
        dependsOn(":sentiaryPull")
        resourceRoot.set(binding.resourceRoot)
        output.set(binding.output)
        resultFile.set(File(root, ".gradle/sentiary/result.json"))
        destination.set(project.layout.buildDirectory.dir("sentiary/staging/${binding.kind}/${binding.sourceSet}"))
    }

internal object ComposeResources {
    fun attach(
        project: Project,
        binding: Binding,
        root: String,
    ) {
        project.pluginManager.withPlugin("org.jetbrains.compose") {
            val api = PublicApi(
                project.plugins
                    .getPlugin("org.jetbrains.compose")
                    .javaClass.classLoader,
            )
            val compose = project.extensions.getByName("compose") as ExtensionAware
            val resources = compose.extensions.getByName("resources")
            project.afterEvaluate {
                val kotlin = extensions.getByName("kotlin")
                val sources = api.call(
                    "org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension",
                    kotlin,
                    "getSourceSets",
                ) as NamedDomainObjectContainer<*>
                require(sources.findByName(binding.sourceSet) != null) { "Unknown Compose source set ${binding.sourceSet}" }
            }
            val staged = stage(project, binding, root)
            staged.configure {
                localDirectories.from(project.layout.projectDirectory.dir("src/${binding.sourceSet}/composeResources"))
                localDirectories.from(binding.localDirectories.map(::File))
            }
            api.call(
                "org.jetbrains.compose.resources.ResourcesExtension",
                resources,
                "customDirectory",
                String::class.java to binding.sourceSet,
                Provider::class.java to staged.flatMap { it.destination },
            )
        }
    }
}

internal object AndroidResources {
    fun attach(
        project: Project,
        binding: Binding,
        root: String,
    ) {
        listOf("com.android.application", "com.android.library").forEach { pluginId ->
            project.pluginManager.withPlugin(pluginId) {
                val api = PublicApi(
                    project.plugins
                        .getPlugin(pluginId)
                        .javaClass.classLoader,
                )
                var matched = false
                project.afterEvaluate {
                    require(matched) { "Android source set ${binding.sourceSet} does not belong to an enabled production variant" }
                }
                val components = project.extensions.getByName("androidComponents")
                val staged = stage(project, binding, root)
                api.call(
                    "com.android.build.api.variant.DslLifecycle",
                    components,
                    "finalizeDsl",
                    Action::class.java to Action<Any> {
                        val dsl = this
                        val sourceSets = api.call(
                            "com.android.build.api.dsl.CommonExtension",
                            dsl,
                            "getSourceSets",
                        ) as NamedDomainObjectContainer<*>
                        val sourceSet = sourceSets.maybeCreate(binding.sourceSet)
                        val resources = api.call("com.android.build.api.dsl.AndroidSourceSet", sourceSet, "getRes")!!
                        val directories = api.call(
                            "com.android.build.api.dsl.AndroidSourceDirectorySet",
                            resources,
                            "getDirectories",
                        ) as Set<*>
                        val localFiles = directories.map { project.file(requireNotNull(it)) }
                        staged.configure { localDirectories.from(localFiles) }
                        api.call(
                            "com.android.build.api.dsl.AndroidSourceDirectorySet",
                            resources,
                            "setSrcDirs",
                            Iterable::class.java to listOf(staged.flatMap { it.destination }),
                        )
                    },
                )
                val selector = api.call("com.android.build.api.variant.AndroidComponentsExtension", components, "selector")!!
                api.call(
                    "com.android.build.api.variant.AndroidComponentsExtension",
                    components,
                    "onVariants",
                    api.type("com.android.build.api.variant.VariantSelector") to selector,
                    Action::class.java to Action<Any> {
                        val variant = this
                        val identity = "com.android.build.api.variant.ComponentIdentity"
                        val names = mutableSetOf("main")
                        listOf("getName", "getBuildType", "getFlavorName").forEach { method ->
                            (api.call(identity, variant, method) as? String)?.let(names::add)
                        }
                        @Suppress("UNCHECKED_CAST")
                        val flavors = api.call(identity, variant, "getProductFlavors") as List<Pair<String, String>>
                        names.addAll(flavors.map { it.second })
                        if (binding.sourceSet in names) {
                            matched = true
                            val lifecycle = api.call("com.android.build.api.variant.Component", variant, "getLifecycleTasks")!!
                            api.call(
                                "com.android.build.api.variant.LifecycleTasks",
                                lifecycle,
                                "registerPreBuild",
                                Array<Any>::class.java to arrayOf(staged),
                            )
                        }
                    },
                )
            }
        }
    }
}

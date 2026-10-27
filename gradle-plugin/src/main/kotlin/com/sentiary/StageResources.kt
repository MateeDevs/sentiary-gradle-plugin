package com.sentiary

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files

@DisableCachingByDefault(because = "Validates the current CLI result and local resource collisions")
abstract class StageResources : DefaultTask() {
    @get:Internal abstract val localDirectories: ConfigurableFileCollection

    @get:Internal abstract val resourceRoot: Property<String>

    @get:Internal abstract val output: Property<String>

    @get:Internal abstract val resultFile: RegularFileProperty

    @get:OutputDirectory abstract val destination: DirectoryProperty

    init {
        group = "Sentiary"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stage() {
        val sources = linkedMapOf<String, Pair<String, File>>()
        val physical = mutableSetOf<String>()

        fun add(
            relative: String,
            source: File,
        ) {
            if (!source.isFile) throw GradleException("Missing resource $source")
            if (!physical.add(source.canonicalPath)) return
            val previous = sources.putIfAbsent(relative.lowercase(), relative to source)
            if (previous != null) throw GradleException("Resource collision at $relative: ${previous.second} and $source")
        }
        val root = File(resourceRoot.get()).canonicalFile
        Protocol.files(resultFile.get().asFile.readText(), output.get()).forEach { (file, checksum) ->
            val path = file.canonicalFile.toPath()
            require(path.startsWith(root.toPath()) && path != root.toPath()) { "Generated resource is outside resourceRoot" }
            require(CliDistribution.digest(file) == checksum) { "Generated resource checksum changed: $file" }
            add(root.toPath().relativize(path).toString(), file)
        }
        localDirectories.files.forEach { directory ->
            if (directory.exists() && !directory.isDirectory) throw GradleException("Local resource input is not a directory: $directory")
            if (directory.isDirectory) {
                directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    add(file.relativeTo(directory).path, file)
                }
            }
        }
        // Validate every input and collision before replacing the previous staging directory.
        val target = destination.get().asFile
        val staging = File(temporaryDir, "resources").apply {
            deleteRecursively()
            mkdirs()
        }
        sources.values.forEach { (relative, file) ->
            val destination = staging.resolve(relative)
            destination.parentFile.mkdirs()
            Files.copy(file.toPath(), destination.toPath())
        }
        target.deleteRecursively()
        target.parentFile.mkdirs()
        staging.copyRecursively(target, overwrite = true)
    }
}

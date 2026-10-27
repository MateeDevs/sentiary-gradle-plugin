package com.sentiary

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Online freshness must be checked on every resource build")
abstract class SentiaryPull
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Internal abstract val buildRoot: Property<String>

        @get:Internal abstract val cliCache: Property<String>

        @get:Internal abstract val configPath: Property<String>

        @get:Internal abstract val cliVersion: Property<String>

        @get:Internal abstract val cliExecutable: Property<String>

        @get:Internal abstract val offline: Property<Boolean>

        @get:Internal abstract val bindings: ListProperty<String>

        @get:Internal abstract val resultFile: RegularFileProperty

        init {
            group = "Sentiary"
            description = "Pull the translation outputs consumed by this build"
        }

        @TaskAction
        fun pull() {
            val executable =
                cliExecutable.get().takeIf(String::isNotBlank)?.let {
                    File(buildRoot.get(), it).let { file ->
                        if (File(it).isAbsolute) File(it) else file
                    }
                }
                    ?: CliDistribution.install(File(cliCache.get()), cliVersion.get(), offline.get())

            fun run(arguments: List<String>): String {
                val stdout = ByteArrayOutputStream()
                exec
                    .exec {
                        workingDir = File(buildRoot.get())
                        commandLine(listOf(executable.absolutePath) + arguments)
                        standardOutput = stdout
                    }.assertNormalExitValue()
                return stdout.toString(Charsets.UTF_8)
            }
            Protocol.checkVersion(run(listOf("version", "--json")))
            val arguments = mutableListOf("pull", "--json")
            if (configPath.get().isNotBlank()) arguments += listOf("--config", configPath.get())
            if (offline.get()) arguments += "--offline"
            if (bindings.get().isNotEmpty()) {
                val request = File(temporaryDir, "request.json")
                request.writeText(Protocol.request(bindings.get()))
                arguments += listOf("--integration-request", request.absolutePath)
            }
            val result = run(arguments)
            // Validate the envelope even when no source set consumes the result file.
            Protocol.files(result, "")
            resultFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText(result)
            }
        }
    }

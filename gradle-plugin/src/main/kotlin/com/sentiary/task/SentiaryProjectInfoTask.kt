package com.sentiary.task

import com.sentiary.api.SentiaryApiClientService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SentiaryProjectInfoTask : DefaultTask() {

    @get:Internal
    abstract val sentiaryApiClientService: Property<SentiaryApiClientService>

    @get:OutputFile
    abstract val projectInfoFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val service = sentiaryApiClientService.get()
        if (!service.parameters.projectId.isPresent || !service.parameters.projectApiKey.isPresent) {
            throw IllegalArgumentException("Sentiary projectId and projectApiKey must be set.")
        }

        val projectInfo = runBlocking {
            service.client.getProjectInfo().getOrThrow()
        }

        val json = Json.encodeToString(projectInfo)
        projectInfoFile.get().asFile.writeText(json)
    }
}

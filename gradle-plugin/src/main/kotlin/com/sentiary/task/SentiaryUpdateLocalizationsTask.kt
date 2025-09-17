package com.sentiary.task

import com.sentiary.api.SentiaryApiClientService
import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import com.sentiary.model.ProjectInfo
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class SentiaryUpdateLocalizationsTask : DefaultTask() {

    @get:Input
    @get:Option(
        option = "force-update",
        description = "Forces the download of the localization files, ignoring the cache."
    )
    abstract val forceUpdate: Property<Boolean>

    @get:Internal
    abstract val sentiaryApiClientService: Property<SentiaryApiClientService>

    @get:InputFile
    abstract val projectInfoFile: RegularFileProperty

    @get:Input
    abstract val defaultLanguage: Property<String>

    @get:Nested
    abstract val languageOverrides: ListProperty<LanguageOverride>

    @get:Input
    abstract val disabledLanguages: ListProperty<String>

    @get:Nested
    abstract val exportPaths: ListProperty<ExportPath>

    @get:Nested
    abstract val caching: Property<CacheConfiguration>

    @get:LocalState
    abstract val cacheFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val service = sentiaryApiClientService.get()
        if (!service.parameters.projectId.isPresent || !service.parameters.projectApiKey.isPresent) {
            throw GradleException("Sentiary projectId and projectApiKey must be set.")
        }

        val projectInfo =
            Json.decodeFromString<ProjectInfo>(projectInfoFile.get().asFile.readText())

        val worker = SentiaryWorker(
            client = service.client,
            logger = logger,
            projectInfo = projectInfo,
            defaultLanguage = defaultLanguage.get(),
            languageOverrides = languageOverrides.get(),
            disabledLanguages = disabledLanguages.get().toSet(),
            exportPaths = exportPaths.get(),
            cacheConfiguration = caching.get(),
            cacheFile = cacheFile.get().asFile,
        )
        didWork = worker.run()
    }
}

package com.sentiary.task

import com.sentiary.api.SentiaryApiClientService
import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class SentiaryFetchTask : DefaultTask() {

    @get:Input
    @get:Option(option = "force-update", description = "Forces the download of the localization files, ignoring the cache.")
    abstract val forceUpdate: Property<Boolean>

    @get:Internal
    abstract val sentiaryApiClientService: Property<SentiaryApiClientService>

    @get:Input
    abstract val defaultLanguage: Property<String>

    @get:Nested
    abstract val languageOverrides: ListProperty<LanguageOverride>

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
            throw IllegalArgumentException("Sentiary projectId and projectApiKey must be set.")
        }

        val client = service.client

        val worker = SentiaryWorker(
            client = client,
            logger = logger,
            defaultLanguage = defaultLanguage.get(),
            languageOverrides = languageOverrides.get(),
            exportPaths = exportPaths.get(),
            cacheConfiguration = caching.get(),
            cacheFile = cacheFile.get().asFile,
            forceUpdate = forceUpdate.getOrElse(false),
        )
        didWork = worker.run()
    }
}
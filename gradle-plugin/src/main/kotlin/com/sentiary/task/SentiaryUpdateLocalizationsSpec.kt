package com.sentiary.task

import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import com.sentiary.config.LocalizationOutputProvider
import com.sentiary.model.ProjectInfo
import kotlinx.serialization.json.Json
import org.gradle.api.Task
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.internal.cc.base.logger

internal class SentiaryUpdateLocalizationsSpec(
    private val forceUpdate: Property<Boolean>,
    private val projectInfoFile: RegularFileProperty,
    private val languageOverrides: ListProperty<LanguageOverride>,
    private val disabledLanguages: ListProperty<String>,
    private val exportPaths: ListProperty<ExportPath>,
    private val defaultLanguage: Property<String>,
    private val caching: Property<CacheConfiguration>,
    private val cacheFile: RegularFileProperty,
) : Spec<Task> {

    override fun isSatisfiedBy(element: Task): Boolean {
        logger.debug("[Sentiary] Running SentiaryUpdateLocalizationsSpec")
        if (forceUpdate.getOrElse(false)) {
            logger.debug("[Sentiary] Force update enabled, updating localizations")
            return true
        }

        val projectInfoFileProvider = projectInfoFile.get().asFile
        if (!projectInfoFileProvider.exists()) {
            logger.debug("[Sentiary] Project info file does not exist, updating localizations")
            return true
        }

        val projectInfo = Json.decodeFromString<ProjectInfo>(projectInfoFileProvider.readText())
        val outputProvider = LocalizationOutputProvider(
            projectInfo = projectInfo,
            languageOverrides = languageOverrides.get(),
            disabledLanguages = disabledLanguages.get().toSet(),
            exportPaths = exportPaths.get(),
            defaultLanguage = defaultLanguage.get(),
        )

        if (outputProvider.getOutputFiles().any { !it.exists() }) {
            logger.debug("[Sentiary] Some output files do not exist, updating localizations")
            return true
        }

        val cachingConfig = caching.get()
        if (!cachingConfig.enabled.get()) {
            logger.debug("[Sentiary] Caching is not enabled, updating localizations")
            return true
        }

        val cacheFileProvider = cacheFile.get().asFile
        if (!cacheFileProvider.exists()) {
            logger.debug("[Sentiary] Cache file does not exist, updating localizations")
            return true
        }

        val cacheLastModified = kotlinx.datetime.Instant.parse(cacheFileProvider.readText().trim())
        val remoteLastModified = projectInfo.termsLastModified

        logger.debug("[Sentiary] remoteLastModified: $remoteLastModified, cacheLastModified: $cacheLastModified")
        logger.debug("[Sentiary] remoteLastModified > cacheLastModified: ${remoteLastModified > cacheLastModified}")

        return remoteLastModified > cacheLastModified
    }
}

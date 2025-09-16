package com.sentiary.task

import com.sentiary.api.SentiaryApiClient
import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import com.sentiary.model.ExportedLocalization
import com.sentiary.model.LanguageConfiguration
import com.sentiary.model.ProjectInfo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.gradle.api.logging.Logger
import java.io.File

internal class SentiaryWorker(
    private val client: SentiaryApiClient,
    private val logger: Logger,
    private val defaultLanguage: String,
    private val languageOverrides: List<LanguageOverride>,
    private val exportPaths: List<ExportPath>,
    private val cacheConfiguration: CacheConfiguration,
    private val cacheFile: File,
    private val forceUpdate: Boolean,
) {

    fun run(): Boolean = runBlocking { execute() }

    private suspend fun execute(): Boolean {
        logger.debug("$TAG Fetching project info")

        val projectInfo = client
            .getProjectInfo()
            .onFailure {
                logger.error("$TAG Failed to get project info!")
            }.getOrThrow()

        val languages = calculateLanguageConfiguration(projectInfo)

        if (checkIfUpToDate(projectInfo, languages.expectedLanguages)) {
            logger.info("$TAG Localizations are up to date")
            return false
        }

        logger.info("$TAG Project Name: ${projectInfo.name}")
        logger.info("$TAG Languages: ${projectInfo.languages.joinToString(", ")}")

        val exported = exportPaths.flatMap { exportPath ->
            languages.languagesToFetch.map { language ->
                val outputFile = createOutputFile(
                    language = language,
                    exportPath = exportPath,
                )

                fetchLocalizationForLanguage(
                    client = client,
                    language = language,
                    exportPath = exportPath,
                    outputFile = outputFile,
                ).onFailure { throwable ->
                    logger.error("$TAG Failed to fetch localization for $language!")
                    throw throwable
                }

                ExportedLocalization(
                    language = language,
                    outputFile = outputFile,
                    exportPath = exportPath,
                )
            }
        }

        handleLanguageOverrides(languageOverrides, exported)
        saveLastModified(projectInfo.termsLastModified)
        return true
    }

    private fun calculateLanguageConfiguration(projectInfo: ProjectInfo): LanguageConfiguration {
        val projectLanguages = projectInfo.languages.toSet()
        val overrides = languageOverrides

        val disabledLanguages = overrides.filterNot { it.fetch.get() }.map { it.name }.toSet()
        val enabledOverrides = overrides.filter { it.fetch.get() }

        val expectedLanguages =
            (projectLanguages - disabledLanguages) + enabledOverrides.map { it.name }
        val fallbackSources = enabledOverrides.mapNotNull { it.fallbackTo.orNull }.toSet()
        val languagesToFetch =
            (expectedLanguages - enabledOverrides.map { it.name }.toSet()) + fallbackSources

        return LanguageConfiguration(languagesToFetch, expectedLanguages)
    }


    private fun handleLanguageOverrides(
        languageOverrides: List<LanguageOverride>,
        exported: List<ExportedLocalization>,
    ) {
        val fetchedOverrides = languageOverrides.filter { it.fetch.get() }
        val fallbacks = fetchedOverrides.mapNotNull { it.fallbackTo.orNull }
        val exportedGroups = exported
            .groupBy { it.language }
            .filterKeys { it in fallbacks }

        fetchedOverrides.forEach { override ->
            val fallback = override.fallbackTo.orNull ?: return@forEach
            exportedGroups[fallback]?.forEach { localization ->
                val outputFile = createOutputFile(
                    language = override.name,
                    exportPath = localization.exportPath,
                )
                logger.lifecycle(
                    "$TAG Creating '${override.name}' from fallback '$fallback' " +
                        "for export '${localization.exportPath.name}' at ${outputFile.path}",
                )
                localization.outputFile.copyTo(outputFile, overwrite = true)
            }
        }
    }

    private fun saveLastModified(lastModified: Instant) {
        if (!cacheConfiguration.enabled.get()) return
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(lastModified.toString())
    }

    private fun checkIfUpToDate(
        projectInfo: ProjectInfo,
        expectedLanguages: Set<String>,
    ): Boolean {
        if (forceUpdate) {
            logger.lifecycle("$TAG Force update is enabled, forcing download.")
            return false
        }

        for (language in expectedLanguages) {
            for (exportPath in exportPaths) {
                val expectedFile = createOutputFile(language = language, exportPath = exportPath)
                if (!expectedFile.exists()) {
                    logger.lifecycle("$TAG Output file ${expectedFile.path} is missing, forcing download.")
                    return false
                }
            }
        }

        if (!cacheConfiguration.enabled.get()) {
            logger.debug("$TAG Caching is disabled, forcing download.")
            return false
        }

        if (!cacheFile.exists()) {
            logger.debug("$TAG Cache file does not exist, forcing download.")
            return false
        }

        val cacheLastModified = Instant.Companion.parse(cacheFile.readText().trim())
        val remoteLastModified = projectInfo.termsLastModified

        val isUpToDate = remoteLastModified <= cacheLastModified
        if (!isUpToDate) {
            logger.lifecycle("$TAG Remote content is newer, forcing download.")
        }

        return isUpToDate
    }

    private fun createOutputFile(language: String, exportPath: ExportPath): File {
        val baseDir = exportPath.outputDirectory.get()
        val folderNamingStrategy = exportPath.folderNamingStrategy.get()
        val fileNamingStrategy = exportPath.fileNamingStrategy.get()

        val isDefault = language == defaultLanguage
        val folderName = folderNamingStrategy(language, isDefault)
        val fileName = fileNamingStrategy(language, isDefault)

        val outputDirectory = baseDir.dir(folderName)
        val outputFile = outputDirectory.file(fileName)

        outputDirectory.asFile.mkdirs()
        return outputFile.asFile
    }

    private suspend fun fetchLocalizationForLanguage(
        client: SentiaryApiClient,
        language: String,
        exportPath: ExportPath,
        outputFile: File,
    ): Result<Unit> {
        val format = exportPath.format.get()
        logger.lifecycle("$TAG Downloading '$language' for export '${exportPath.name}' to ${outputFile.path}")
        return client.fetchLocalization(
            language = language,
            format = format,
            outputFile = outputFile,
        )
    }

    private companion object {
        const val TAG = "[Sentiary]"
    }
}
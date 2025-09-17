package com.sentiary.task

import com.sentiary.api.SentiaryApiClient
import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import com.sentiary.config.LocalizationOutputProvider
import com.sentiary.model.ExportedLocalization
import com.sentiary.model.ProjectInfo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.gradle.api.logging.Logger
import java.io.File

internal class SentiaryWorker(
    private val client: SentiaryApiClient,
    private val logger: Logger,
    private val projectInfo: ProjectInfo,
    private val defaultLanguage: String,
    private val languageOverrides: List<LanguageOverride>,
    private val disabledLanguages: Set<String>,
    private val exportPaths: List<ExportPath>,
    private val cacheConfiguration: CacheConfiguration,
    private val cacheFile: File,
    private val forceUpdate: Boolean,
) {

    private val outputProvider = LocalizationOutputProvider(
        projectInfo = projectInfo,
        languageOverrides = languageOverrides,
        disabledLanguages = disabledLanguages,
        exportPaths = exportPaths,
        defaultLanguage = defaultLanguage,
    )

    fun run(): Boolean = runBlocking { execute() }

    private suspend fun execute(): Boolean {
        if (checkIfUpToDate()) {
            logger.info("$TAG Localizations are up to date")
            return false
        }

        logger.info("$TAG Project Name: ${projectInfo.name}")
        logger.info("$TAG Languages: ${projectInfo.languages.joinToString(", ")}")

        val exported = exportPaths.flatMap { exportPath ->
            outputProvider.languagesToFetch.map { language ->
                val outputFile = outputProvider.getOutputFileFor(language, exportPath)
                outputFile.parentFile.mkdirs()

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

    private fun handleLanguageOverrides(
        languageOverrides: List<LanguageOverride>,
        exported: List<ExportedLocalization>,
    ) {
        val fallbacks = languageOverrides.mapNotNull { it.fallbackTo.orNull }
        val exportedGroups = exported
            .groupBy { it.language }
            .filterKeys { it in fallbacks }

        languageOverrides.forEach { override ->
            val fallback = override.fallbackTo.orNull ?: return@forEach
            exportedGroups[fallback]?.forEach { localization ->
                val outputFile = outputProvider.getOutputFileFor(override.name, localization.exportPath)
                outputFile.parentFile.mkdirs()

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

    private fun checkIfUpToDate(): Boolean {
        if (forceUpdate) {
            logger.lifecycle("$TAG Force update is enabled, forcing download.")
            return false
        }

        for (expectedFile in outputProvider.getOutputFiles()) {
            if (!expectedFile.exists()) {
                logger.lifecycle("$TAG Output file ${expectedFile.path} is missing, forcing download.")
                return false
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
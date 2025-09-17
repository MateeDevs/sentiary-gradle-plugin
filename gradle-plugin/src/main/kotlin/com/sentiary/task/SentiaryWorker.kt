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
import org.gradle.api.GradleException
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
        val allLocalizations = exported.groupBy { it.language }.toMutableMap()

        // Topologically sort the languages based on fallback dependencies.
        val sortedLanguages = topologicallySort(languageOverrides)

        if (sortedLanguages == null) {
            logger.error("$TAG Could not resolve language overrides due to a circular dependency.")
            throw GradleException("Circular dependency detected in language overrides.")
        }

        // Process the overrides in the guaranteed correct order.
        for (language in sortedLanguages) {
            val override = languageOverrides.find { it.name == language } ?: continue
            val fallbackLanguage = override.fallbackTo.orNull ?: continue

            val fallbackLocalizations = allLocalizations[fallbackLanguage]
            if (fallbackLocalizations == null) {
                // This is a hard failure because the topological sort guarantees we should be able to resolve this.
                // If we can't, it means the user is asking for a fallback that was never part of the project.
                throw GradleException("Could not resolve fallback for '$language'. The dependency '$fallbackLanguage' is missing.")
            }

            logger.info("$TAG Resolving fallback for '$language' from '$fallbackLanguage'")
            val newLocalizations = mutableListOf<ExportedLocalization>()
            fallbackLocalizations.forEach { fallbackLocalization ->
                val outputFile = outputProvider.getOutputFileFor(language, fallbackLocalization.exportPath)
                outputFile.parentFile.mkdirs()

                logger.lifecycle(
                    "$TAG Creating '$language' from fallback '$fallbackLanguage' " +
                        "for export '${fallbackLocalization.exportPath.name}' at ${outputFile.path}",
                )
                fallbackLocalization.outputFile.copyTo(outputFile, overwrite = true)
                newLocalizations.add(
                    ExportedLocalization(
                        language = language,
                        outputFile = outputFile,
                        exportPath = fallbackLocalization.exportPath,
                    )
                )
            }
            allLocalizations[language] = newLocalizations
        }
    }

    /**
     * Performs a topological sort (using Kahn's algorithm) on the language overrides.
     * Returns a list of languages in an order that respects fallback dependencies,
     * or null if a cycle is detected.
     */
    private fun topologicallySort(overrides: List<LanguageOverride>): List<String>? {
        val graph = mutableMapOf<String, MutableSet<String>>()
        val inDegrees = mutableMapOf<String, Int>()

        // Build the graph and in-degree map.
        // An edge goes from the fallback to the override (e.g., en-US -> en-GB).
        for (override in overrides) {
            val lang = override.name
            val fallback = override.fallbackTo.orNull ?: continue

            graph.getOrPut(fallback) { mutableSetOf() }.add(lang)
            inDegrees.putIfAbsent(fallback, 0)
            inDegrees[lang] = (inDegrees[lang] ?: 0) + 1
        }

        // Initialize the queue with all nodes that have no incoming edges.
        val queue = ArrayDeque<String>()
        inDegrees.keys.forEach { lang ->
            if (inDegrees[lang] == 0) {
                queue.add(lang)
            }
        }

        val sorted = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val lang = queue.removeFirst()
            sorted.add(lang)

            graph[lang]?.forEach { neighbor ->
                inDegrees[neighbor] = (inDegrees[neighbor] ?: 0) - 1
                if (inDegrees[neighbor] == 0) {
                    queue.add(neighbor)
                }
            }
        }

        // If the sorted list doesn't contain all nodes that had dependencies, there's a cycle.
        return if (sorted.size == inDegrees.keys.size) {
            // We only care about the order of the overrides themselves, not the base languages.
            sorted.filter { lang -> overrides.any { it.name == lang } }
        } else {
            null // Cycle detected
        }
    }

    private fun saveLastModified(lastModified: Instant) {
        if (!cacheConfiguration.enabled.get()) return
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(lastModified.toString())
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

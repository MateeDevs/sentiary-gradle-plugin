package com.sentiary.config

import com.sentiary.model.ProjectInfo
import java.io.File

internal class LocalizationOutputProvider(
    private val projectInfo: ProjectInfo,
    private val languageOverrides: List<LanguageOverride>,
    private val disabledLanguages: Set<String>,
    private val exportPaths: List<ExportPath>,
    private val defaultLanguage: String,
) {
    val expectedLanguages: Set<String> by lazy {
        val projectLanguages = projectInfo.languages.toSet()
        val overrideLanguages = languageOverrides.map { it.name }.toSet()
        val allPossibleLanguages = projectLanguages + overrideLanguages
        allPossibleLanguages - disabledLanguages
    }

    val languagesToFetch: Set<String> by lazy {
        val fallbackSources = languageOverrides
            .mapNotNull { it.fallbackTo.orNull }
            .filterNot { it in disabledLanguages }
            .toSet()

        val languagesWithoutOverrides = expectedLanguages - languageOverrides.map { it.name }.toSet()

        languagesWithoutOverrides + fallbackSources
    }

    fun getOutputFiles(): List<File> {
        return exportPaths.flatMap { exportPath ->
            expectedLanguages.map { lang ->
                getOutputFileFor(lang, exportPath)
            }
        }
    }

    fun getOutputFileFor(language: String, exportPath: ExportPath): File {
        val baseDir = exportPath.outputDirectory.get().asFile
        val isDefault = language == defaultLanguage
        val folderName = exportPath.folderNamingStrategy.get().invoke(language, isDefault)
        val fileName = exportPath.fileNamingStrategy.get().invoke(language, isDefault)

        val outputDirectory = File(baseDir, folderName)
        return File(outputDirectory, fileName)
    }
}

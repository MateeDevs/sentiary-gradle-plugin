package com.sentiary

import com.sentiary.api.SentiaryApiClientService
import com.sentiary.config.DefaultFolderNamingStrategy
import com.sentiary.config.FileNamingStrategyFromFormat
import com.sentiary.task.SentiaryUpdateLocalizationsSpec
import com.sentiary.task.SentiaryUpdateLocalizationsTask
import com.sentiary.task.SentiaryUpdateProjectInfoTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

open class SentiaryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sentiaryExtension = project.extensions.create<SentiaryPluginExtension>("sentiary")

        sentiaryExtension.exportPaths.all {
            folderNamingStrategy.convention(DefaultFolderNamingStrategy)
            fileNamingStrategy.convention(format.map { FileNamingStrategyFromFormat(it) })
        }

        project.afterEvaluate {
            val exportPaths = sentiaryExtension.exportPaths
            val outputDirs = exportPaths.map { it.outputDirectory.get().asFile }
            val duplicates = outputDirs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw GradleException("Duplicate output directories found in sentiary exportPaths: ${duplicates.joinToString()}")
            }
        }

        val sentiaryServiceProvider = project.gradle.sharedServices.registerIfAbsent(
            "sentiaryApiClient",
            SentiaryApiClientService::class.java
        ) {
            parameters.sentiaryUrl.set(
                project.providers.systemProperty("com.sentiary.internal.test.url")
                    .orElse("https://api.sentiary.com/")
            )
            parameters.projectId.set(sentiaryExtension.projectId)
            parameters.projectApiKey.set(sentiaryExtension.projectApiKey)
            parameters.requestTimeoutMillis.set(sentiaryExtension.requestTimeoutMillis)
        }

        val sentiaryUpdateProjectInfoTask =
            project.tasks.register<SentiaryUpdateProjectInfoTask>("sentiaryUpdateProjectInfo") {
                group = "Sentiary"
                description = "Updates a local cache of the Sentiary project information, including language configurations and modification timestamps."
                sentiaryApiClientService.set(sentiaryServiceProvider)
                projectInfoFile.set(project.layout.buildDirectory.file("sentiary/project-info.json"))
                outputs.upToDateWhen { false } // Always check for new languages
            }

        project.tasks.register<SentiaryUpdateLocalizationsTask>("sentiaryUpdateLocalizations") {
            group = "Sentiary"
            description = "Updates local localization files from Sentiary."

            sentiaryApiClientService.set(sentiaryServiceProvider)
            defaultLanguage.set(sentiaryExtension.defaultLanguage)
            languageOverrides.set(sentiaryExtension.languageOverrides)
            disabledLanguages.set(sentiaryExtension.disabledLanguages)
            exportPaths.set(sentiaryExtension.exportPaths)
            caching.set(sentiaryExtension.caching)
            cacheFile.set(project.layout.buildDirectory.file("sentiary/timestamp"))
            projectInfoFile.set(sentiaryUpdateProjectInfoTask.flatMap { it.projectInfoFile })

            outputs.dirs(sentiaryExtension.exportPaths.map { it.outputDirectory })
            outputs.upToDateWhen(
                SentiaryUpdateLocalizationsSpec(
                    forceUpdate = forceUpdate,
                    projectInfoFile = projectInfoFile,
                    languageOverrides = languageOverrides,
                    disabledLanguages = disabledLanguages,
                    exportPaths = exportPaths,
                    defaultLanguage = defaultLanguage,
                    caching = caching,
                    cacheFile = cacheFile,
                )
            )

            forceUpdate.convention(false)
            dependsOn(sentiaryUpdateProjectInfoTask)
        }
    }
}

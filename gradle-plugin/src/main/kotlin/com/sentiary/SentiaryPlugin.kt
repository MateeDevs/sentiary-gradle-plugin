package com.sentiary

import com.sentiary.api.SentiaryApiClientService
import com.sentiary.config.DefaultFolderNamingStrategy
import com.sentiary.config.FileNamingStrategyFromFormat
import com.sentiary.config.LocalizationOutputProvider
import com.sentiary.model.ProjectInfo
import com.sentiary.task.SentiaryFetchTask
import com.sentiary.task.SentiaryProjectInfoTask
import kotlinx.serialization.json.Json
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

        val sentiaryServiceProvider = project.gradle.sharedServices.registerIfAbsent(
            "sentiaryApiClient",
            SentiaryApiClientService::class.java
        ) {
            parameters.sentiaryUrl.set(sentiaryExtension.sentiaryUrl)
            parameters.projectId.set(sentiaryExtension.projectId)
            parameters.projectApiKey.set(sentiaryExtension.projectApiKey)
            parameters.requestTimeoutMillis.set(sentiaryExtension.requestTimeoutMillis)
        }

        val sentiaryProjectInfoTask =
            project.tasks.register<SentiaryProjectInfoTask>("sentiaryProjectInfo") {
                group = "Sentiary"
                description = "Downloads project info from Sentiary."
                sentiaryApiClientService.set(sentiaryServiceProvider)
                projectInfoFile.set(project.layout.buildDirectory.file("sentiary/project-info.json"))
                outputs.upToDateWhen { false } // Always check for new languages
            }

        project.tasks.register<SentiaryFetchTask>("sentiaryFetch") {
            group = "Sentiary"
            description = "Downloads and exports localization files from Sentiary."

            sentiaryApiClientService.set(sentiaryServiceProvider)
            defaultLanguage.set(sentiaryExtension.defaultLanguage)
            languageOverrides.set(project.provider { sentiaryExtension.languageOverrides.toList() })
            disabledLanguages.set(sentiaryExtension.disabledLanguages)
            exportPaths.set(project.provider { sentiaryExtension.exportPaths.toList() })
            caching.set(sentiaryExtension.caching)
            cacheFile.set(sentiaryExtension.caching.cacheFilePath)
            projectInfoFile.set(sentiaryProjectInfoTask.flatMap { it.projectInfoFile })

            // The task action itself will determine if work needs to be done.
            outputs.dirs(sentiaryExtension.exportPaths.map { it.outputDirectory })

            forceUpdate.convention(false)
            dependsOn(sentiaryProjectInfoTask)
        }
    }
}

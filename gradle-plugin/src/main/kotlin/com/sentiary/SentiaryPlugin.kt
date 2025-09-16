package com.sentiary

import com.sentiary.config.DefaultFolderNamingStrategy
import com.sentiary.config.FileNamingStrategyFromFormat
import com.sentiary.api.SentiaryApiClientService
import com.sentiary.task.SentiaryFetchTask
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

        sentiaryExtension.languageOverrides.all {
            fetch.convention(true)
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

        project.tasks.register<SentiaryFetchTask>("sentiaryFetch") {
            group = "Sentiary"
            description = "Downloads and exports localization files from Sentiary."

            sentiaryApiClientService.set(sentiaryServiceProvider)
            defaultLanguage.set(sentiaryExtension.defaultLanguage)
            languageOverrides.set(project.provider { sentiaryExtension.languageOverrides.toList() })
            exportPaths.set(project.provider { sentiaryExtension.exportPaths.toList() })
            caching.set(sentiaryExtension.caching)
            cacheFile.set(sentiaryExtension.caching.cacheFilePath)

            outputs.dirs(
                project.provider {
                    sentiaryExtension.exportPaths.map { it.outputDirectory }
                }
            )

            // This task can never be UP-TO-DATE because it needs to check a remote API.
            // The task action itself will determine if work needs to be done.
            outputs.upToDateWhen { false }

            forceUpdate.convention(false)
        }
    }
}

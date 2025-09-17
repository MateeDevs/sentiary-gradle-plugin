package com.sentiary

import com.sentiary.config.CacheConfiguration
import com.sentiary.config.ExportPath
import com.sentiary.config.LanguageOverride
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class SentiaryPluginExtension @Inject constructor(
    objects: ObjectFactory,
    project: Project,
) {
    abstract val projectId: Property<String>
    abstract val projectApiKey: Property<String>

    /**
     * IETF BCP 47 language identifier for the default language.
     */
    abstract val defaultLanguage: Property<String>

    /**
     * Network request timeout in milliseconds. Defaults to 100000.
     */
    abstract val requestTimeoutMillis: Property<Long>

    val languageOverrides: NamedDomainObjectContainer<LanguageOverride> =
        objects.domainObjectContainer(LanguageOverride::class.java)

    abstract val disabledLanguages: ListProperty<String>

    val exportPaths: NamedDomainObjectContainer<ExportPath> =
        objects.domainObjectContainer(ExportPath::class.java)

    val caching: CacheConfiguration = objects.newInstance(CacheConfiguration::class.java)

    init {
        defaultLanguage.convention("en-US")
        requestTimeoutMillis.convention(100_000L)
        disabledLanguages.convention(emptyList())

        projectId.convention(
            project.providers.gradleProperty("sentiary.projectId")
                .orElse(project.providers.environmentVariable("SENTIARY_PROJECT_ID"))
        )
        projectApiKey.convention(
            project.providers.gradleProperty("sentiary.projectApiKey")
                .orElse(project.providers.environmentVariable("SENTIARY_PROJECT_API_KEY"))
        )

        caching.enabled.convention(true)
    }

    fun caching(action: Action<CacheConfiguration>) {
        action.execute(this.caching)
    }
}

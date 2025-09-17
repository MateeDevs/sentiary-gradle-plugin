package com.sentiary.config

import org.gradle.api.Named
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import javax.inject.Inject

abstract class LanguageOverride @Inject constructor(private val name: String) : Named {
    @Input
    override fun getName(): String = name

    /**
     * IETF BCP 47 language identifier for the language to use as the source of this language.
     */
    @get:Input
    abstract val fallbackTo: Property<String>
}
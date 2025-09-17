package com.sentiary.config

import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import javax.inject.Inject

abstract class ExportPath @Inject constructor(private val name: String) : Named {

    @Input
    override fun getName(): String = name

    @get:Input
    abstract val format: Property<Format>

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val folderNamingStrategy: Property<NamingStrategy>

    fun folderNamingStrategy(strategy: NamingStrategy) {
        this.folderNamingStrategy.set(strategy)
    }

    @get:Input
    abstract val fileNamingStrategy: Property<NamingStrategy>

    fun fileNamingStrategy(strategy: NamingStrategy) {
        this.fileNamingStrategy.set(strategy)
    }
}
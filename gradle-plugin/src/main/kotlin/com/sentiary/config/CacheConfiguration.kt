package com.sentiary.config

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

abstract class CacheConfiguration {

    /**
     * Whether to enable caching. Defaults to true.
     */
    @get:Input
    abstract val enabled: Property<Boolean>

    /**
     * The path to the file where the last modified date is stored.
     * By default, a cache file is stored in the build directory.
     */
    @get:Internal
    abstract val cacheFilePath: RegularFileProperty
}
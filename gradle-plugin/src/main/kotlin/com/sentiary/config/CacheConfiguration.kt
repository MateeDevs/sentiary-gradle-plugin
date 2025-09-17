package com.sentiary.config

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

abstract class CacheConfiguration {

    /**
     * Whether to enable caching. Defaults to true.
     */
    @get:Input
    abstract val enabled: Property<Boolean>
}
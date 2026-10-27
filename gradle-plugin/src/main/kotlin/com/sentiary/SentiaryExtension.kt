package com.sentiary

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import java.io.Serializable
import javax.inject.Inject

open class ResourceBinding
    @Inject
    constructor(
        private val bindingName: String,
    ) : Named {
        override fun getName(): String = bindingName

        var modulePath: String = ""
        var output: String = ""
        var sourceSet: String = "main"
        var resourceRoot: String = ""
        var localDirectories: List<String> = emptyList()
    }

open class SentiaryExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        val android: NamedDomainObjectContainer<ResourceBinding> = objects.domainObjectContainer(ResourceBinding::class.java)
        val compose: NamedDomainObjectContainer<ResourceBinding> = objects.domainObjectContainer(ResourceBinding::class.java)

        fun android(action: Action<NamedDomainObjectContainer<ResourceBinding>>) = action.execute(android)

        fun compose(action: Action<NamedDomainObjectContainer<ResourceBinding>>) = action.execute(compose)
    }

internal data class Binding(
    val name: String,
    val kind: String,
    val modulePath: String,
    val output: String,
    val sourceSet: String,
    val resourceRoot: String,
    val localDirectories: List<String>,
) : Serializable

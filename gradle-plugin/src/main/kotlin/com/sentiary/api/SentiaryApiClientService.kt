package com.sentiary.api

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class SentiaryApiClientService : BuildService<SentiaryApiClientService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val sentiaryUrl: Property<String>
        val projectId: Property<String>
        val projectApiKey: Property<String>
        val requestTimeoutMillis: Property<Long>
    }

    val client: SentiaryApiClient by lazy {
        SentiaryApiClient(
            baseUrl = parameters.sentiaryUrl.get(),
            projectId = parameters.projectId.get(),
            projectApiKey = parameters.projectApiKey.get(),
            requestTimeoutMillis = parameters.requestTimeoutMillis.get()
        )
    }

    override fun close() {
        client.close()
    }
}

package com.sentiary.api

import com.sentiary.config.Format
import com.sentiary.model.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File

class SentiaryApiClient(
    private val baseUrl: String,
    private val projectId: String,
    private val projectApiKey: String,
    private val requestTimeoutMillis: Long,
) {
    private val client = HttpClient(CIO) {
        expectSuccess = true

        defaultRequest {
            url(baseUrl)
            header(HttpHeaders.Authorization, "Ribbon $projectApiKey")
        }

        engine {
            requestTimeout = requestTimeoutMillis
        }

        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun getProjectInfo(): Result<ProjectInfo> = runCatching {
        client
            .get(Routes.Project(projectId).info)
            .body<ProjectInfo>()
    }

    suspend fun fetchLocalization(
        language: String,
        format: Format,
        outputFile: File,
    ): Result<Unit> = runCatching {
        val channel = outputFile.writeChannel()

        client.get(Routes.Project(projectId).export) {
            parameter("languageId", language)
            parameter("format", format.apiName)
        }.bodyAsChannel().copyAndClose(channel)
    }

    fun close() {
        client.close()
    }

    private object Routes {
        class Project(projectId: String) {
            val export = "/api/v1/batch/$projectId/export"
            val info = "/api/v1/batch/$projectId/info"
        }
    }
}

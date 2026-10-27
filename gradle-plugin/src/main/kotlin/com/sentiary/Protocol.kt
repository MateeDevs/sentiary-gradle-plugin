package com.sentiary

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.gradle.api.GradleException
import java.io.File

internal object Protocol {
    fun binding(binding: Binding): String =
        Gson().toJson(
            mapOf(
                "output" to binding.output,
                "format" to binding.kind,
                "resourceRoot" to binding.resourceRoot,
            ),
        )

    fun request(bindings: List<String>): String = """{"protocolVersion":1,"bindings":[${bindings.joinToString(",")}]}"""

    fun checkVersion(json: String) {
        val version = JsonParser.parseString(json).asJsonObject
        if (version.getAsJsonArray("integrationProtocols")?.any { it.asInt == 1 } != true) {
            throw GradleException("Sentiary CLI does not support integration protocol 1")
        }
    }

    fun files(
        json: String,
        output: String,
    ): List<Pair<File, String>> {
        val result = JsonParser.parseString(json).asJsonObject
        require(result.get("protocolVersion")?.asInt == 1) { "Unsupported Sentiary result protocol" }
        return result
            .getAsJsonArray("files")
            .map { it.asJsonObject }
            .filter { it.get("output").asString == output }
            .map { File(it.get("path").asString) to it.get("sha256").asString }
    }
}

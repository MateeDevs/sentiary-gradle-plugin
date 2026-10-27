package com.sentiary

import org.gradle.api.GradleException
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal object CliDistribution {
    const val RELEASES = "https://github.com/MateeDevs/sentiary-tools/releases/download"

    fun platform(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): String {
        val system = when {
            os.lowercase().contains("mac") -> "darwin"
            os.lowercase().contains("windows") -> "windows"
            os.lowercase().contains("linux") -> "linux"
            else -> throw GradleException("Unsupported Sentiary CLI OS: $os")
        }
        val cpu = when (arch.lowercase()) {
            "amd64", "x86_64" -> "amd64"
            "arm64", "aarch64" -> "arm64"
            else -> throw GradleException("Unsupported Sentiary CLI architecture: $arch")
        }
        return "$system-$cpu"
    }

    @Synchronized
    fun install(
        cache: File,
        version: String,
        offline: Boolean,
        releases: String = RELEASES,
    ): File {
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid Sentiary CLI version" }
        val platform = platform()
        val directory = cache.resolve("$version/$platform").apply { mkdirs() }
        val asset = "sentiary-cli-$platform" + if (platform.startsWith("windows")) ".exe" else ""
        val binary = directory.resolve(asset)
        FileChannel.open(directory.resolve("install.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val manifest = directory.resolve("SHA256SUMS")
                if (binary.isFile && manifest.isFile && valid(binary, manifest.readText(), asset)) return binary
                if (offline) {
                    throw GradleException(
                        "Sentiary CLI $version is missing or corrupt; run online once or set sentiary.cliExecutable",
                    )
                }
                val checksums = fetch("$releases/$version/SHA256SUMS").toString(Charsets.UTF_8)
                val temporary = Files.createTempFile(directory.toPath(), ".download-", ".tmp").toFile()
                try {
                    temporary.writeBytes(fetch("$releases/$version/$asset"))
                    if (!valid(temporary, checksums, asset)) throw GradleException("Sentiary CLI checksum mismatch for $asset")
                    if (!platform.startsWith("windows") &&
                        !temporary.setExecutable(true, false)
                    ) {
                        throw GradleException("Cannot make Sentiary CLI executable")
                    }
                    Files.move(temporary.toPath(), binary.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    val stagedManifest = Files.createTempFile(directory.toPath(), ".manifest-", ".tmp").toFile()
                    try {
                        stagedManifest.writeText(checksums)
                        Files.move(
                            stagedManifest.toPath(),
                            manifest.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        stagedManifest.delete()
                    }
                } finally {
                    temporary.delete()
                }
                return binary
            }
        }
    }

    private fun fetch(url: String): ByteArray =
        URI(url)
            .toURL()
            .openConnection()
            .apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }.getInputStream()
            .use { it.readBytes() }

    private fun valid(
        file: File,
        manifest: String,
        asset: String,
    ): Boolean {
        val checksums = manifest
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 2 && it[1].removePrefix("*") == asset }
            .toList()
        return checksums.size == 1 &&
            checksums.single()[0].matches(Regex("[a-fA-F0-9]{64}")) &&
            digest(file).equals(checksums.single()[0], ignoreCase = true)
    }

    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

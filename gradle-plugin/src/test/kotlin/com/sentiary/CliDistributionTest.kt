package com.sentiary

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliDistributionTest {
    @TempDir lateinit var directory: File

    @Test fun `downloads verified binary once and reuses offline`() {
        server { url, requests ->
            val binary = CliDistribution.install(directory, "v2", false, url)
            assertEquals("binary", binary.readText())
            assertTrue(binary.canExecute())
            assertEquals(binary, CliDistribution.install(directory, "v2", true, url))
            assertEquals(2, requests.get())
        }
    }

    @Test fun `rejects corrupt download without installing it`() {
        server(corrupt = true) { url, _ ->
            assertFailsWith<Exception> { CliDistribution.install(directory, "v2", false, url) }
            assertTrue(directory.walk().none { it.isFile && it.name.startsWith("sentiary-cli") })
        }
    }

    @Test fun `offline missing binary fails without network`() {
        assertFailsWith<Exception> { CliDistribution.install(directory, "v2", true, "http://127.0.0.1:1") }
    }

    @Test fun `parallel installs publish one complete executable`() {
        server { url, requests ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (1..4).map { pool.submit<File> { CliDistribution.install(directory, "v2", false, url) } }
                assertEquals(1, futures.map { it.get().absolutePath }.distinct().size)
                assertEquals(2, requests.get())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun `detects corrupted installed binary offline`() {
        server { url, _ ->
            CliDistribution.install(directory, "v2", false, url).writeText("broken")
            assertFailsWith<Exception> { CliDistribution.install(directory, "v2", true, url) }
        }
    }

    @Test fun `all six native platforms have stable asset names`() {
        assertEquals("linux-amd64", CliDistribution.platform("Linux", "x86_64"))
        assertEquals("linux-arm64", CliDistribution.platform("Linux", "aarch64"))
        assertEquals("darwin-amd64", CliDistribution.platform("Mac OS X", "x86_64"))
        assertEquals("darwin-arm64", CliDistribution.platform("Mac OS X", "aarch64"))
        assertEquals("windows-amd64", CliDistribution.platform("Windows 11", "amd64"))
        assertEquals("windows-arm64", CliDistribution.platform("Windows 11", "arm64"))
    }

    @Test fun `interrupted response leaves no installed binary or temporary download`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path.endsWith("SHA256SUMS")) {
                val bytes = ("0".repeat(64) + "  sentiary-cli-${CliDistribution.platform()}\n").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.close()
            }
        }
        server.start()
        try {
            assertFailsWith<Exception> { CliDistribution.install(directory, "v2", false, "http://127.0.0.1:${server.address.port}") }
            assertTrue(directory.walk().none { it.isFile && (it.name.startsWith("sentiary-cli") || it.name.endsWith(".tmp")) })
        } finally {
            server.stop(0)
        }
    }

    private fun server(
        corrupt: Boolean = false,
        block: (String, AtomicInteger) -> Unit,
    ) {
        val content = directory.resolve("fixture").apply { writeText("binary") }
        val asset = "sentiary-cli-${CliDistribution.platform()}"
        val checksum = if (corrupt) "0".repeat(64) else CliDistribution.digest(content)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val bytes = if (exchange.requestURI.path.endsWith("SHA256SUMS")) "$checksum  $asset\n".toByteArray() else "binary".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }
}

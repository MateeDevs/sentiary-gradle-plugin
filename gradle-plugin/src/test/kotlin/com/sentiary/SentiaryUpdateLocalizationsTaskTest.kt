package com.sentiary

import com.sentiary.model.ProjectInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.gradle.internal.cc.base.logger
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.days

class SentiaryUpdateLocalizationsTaskTest {

    @TempDir
    lateinit var projectDir: File

    private lateinit var buildFile: File
    private lateinit var runner: GradleRunner
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private val yesterday: Instant = Clock.System.now() - 1.days
    private val now: Instant = Clock.System.now()

    private var mockProjectInfo = ProjectInfo("1", "Test", listOf("en", "de"), termsLastModified = now)
    private var mockEnLocalization = """<resources><string name="hello">Hello</string></resources>"""
    private var mockDeLocalization = """<resources><string name="hello">Hallo</string></resources>"""

    @BeforeEach
    fun setup() {
        buildFile = projectDir.resolve("build.gradle.kts")
        runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath() // Use the plugin from the current project build
    }

    @AfterEach
    fun teardown() {
        server?.stop(100, 100)
    }

    private fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) { json() }
            routing {
                get("/api/v1/batch/{projectId}/info") {
                    call.respond(mockProjectInfo)
                }
                get("/api/v1/batch/{projectId}/export") {
                    when (call.request.queryParameters["languageId"]) {
                        "en" -> call.respondText(mockEnLocalization)
                        "de" -> call.respondText(mockDeLocalization)
                        else -> call.respondText("", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }.start()
    }

    private fun getPort(): Int = runBlocking {
        server?.engine?.resolvedConnectors()?.first()?.port ?: throw IllegalStateException("Server not started")
    }

    private fun writeBuildFile() {
        buildFile.writeText("""
            import com.sentiary.config.Format

            plugins {
                id("com.sentiary.gradle")
            }

            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                defaultLanguage = "en"

                exportPaths {
                    create("android") {
                        format = Format.Android
                        outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
                    }
                }
            }
        """.trimIndent())
    }

    private fun runTask(vararg arguments: String): org.gradle.testkit.runner.BuildResult {
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/" + "--info"
        return runner.withArguments(args).build()
    }

    private fun runAndFail(vararg arguments: String): org.gradle.testkit.runner.BuildResult {
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/"
        return runner.withArguments(args).buildAndFail()
    }

    @Test
    fun `task runs when remote is newer`() {
        // Arrange
        mockProjectInfo = mockProjectInfo.copy(termsLastModified = now)
        startServer()
        writeBuildFile()
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(yesterday.toString())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.output shouldContain "BUILD SUCCESSFUL"
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        cacheFile.readText() shouldBe now.toString()
        projectDir.resolve("src/main/res/values/strings.xml").readText() shouldBe mockEnLocalization
    }

    @Test
    fun `task is up-to-date when cache is fresh`() {
        // Arrange
        startServer()
        writeBuildFile()

        // --- First run: Execute the task to create a history ---
        mockProjectInfo = mockProjectInfo.copy(termsLastModified = now)
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(yesterday.toString())

        val result1 = runTask("sentiaryUpdateLocalizations")
        result1.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS

        // --- Second run: Assert that the task is now up-to-date ---
        // The cache file now contains `now`. The remote `ProjectInfo` still has `now`.
        // The task should be up-to-date.
        val result2 = runTask("sentiaryUpdateLocalizations")

        // Assert
        result2.output shouldContain "BUILD SUCCESSFUL"
        result2.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.UP_TO_DATE
    }

    @Test
    fun `task runs when output file is missing`() {
        // Arrange
        mockProjectInfo = mockProjectInfo.copy(termsLastModified = now)
        startServer()
        writeBuildFile()
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(now.toString())
        // One file is missing
        projectDir.resolve("src/main/res/values/strings.xml").apply { parentFile.mkdirs() }.writeText(mockEnLocalization)

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.output shouldContain "BUILD SUCCESSFUL"
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
    }

    @Test
    fun `task runs with force-update`() {
        // Arrange
        mockProjectInfo = mockProjectInfo.copy(termsLastModified = yesterday)
        startServer()
        writeBuildFile()
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(now.toString())
        projectDir.resolve("src/main/res/values/strings.xml").apply { parentFile.mkdirs() }.writeText(mockEnLocalization)
        projectDir.resolve("src/main/res/values-de/strings.xml").apply { parentFile.mkdirs() }.writeText(mockDeLocalization)

        // Act
        val result = runTask("sentiaryUpdateLocalizations", "--force-update")

        // Assert
        result.output shouldContain "BUILD SUCCESSFUL"
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
    }
}

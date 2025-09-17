package com.sentiary

import com.sentiary.model.ProjectInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
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

    private var mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US", "de-DE"), termsLastModified = now)
    private var mockEnLocalization = """<resources><string name="hello">Hello</string></resources>"""
    private var mockDeLocalization = """<resources><string name="hello">Hallo</string></resources>"""
    private var apiShouldFail = false

    @BeforeEach
    fun setup() {
        buildFile = projectDir.resolve("build.gradle.kts")
        runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
    }

    @AfterEach
    fun teardown() {
        server?.stop(100, 100)
        apiShouldFail = false
    }

    private fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) { json() }
            routing {
                get("/api/v1/batch/{projectId}/info") {
                    if (apiShouldFail) {
                        call.respondText("Internal Server Error", status = HttpStatusCode.InternalServerError)
                    } else {
                        call.respond(mockProjectInfo)
                    }
                }
                get("/api/v1/batch/{projectId}/export") {
                    when (call.request.queryParameters["languageId"]) {
                        "en-US" -> call.respondText(mockEnLocalization)
                        "de-DE" -> call.respondText(mockDeLocalization)
                        else -> call.respondText("", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start()
    }

    private fun getPort(): Int = runBlocking {
        server?.engine?.resolvedConnectors()?.first()?.port ?: throw IllegalStateException("Server not started")
    }

    private fun writeBuildFile(buildScript: String) {
        buildFile.writeText(buildScript)
    }

    private fun runTask(vararg arguments: String): org.gradle.testkit.runner.BuildResult {
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/" + "--stacktrace"
        return runner.withArguments(args).build()
    }

    private fun runAndFail(vararg arguments: String): org.gradle.testkit.runner.BuildResult {
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/" + "--stacktrace"
        return runner.withArguments(args).buildAndFail()
    }

    @Test
    fun `task runs when remote is newer`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(yesterday.toString())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        cacheFile.readText() shouldBe now.toString()
        projectDir.resolve("src/main/res/values/strings.xml").readText() shouldBe mockEnLocalization
    }

    @Test
    fun `task is up-to-date when cache is fresh`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // --- First run: Execute the task to create a history ---
        mockProjectInfo = mockProjectInfo.copy(termsLastModified = now)
        val cacheFile = projectDir.resolve("build/sentiary/timestamp")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(yesterday.toString())
        val result1 = runTask("sentiaryUpdateLocalizations")
        result1.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS

        // --- Second run: Assert that the task is now up-to-date ---
        val result2 = runTask("sentiaryUpdateLocalizations")
        result2.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.UP_TO_DATE
    }

    @Test
    fun `handles language overrides`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                languageOverrides { create("en-GB") { fallbackTo = "en-US" } }
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        val gbFile = projectDir.resolve("src/main/res/values-en-GB/strings.xml")
        gbFile.exists() shouldBe true
        gbFile.readText() shouldBe mockEnLocalization
    }

    @Test
    fun `does not fetch disabled languages`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US", "de-DE"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                disabledLanguages = listOf("de-DE")
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res/values/strings.xml").exists() shouldBe true
        projectDir.resolve("src/main/res/values-de/strings.xml").exists() shouldBe false
    }

    @Test
    fun `build fails on api error`() {
        // Arrange
        apiShouldFail = true
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "500 Internal Server Error"
    }

    @Test
    fun `build fails when projectId is missing`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectApiKey = "test-api-key"
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Sentiary projectId and projectApiKey must be set"
    }

    @Test
    fun `task always runs when caching is disabled`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                caching { enabled = false }
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // --- First run ---
        val result1 = runTask("sentiaryUpdateLocalizations")
        result1.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS

        // --- Second run ---
        val result2 = runTask("sentiaryUpdateLocalizations")
        result2.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
    }
}
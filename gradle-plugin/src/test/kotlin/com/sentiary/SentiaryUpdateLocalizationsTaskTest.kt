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
    private var languagesToFail = mutableSetOf<String>()

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
        languagesToFail.clear()
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
                    val lang = call.request.queryParameters["languageId"]
                    if (lang in languagesToFail) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    when (lang) {
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
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/" + "--info"
        return runner.withArguments(args).build()
    }

    private fun runAndFail(vararg arguments: String): org.gradle.testkit.runner.BuildResult {
        val args = arguments.toList() + "-Dcom.sentiary.internal.test.url=http://localhost:${getPort()}/" + "--info"
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
        result.task(":sentiaryUpdateProjectInfo")?.outcome shouldBe TaskOutcome.FAILED
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
        result.task(":sentiaryUpdateProjectInfo")?.outcome shouldBe TaskOutcome.FAILED
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

    @Test
    fun `task succeeds with no languages from api`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", emptyList(), termsLastModified = now)
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
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        val outputDir = projectDir.resolve("src/main/res")
        // The parent directory gets created, but it should be empty
        outputDir.exists() shouldBe true
        outputDir.listFiles()?.isEmpty() shouldBe true
    }

    @Test
    fun `task succeeds with no export paths configured`() {
        // Arrange
        startServer()
        writeBuildFile("""
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res").exists() shouldBe false
    }

    @Test
    fun `build fails with read-only output directory`() {
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
        val outputDir = projectDir.resolve("src/main/res")
        outputDir.mkdirs()
        outputDir.setReadOnly()

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
    }

    @Test
    fun `respects custom naming strategies`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                defaultLanguage = "en-US"
                exportPaths {
                    create("android") {
                        format = Format.Android
                        outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
                        folderNamingStrategy { language, isDefault -> if (isDefault) "values-en-default" else "values-lang-${'$'}language" }
                        fileNamingStrategy { _, _ -> "my_strings.xml" }
                    }
                }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res/values-en-default/my_strings.xml").exists() shouldBe true
        projectDir.resolve("src/main/res/values-lang-de-DE/my_strings.xml").exists() shouldBe true
    }

    @Test
    fun `handles multiple export paths`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths {
                    create("android") {
                        format = Format.Android
                        outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
                    }
                    create("json") {
                        format = Format.Android // Using Android format for simplicity, just to test file writing
                        outputDirectory.set(layout.projectDirectory.dir("src/main/json"))
                    }
                }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res/values/strings.xml").exists() shouldBe true
        projectDir.resolve("src/main/json/values/strings.xml").exists() shouldBe true
    }

    @Test
    fun `handles chained language fallbacks`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                languageOverrides {
                    create("en-CA") { fallbackTo = "en-GB" }
                    create("en-GB") { fallbackTo = "en-US" }
                }
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res/values-en-GB/strings.xml").readText() shouldBe mockEnLocalization
        projectDir.resolve("src/main/res/values-en-CA/strings.xml").readText() shouldBe mockEnLocalization
    }

    @Test
    fun `uses credentials from gradle properties`() {
        // Arrange
        startServer()
        projectDir.resolve("gradle.properties").writeText("""
            sentiary.projectId=prop-project-id
            sentiary.projectApiKey=prop-api-key
        """.trimIndent())
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                // Credentials are not set here, should be picked from properties
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
    }

    @Test
    fun `fails when chained language fallbacks are not available`() {
        // Arrange
        // API provides en-US, but the chain is en-AU -> en-GB. The link is broken because en-GB is not provided.
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                languageOverrides {
                    create("en-AU") { fallbackTo = "en-GB" }
                }
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Could not resolve fallback for 'en-AU'. The dependency 'en-GB' is missing."
    }

    @Test
    fun `fails on circular dependency in language fallbacks`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                languageOverrides {
                    create("en-CA") { fallbackTo = "en-GB" }
                    create("en-GB") { fallbackTo = "en-CA" } // Cycle!
                }
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Could not resolve language overrides due to a circular dependency."
        projectDir.resolve("src/main/res/values-en-CA").exists() shouldBe false
        projectDir.resolve("src/main/res/values-en-GB").exists() shouldBe false
    }

    @Test
    fun `defaultLanguage is ignored if also disabled`() {
        // Arrange
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US", "de-DE"), termsLastModified = now)
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                defaultLanguage = "en-US"
                disabledLanguages = listOf("en-US") // Disabling the default
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) } }
            }
        """.trimIndent())

        // Act
        val result = runTask("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.SUCCESS
        projectDir.resolve("src/main/res/values").exists() shouldBe false // Default "values" folder should not be created
        projectDir.resolve("src/main/res/values-de-DE").exists() shouldBe true
    }

    @Test
    fun `fails with duplicate export path directories`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths {
                    create("android1") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) }
                    create("android2") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res")) }
                }
            }
        """.trimIndent())

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Duplicate output directories found"
    }

    @Test
    fun `fails with read-only parent of output directory`() {
        // Arrange
        startServer()
        writeBuildFile("""
            import com.sentiary.config.Format
            plugins { id("com.sentiary.gradle") }
            sentiary {
                projectId = "test-project"
                projectApiKey = "test-api-key"
                exportPaths { create("android") { format = Format.Android; outputDirectory.set(layout.projectDirectory.dir("src/main/res/values")) } }
            }
        """.trimIndent())
        val parentDir = projectDir.resolve("src/main/res")
        parentDir.mkdirs()
        parentDir.setReadOnly()

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Failed to create directory"
    }

    @Test
    fun `fails when cache file is a directory`() {
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
        projectDir.resolve("build/sentiary/timestamp").mkdirs() // Create a directory where the file should be

        // Act
        val result = runAndFail("sentiaryUpdateLocalizations")

        // Assert
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Is a directory"
    }

    @Test
    fun `fails when api returns 404 for a specific language`() {
        // Arrange
        // Project info lists 'de-DE', but the export endpoint will 404 for it.
        languagesToFail.add("de-DE")
        mockProjectInfo = ProjectInfo("1", "Test", listOf("en-US", "de-DE"), termsLastModified = now)
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
        result.task(":sentiaryUpdateLocalizations")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "BUILD FAILED"
        result.output shouldContain "Failed to fetch localization for de-DE"
    }
}

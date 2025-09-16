package com.sentiary

import com.sentiary.config.CacheConfiguration
import com.sentiary.config.DefaultFolderNamingStrategy
import com.sentiary.config.ExportPath
import com.sentiary.config.FileNamingStrategyFromFormat
import com.sentiary.config.Format
import com.sentiary.config.LanguageOverride
import com.sentiary.model.ProjectInfo
import com.sentiary.api.SentiaryApiClient
import com.sentiary.task.SentiaryWorker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.days

class SentiaryWorkerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var mockApiClient: SentiaryApiClient
    private lateinit var mockLogger: Logger
    private lateinit var mockCacheConfiguration: CacheConfiguration
    private lateinit var mockExportPath: ExportPath
    private lateinit var cacheFile: File

    private val now: Instant = Clock.System.now()
    private val yesterday: Instant = now - 1.days

    @BeforeEach
    fun setup() {
        mockApiClient = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)
        mockCacheConfiguration = mockk(relaxed = true)
        mockExportPath = mockk(relaxed = true)
        cacheFile = tempDir.resolve("cache/last-modified")

        every { mockCacheConfiguration.enabled.get() } returns true
        every { mockExportPath.format.get() } returns Format.Android

        val resDir = tempDir.resolve("res")
        val mockOutputDirectory = mockk<Directory> {
            every { asFile } returns resDir
            every { dir(any<String>()) } answers {
                val subDirName = firstArg<String>()
                val subDir = resDir.resolve(subDirName)
                mockk {
                    every { asFile } returns subDir
                    every { file(any<String>()) } answers {
                        val fileName = firstArg<String>()
                        val file = subDir.resolve(fileName)
                        mockk {
                            every { asFile } returns file
                        }
                    }
                }
            }
        }
        every { mockExportPath.outputDirectory.get() } returns mockOutputDirectory
        every { mockExportPath.folderNamingStrategy.get() } returns DefaultFolderNamingStrategy
        every { mockExportPath.fileNamingStrategy.get() } returns FileNamingStrategyFromFormat(Format.Android)
    }

    private fun createWorker(
        languageOverrides: List<LanguageOverride> = emptyList(),
        exportPaths: List<ExportPath> = listOf(mockExportPath),
        forceUpdate: Boolean = false,
    ): SentiaryWorker {
        return SentiaryWorker(
            client = mockApiClient,
            logger = mockLogger,
            defaultLanguage = "en",
            languageOverrides = languageOverrides,
            exportPaths = exportPaths,
            cacheConfiguration = mockCacheConfiguration,
            cacheFile = cacheFile,
            forceUpdate = forceUpdate,
        )
    }

    @Test
    fun `run should download files when remote is newer`() {
        // Arrange
        val projectInfo = ProjectInfo("1", "Test", listOf("en", "de"), termsLastModified = now)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        cacheFile.apply { parentFile.mkdirs() }.writeText(yesterday.toString())
        val worker = createWorker()

        // Act
        val result = worker.run()

        // Assert
        result shouldBe true
        coVerify { mockApiClient.fetchLocalization(any(), any(), any()) }
        cacheFile.readText() shouldBe now.toString()
    }

    @Test
    fun `run should not download files when cache is up to date`() {
        // Arrange
        val projectInfo = ProjectInfo("1", "Test", listOf("en", "de"), termsLastModified = yesterday)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        cacheFile.apply { parentFile.mkdirs() }.writeText(now.toString())
        // Ensure output files exist
        tempDir.resolve("res/values/strings.xml").apply { parentFile.mkdirs(); createNewFile() }
        tempDir.resolve("res/values-de/strings.xml").apply { parentFile.mkdirs(); createNewFile() }
        val worker = createWorker()

        // Act
        val result = worker.run()

        // Assert
        result shouldBe false
        coVerify(exactly = 0) { mockApiClient.fetchLocalization(any(), any(), any()) }
    }

    @Test
    fun `run should download files when output files are missing`() {
        // Arrange
        val projectInfo = ProjectInfo("1", "Test", listOf("en", "de"), termsLastModified = yesterday)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        cacheFile.apply { parentFile.mkdirs() }.writeText(now.toString())
        // One output file is missing
        tempDir.resolve("res/values/strings.xml").apply { parentFile.mkdirs(); createNewFile() }
        val worker = createWorker()

        // Act
        val result = worker.run()

        // Assert
        result shouldBe true
        coVerify(atLeast = 1) { mockApiClient.fetchLocalization(any(), any(), any()) }
    }

    @Test
    fun `run should handle language overrides with fallback`() {
        // Arrange
        val projectInfo = ProjectInfo("1", "Test", listOf("en"), termsLastModified = now)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        val mockOverride = mockk<LanguageOverride> {
            every { name } returns "en-GB"
            every { fetch.get() } returns true
            every { fallbackTo.get() } returns "en"
            every { fallbackTo.orNull } returns "en"
        }
        val worker = createWorker(languageOverrides = listOf(mockOverride))
        coEvery { mockApiClient.fetchLocalization("en", Format.Android, any()) } coAnswers {
            // Use the actual File object passed to the mock by the worker
            val outputFile = it.invocation.args[2] as File
            outputFile.apply { parentFile.mkdirs() }.writeText("english")
            Result.success(Unit)
        }

        // Act
        worker.run()

        // Assert
        val gbFile = tempDir.resolve("res/values-en-GB/strings.xml")
        gbFile.exists() shouldBe true
        gbFile.readText() shouldBe "english"
    }

    @Test
    fun `run should not fetch disabled languages`() {
        // Arrange
        val projectInfo = ProjectInfo("1", "Test", listOf("en", "de"), termsLastModified = now)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        val mockOverride = mockk<LanguageOverride> {
            every { name } returns "de"
            every { fetch.get() } returns false
        }
        val worker = createWorker(languageOverrides = listOf(mockOverride))

        // Act
        worker.run()

        // Assert
        coVerify(exactly = 1) { mockApiClient.fetchLocalization("en", any(), any()) }
        coVerify(exactly = 0) { mockApiClient.fetchLocalization("de", any(), any()) }
    }

    @Test
    fun `run should always download when caching is disabled`() {
        // Arrange
        every { mockCacheConfiguration.enabled.get() } returns false
        val projectInfo = ProjectInfo("1", "Test", listOf("en"), termsLastModified = yesterday)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        val initialCacheContent = now.toString()
        cacheFile.apply { parentFile.mkdirs() }.writeText(initialCacheContent)
        val worker = createWorker()

        // Act
        val result = worker.run()

        // Assert
        result shouldBe true
        coVerify(exactly = 1) { mockApiClient.fetchLocalization(any(), any(), any()) }
        cacheFile.readText() shouldBe initialCacheContent
    }

    @Test
    fun `run should force download when forceUpdate is true`() {
        // Arrange
        every { mockCacheConfiguration.enabled.get() } returns true
        val projectInfo = ProjectInfo("1", "Test", listOf("en"), termsLastModified = yesterday)
        coEvery { mockApiClient.getProjectInfo() } returns Result.success(projectInfo)
        cacheFile.apply { parentFile.mkdirs() }.writeText(now.toString())
        val worker = createWorker(forceUpdate = true)

        // Act
        val result = worker.run()

        // Assert
        result shouldBe true
        coVerify(exactly = 1) { mockApiClient.fetchLocalization(any(), any(), any()) }
    }
}

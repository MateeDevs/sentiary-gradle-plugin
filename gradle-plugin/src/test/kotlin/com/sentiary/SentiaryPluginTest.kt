package com.sentiary

import io.kotest.matchers.shouldNotBe
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class SentiaryPluginTest {

    @Test
    fun `plugin registers sentiaryFetch task`() {
        // Arrange
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.sentiary.gradle")

        // Act & Assert
        project.tasks.findByName("sentiaryFetch") shouldNotBe null
    }
}

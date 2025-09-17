package com.sentiary

import io.kotest.matchers.shouldNotBe
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class SentiaryPluginTest {

    @Test
    fun `plugin registers required tasks`() {
        // Arrange
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.sentiary.gradle")

        // Act & Assert
        project.tasks.findByName("sentiaryUpdateProjectInfo") shouldNotBe null
        project.tasks.findByName("sentiaryUpdateLocalizations") shouldNotBe null
    }
}

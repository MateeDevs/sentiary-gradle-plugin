plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    alias(libs.plugins.pluginPublish)
}

dependencies {
    implementation(libs.gson)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("sentiary.test.cli", providers.environmentVariable("SENTIARY_TEST_CLI").getOrElse(""))
}

kotlin {
    jvmToolchain(
        libs.versions.java
            .get()
            .toInt(),
    )
}

gradlePlugin {
    website.set("https://github.com/MateeDevs/sentiary-gradle-plugin")
    vcsUrl.set("https://github.com/MateeDevs/sentiary-gradle-plugin.git")
    plugins {
        create("sentiary") {
            id = "com.sentiary.gradle"
            implementationClass = "com.sentiary.SentiaryPlugin"
            version = project.version as String
            displayName = "Sentiary Gradle Plugin"
            description = "A Gradle plugin to download and manage localization files from the Sentiary platform."
            tags.set(listOf("sentiary", "strings", "localization", "translations"))
        }
    }
}

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
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

publishing {
    repositories {
        maven {
            name = "localPluginRepository"
            url = uri("../../local-plugin-repository")
        }
    }
}
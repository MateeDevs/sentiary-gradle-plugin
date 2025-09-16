plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.serialization)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(gradleApi())

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
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
            id = "com.sentiary"
            implementationClass = "com.sentiary.SentiaryPlugin"
            version = project.version as String
            displayName = "Sentiary Gradle Plugin"
            description = "A Gradle plugin to download and manage localization files from the Sentiary platform."
            tags.set(listOf("sentiary", "strings", "localization", "translations"))
        }
    }
}

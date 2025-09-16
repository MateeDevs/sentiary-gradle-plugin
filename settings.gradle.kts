enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}


dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://plugins.gradle.org/m2/")
    }
}

rootProject.name = "sentiary-gradle-plugin"
include(":gradle-plugin")
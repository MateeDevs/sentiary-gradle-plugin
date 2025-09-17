# Sentiary Gradle Plugin

The Sentiary Gradle plugin provides a convenient way to download and manage your localization files from the Sentiary platform. It automates the process of fetching the latest translations and integrating them into your Android project.

## Setup

To use the Sentiary Gradle plugin, you need to apply it in your `build.gradle.kts` file. For multi-project builds, it is recommended to apply the plugin in your root `build.gradle.kts` file.

First, apply the plugin:

```kotlin
plugins {
    id("com.sentiary.gradle") version "1.0.0"
}
```

## Configuration

The plugin can be configured using the `sentiary` extension in your `build.gradle.kts` file.

```kotlin
sentiary {
    // Sentiary project ID (required, see credentials sections)
    projectId = "YOUR_PROJECT_ID"

    // Sentiary project API key (required, see credentials sections)
    projectApiKey = "YOUR_PROJECT_API_KEY"

    // Default language for your project (optional, defaults to en-US)
    defaultLanguage = "en-US"

    // A list of language identifiers to prevent from being downloaded or generated.
    // This takes precedence over all other settings.
    disabledLanguages = listOf("fr-FR", "de-DE")

    // Language-specific overrides (optional).
    // Use this to create a language that is a copy of another. For example,
    // create an `sk-SK` localization that is a copy of the `cs-CZ` source.
    languageOverrides {
        create("sk-SK") {
            fallbackTo = "cs-CZ" // Language identifier for the language to use as the source.
        }
    }

    // Export paths for localization files (required)
    exportPaths {
        create("android") {
            // Export format
            format = com.sentiary.config.Format.Android

            // Path to the output directory
            outputDirectory.set(layout.projectDirectory.dir("src/main/res"))

            // Naming strategies for folders and files have sensible defaults
            // and can be optionally overridden here. For example:
            folderNamingStrategy { language, isDefault ->
                if (isDefault) "values-default" else "values-$language"
            }

            fileNamingStrategy { _, _ ->
                "custom_strings.xml"
            }
        }
    }

    // Caching configuration (optional)
    caching {
        enabled = true
    }
}
```

### Credentials

It is recommended to store your `projectId` and `projectApiKey` outside of your build script. The plugin resolves them in the following order of priority:

1.  **Gradle Properties**: In a `gradle.properties` or `local.properties` file.
    ```properties
    sentiary.projectId=YOUR_PROJECT_ID
    sentiary.projectApiKey=YOUR_PROJECT_API_KEY
    ```
2.  **Environment Variables**:
    ```bash
    export SENTIARY_PROJECT_ID="YOUR_PROJECT_ID"
    export SENTIARY_PROJECT_API_KEY="YOUR_PROJECT_API_KEY"
    ```
3.  **Directly in `build.gradle.kts`**: As a last resort, you can set them in the `sentiary` block.

The plugin will automatically pick up these properties.

## Tasks

The plugin provides the following tasks:

### `sentiaryUpdateProjectInfo`

This task updates a local cache of the Sentiary project information, including language configurations and modification timestamps. It is primarily used as an input for the `sentiaryUpdateLocalizations` task and does not need to be run manually.

### `sentiaryUpdateLocalizations`

This is the main task to run to get the newest translations. It downloads and exports the localization files from Sentiary based on your configuration.

Because this task writes into a shared source directory (like `src/main/res`), you **must** manually declare a dependency on it for any task that consumes the generated files. This ensures that your project always builds with the latest translations.

You can run the task from the command line:

```bash
./gradlew sentiaryUpdateLocalizations
```

To force the task to download the latest localizations regardless of the cache state, you can use the `--force-update` flag:

```bash
./gradlew sentiaryUpdateLocalizations --force-update
```

### Usage Examples

Here are some common use cases for integrating the Sentiary plugin. When applying the plugin to your root `build.gradle.kts` file, you can use the `subprojects` block to configure task dependencies for the relevant subprojects.

The following snippet shows how to configure dependencies for standard Android, Compose Multiplatform, and Moko Resources projects. You can use any or all of these configurations as needed.

```kotlin
// In your root build.gradle.kts
val sentiaryUpdateLocalizationsTask = tasks.named<com.sentiary.task.SentiaryUpdateLocalizationsTask>("sentiaryUpdateLocalizations")

subprojects {
    // For standard Android Applications
    plugins.withId("com.android.application") {
        tasks.withType<com.android.build.gradle.tasks.ProcessAndroidResources> {
            dependsOn(sentiaryUpdateLocalizationsTask)
        }
    }

    // For JetBrains Compose for Multiplatform
    plugins.withId("org.jetbrains.compose") {
        tasks.named("generateComposeResClass") {
            dependsOn(sentiaryUpdateLocalizationsTask)
        }
    }

    // For Moko Resources in a Kotlin Multiplatform project
    plugins.withId("dev.icerock.mobile.multiplatform-resources") {
        tasks.named("generateMRcommonMain") {
            dependsOn(sentiaryUpdateLocalizationsTask)
        }
    }
}
```

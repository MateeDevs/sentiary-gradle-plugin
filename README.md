# Sentiary Gradle Plugin

The Sentiary Gradle plugin provides a convenient way to download and manage your localization files from the Sentiary platform. It automates the process of fetching the latest translations and integrating them into your Android project.

## Setup

To use the Sentiary Gradle plugin, you need to apply it in your `build.gradle.kts` file and configure it with your project details.

First, apply the plugin in your project's `build.gradle.kts`:

```kotlin
plugins {
    id("com.sentiary") version "1.0.0"
}
```

## Configuration

The plugin can be configured using the `sentiary` extension in your `build.gradle.kts` file.

```kotlin
sentiary {
    // Sentiary API URL (optional, defaults to https://api.sentiary.com/)
    sentiaryUrl = "https://api.sentiary.com/"

    // Sentiary project ID (required, see credentials sections)
    projectId = "YOUR_PROJECT_ID"

    // Sentiary project API key (required, see credentials sections)
    projectApiKey = "YOUR_PROJECT_API_KEY"

    // Network request timeout in milliseconds (optional, defaults to 100000)
    requestTimeoutMillis = 100_000L

    // Default language for your project (optional, defaults to en-US)
    defaultLanguage = "en-US"

    // Language-specific overrides (optional)
    languageOverrides {
        create("cs-CZ") {
            fetch = true // or false to disable fetching for this language
            fallbackTo = "en-US" // Language identifier for the language to use as the source of this language.
        }
    }

    // Export paths for localization files (required)
    exportPaths {
        create("android") {
            // Export format (e.g., ANDROID_XML, IOS_STRINGS)
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
        cacheFilePath.set(layout.buildDirectory.dir("sentiary").file("last-modified"))
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

The plugin provides the following task:

### `sentiaryFetch`

This task downloads and exports the localization files from Sentiary based on your configuration. It is recommended to run this task before building your project to ensure you have the latest translations. If caching is not enabled, this may significantly increase your build times.

You can run the task from the command line:

```bash
./gradlew sentiaryFetch
```

To force the task to download the latest localizations regardless of the cache state, you can use the `--force-update` flag:

```bash
./gradlew sentiaryFetch --force-update
```

Or you can make it a dependency of another task, for example `preBuild`:

```kotlin
val sentiaryFetchTask = tasks.named<com.sentiary.task.SentiaryFetchTask>("sentiaryFetch")

tasks.named("preBuild") {
    dependsOn(sentiaryFetchTask)
}
```

<details>
<summary>Compose Resources configuration</summary>

```kts
val sentiaryFetchTask = tasks.named<com.sentiary.task.SentiaryFetchTask>("sentiaryFetch")

plugins.withId("org.jetbrains.compose") {
    tasks.matching {
        it.name in listOf(
            "generateComposeResClass",
            "copyNonXmlValueResourcesForCommonMain",
            "convertXmlValueResourcesForCommonMain",
        )
    }.configureEach {
        dependsOn(sentiaryFetchTask)
    }
}
```
</details>
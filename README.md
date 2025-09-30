# Sentiary Gradle Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.sentiary.gradle)](https://plugins.gradle.org/plugin/com.sentiary.gradle)

The Sentiary Gradle plugin provides a convenient way to download and manage your localization files
from the Sentiary platform. It automates the process of fetching the latest translations and
integrating them into your Android or Kotlin Multiplatform project.

## Features

- **Automated Downloads**: Fetches and updates localization files automatically.
- **Flexible Configuration**: Easily configure output formats, language overrides, and naming
  strategies.
- **Caching**: Avoids unnecessary downloads by caching project information and translations.

## Getting Started

To use the Sentiary Gradle plugin, you need to apply it to your project.

### 1. Apply the Plugin

It is recommended to apply the plugin in your `build.gradle.kts` file.

```kotlin
// build.gradle.kts
plugins {
    id("com.sentiary.gradle") version "<plugin-version>"
}
```

### 2. Configure the Plugin

Add the `sentiary` block to your `build.gradle.kts` and provide the necessary configuration. At a
minimum, you must specify your project credentials and define at least one export path.

```kotlin
sentiary {
    // Sentiary project ID (see Credentials section)
    projectId = "YOUR_PROJECT_ID"

    // Sentiary project API key (see Credentials section)
    projectApiKey = "YOUR_PROJECT_API_KEY"

    // Define where and how to export localization files
    exportPaths {
        create("android") {
            format.set(com.sentiary.config.Format.Android)
            outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
        }
    }
}
```

## Configuration

### Credentials

It is strongly recommended to store your `projectId` and `projectApiKey` outside of your build
script. The plugin resolves them in the following order of priority:

1. **Gradle Properties**: In a `gradle.properties` or `local.properties` file.
   ```properties
   sentiary.projectId=YOUR_PROJECT_ID
   sentiary.projectApiKey=YOUR_PROJECT_API_KEY
   ```
2. **Environment Variables**:
   ```bash
   export SENTIARY_PROJECT_ID="YOUR_PROJECT_ID"
   export SENTIARY_PROJECT_API_KEY="YOUR_PROJECT_API_KEY"
   ```
3. **Directly in `build.gradle.kts`**: As a last resort, you can set them in the `sentiary` block.

### Plugin DSL Reference

The `sentiary` extension offers the following configuration options:

| Property               | Type                               | Description                                                                                             | Default Value      |
| ---------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------ |
| `projectId`            | `Property<String>`                 | **Required.** Your Sentiary project ID.                                                                 | -                  |
| `projectApiKey`        | `Property<String>`                 | **Required.** Your Sentiary project API key.                                                            | -                  |
| `defaultLanguage`      | `Property<String>`                 | The IETF BCP 47 language tag for the default language.                                                  | `"en-US"`          |
| `disabledLanguages`    | `ListProperty<String>`             | A list of language tags to exclude from downloads.                                                      | `[]` (empty list)  |
| `requestTimeoutMillis` | `Property<Long>`                   | Network request timeout in milliseconds.                                                                | `100000`           |
| `languageOverrides`    | `NamedDomainObjectContainer<...>`  | Defines rules to create new language localizations based on existing ones. See example below.           | -                  |
| `exportPaths`          | `NamedDomainObjectContainer<...>`  | **Required.** Defines output configurations for localization files. See example below.                  | -                  |
| `caching`              | `CacheConfiguration`               | Configures caching behavior.                                                                            | `enabled = true`   |

#### `languageOverrides`

Use this to create a language that is a copy of another. For example, to create an `sk-SK` localization that is a copy of `cs-CZ`. Transitive fallbacks are also supported.

```kotlin
sentiary {
    languageOverrides {
        create("sk-SK") {
            fallbackTo.set("cs-CZ") // Language tag for the source language.
        }
    }
}
```

#### `exportPaths`

This is where you define how your localization files are generated. You can have multiple export
paths for different formats or modules.

```kotlin
sentiary {
    exportPaths {
        create("android") {
            // Export format. Available formats: Android, ComposeResources, Json, Apple.
            format.set(com.sentiary.config.Format.Android)

            // Path to the output directory.
            outputDirectory.set(layout.projectDirectory.dir("src/main/res"))

            // (Optional) Naming strategies for folders and files.
            // The plugin provides sensible defaults based on the chosen format.
            folderNamingStrategy { language, isDefault ->
                if (isDefault) "values" else "values-$language"
            }

            fileNamingStrategy { _, _ ->
                "strings.xml"
            }
        }
    }
}
```

## Tasks

The plugin provides the following tasks:

### `sentiaryUpdateLocalizations`

This is the main task to run to get the newest translations. It downloads and exports the
localization files from Sentiary based on your configuration.

You can run the task manually from the command line:

```bash
./gradlew sentiaryUpdateLocalizations
```

To force the task to download the latest localizations regardless of the cache state, use the
`--force-update` flag:

```bash
./gradlew sentiaryUpdateLocalizations --force-update
```

### `sentiaryUpdateProjectInfo`

This task updates a local cache of the Sentiary project information. It runs automatically as a
dependency of `sentiaryUpdateLocalizations` and does not need to be run manually.

## Usage Examples

To automatically update your localizations as part of your regular build process, you should declare a dependency on the `sentiaryUpdateLocalizations` task for any task that consumes the generated files. This ensures your project always builds with the latest translations. If you prefer to update localizations manually, you can simply run the `./gradlew sentiaryUpdateLocalizations` task as needed and skip adding the explicit dependency.

### Single-Module Project

In a single-module project, apply and configure the plugin in your `build.gradle.kts` file. Then, hook the `sentiaryUpdateLocalizations` task into the build process.

For an Android project, make the `process[Variant]AndroidResources` tasks depend on it:

```kotlin
tasks.withType<com.android.build.gradle.tasks.ProcessAndroidResources> {
    dependsOn("sentiaryUpdateLocalizations")
}

sentiary {
    projectId = "YOUR_PROJECT_ID"
    projectApiKey = "YOUR_PROJECT_API_KEY"
    exportPaths {
        create("android") {
            format.set(com.sentiary.config.Format.Android)
            outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
        }
    }
}
```

### Multi-Module Project

In a multi-module setup, you should first make the plugin available to all subprojects by adding it to the root `build.gradle.kts` file with `apply false`. Then, the recommended approach is to apply the plugin and common configuration in a convention plugin, and apply that convention plugin to each sub-module.

#### 1. Declare the Plugin in the Root Project

In your root `build.gradle.kts`, declare the Sentiary plugin but do not apply it.

```kotlin
// root build.gradle.kts
plugins {
    id("com.sentiary.gradle") apply false
}
```

#### 2. Create a Convention Plugin

First, create a convention plugin (e.g., in `build-logic`) for Sentiary. This allows you to share the base configuration across modules.

```kotlin
// build-logic/src/main/kotlin/sentiary-convention.gradle.kts
plugins {
    id("com.sentiary.gradle")
}

extensions.configure<com.sentiary.SentiaryPluginExtension> {
    defaultLanguage.set("en-US")
    languageOverrides {
        create("sk-SK") {
            fallbackTo.set("cs-CZ")
        }
    }
}
```

You will also need to add the Sentiary plugin as a dependency in your convention plugin's build script:

```kotlin
// build-logic/build.gradle.kts
dependencies {
    compileOnly("com.sentiary:gradle-plugin:<plugin-version>")
}
```

#### 3. Apply to Sub-Modules

In each sub-module where you need localizations, apply your convention plugin and configure the module-specific `exportPaths`.

**Example for an Android Module:**

```kotlin
plugins {
    id("sentiary-convention")
}

tasks.withType<com.android.build.gradle.tasks.ProcessAndroidResources> {
    dependsOn("sentiaryUpdateLocalizations")
}

sentiary {
    exportPaths {
        create("android") {
            format.set(com.sentiary.config.Format.Android)
            outputDirectory.set(layout.projectDirectory.dir("src/main/res"))
        }
    }
}
```

**Example for a Kotlin Multiplatform (Compose Resources) Module:**

```kotlin
plugins {
    id("sentiary-convention")
}

tasks.named("prepareComposeResourcesTaskForCommonMain") {
    dependsOn("sentiaryUpdateLocalizations")
}

sentiary {
    exportPaths {
        create("composeResources") {
            format.set(com.sentiary.config.Format.ComposeResources)
            outputDirectory.set(layout.projectDirectory.dir("src/commonMain/composeResources"))
        }
    }
}
```

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

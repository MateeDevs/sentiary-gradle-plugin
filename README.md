# Sentiary Gradle plugin 2.0

> **This repository has moved.** Further development and issue reports belong in
> [MateeDevs/sentiary-tools](https://github.com/MateeDevs/sentiary-tools).
> See the [Gradle plugin documentation](https://github.com/MateeDevs/sentiary-tools/blob/staging/docs/gradle/index.md).
> The plugin ID remains `com.sentiary.gradle`. This repository is retained for historical reference.

A settings plugin that runs the Sentiary CLI and connects its named outputs to Android and Compose resources.
The CLI owns TOML parsing, credentials, languages, aliases, formats, downloads, cache, and generated-file ownership.
The plugin owns CLI installation and build resource bindings.

## Documentation

See the [Sentiary documentation sources](https://github.com/MateeDevs/sentiary-tools/tree/main/docs)
for the CLI, shared TOML configuration, and migration guidance.
[Gradle plugin 2.0 documentation](https://github.com/MateeDevs/sentiary-tools/blob/main/docs/gradle/index.md)
is **unreleased** and must be checked against its completed implementation.

## Setup

Apply the plugin once in `settings.gradle.kts`, after configuring plugin repositories:

```kotlin
pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
plugins { id("com.sentiary.gradle") version "2.0.0" }
include(":app", ":shared")

sentiary {
    android {
        create("app") {
            modulePath = ":app"
            output = "android"
            sourceSet = "main"
            resourceRoot = "app/build/generated/sentiary"
        }
    }
    compose {
        create("shared") {
            modulePath = ":shared"
            output = "compose"
            sourceSet = "commonMain"
            resourceRoot = "shared/build/generated/sentiary"
            localDirectories = listOf("shared/additionalResources")
        }
    }
}
```

Create `sentiary.toml` at the build root with `sentiary-cli init`, or use:

```toml
config_version = 1
[projects.mobile]
id = "existing-project-id"
languages = ["en", "cs"]

[outputs.android]
project = "mobile"
format = "android"
path = "app/build/generated/sentiary/{locale}/strings.xml"
locale_map = { en = "values", cs = "values-cs" }

[outputs.compose]
project = "mobile"
format = "compose"
path = "shared/build/generated/sentiary/{locale}/strings.xml"
locale_map = { en = "values", cs = "values-cs" }
```

Set `SENTIARY_USER_API_KEY` in the environment or put `user_api_key` in an ignored `sentiary.local.toml`.
The shared TOML may hold a project `api_key` when explicitly intended. Credentials never belong in Gradle DSL.
All DSL resource roots and additional directories are relative to the build root; TOML paths are relative
to the config directory. Android/Compose bindings require matching per-language output formats and destinations
strictly inside `resourceRoot`.

The CLI discovers config from the build root and its parents. Technical overrides are Gradle properties:

| Property | Default |
| --- | --- |
| `sentiary.config` | CLI config discovery from the build root |
| `sentiary.cliVersion` | `v2.0.0` |
| `sentiary.cliExecutable` | Verified binary in Gradle user-home `caches/sentiary/cli/<version>/<platform>` |

An override executable must support integration protocol 1. It can be relative to the build root or absolute.
Automatic installation supports macOS, Linux, and Windows on AMD64 and ARM64, verifies release SHA-256,
locks across processes, and atomically installs the executable. It never changes PATH. A missing or corrupt
managed binary fails clearly offline. `SENTIARY_BASE_URL` selects another API environment without changing TOML.

## Build behavior

The plugin performs no CLI execution, TOML read, or networking during configuration. Isolated callbacks
configure only the current project. Resource tasks depend by task path on a single `:sentiaryPull`, which
selects all bound outputs together, including bindings in modules not consumed by this invocation. Unbound
outputs are excluded. Running `sentiaryPull` with no bindings pulls all outputs from the config.

Each relevant build performs online validation, including Configuration Cache reuse. Gradle's up-to-date
checks and Build Cache cannot skip the check. Errors fail the build; there is no automatic offline fallback.
`--offline` is forwarded to the CLI, which can restore one complete cached download set after generated files
are deleted. The required compatible CLI binary must already be available offline.

Android app/library bindings preserve `main`, build-type, flavor, and variant source-set overlay priorities.
Use a separate binding/output for each layer needed. Each source set stages its existing local resource
directories together with the selected generated files; direct resource tasks also depend on staging.
Android test components and direct XML resources for the new KMP Android library plugin are deferred.

Compose stages `src/<sourceSet>/composeResources`, optional `localDirectories`, and generated resources into
one `customDirectory` registration. KMP Compose is supported. A separate user `customDirectory` registration
for that source set is unsupported. Distinct physical files with the same relative path (including case-only
collisions) fail. The same physical input is copied once. Avoid putting generated outputs in a staging directory.
AGP and Compose are optional integrations, accessed only through public signatures; neither is a runtime dependency.

## Verified consumer versions

The target matrix is Gradle **9.1.0**, JDK **21**, AGP **9.0.0**, Kotlin **2.3.20**, Compose **1.10.3**.
No broader compatibility is promised. AGP 9 has built-in Kotlin; declare Kotlin 2.3.20 in the root plugin
classpath when needed to align it with this matrix.

For Isolated Projects with this Compose version, disable its automatic Hot Reload plugin:

```properties
org.jetbrains.compose.hot.reload.disable=true
```

TestKit also sets `kotlin.internal.collectFUSMetrics=false` to avoid a Kotlin statistics build-service
classloader conflict. The Sentiary tasks do not access another project's mutable state.

## Migrate from 1.x

1. Remove all project-level `com.sentiary.gradle` applications and the old `sentiary` DSL.
2. Move project IDs, keys, languages, formats, path policies, and language overrides into `sentiary.toml`.
   Use `language_aliases` for whole-file language overrides and `locale_map` for destination qualifiers.
3. Apply 2.0 once in settings and add the appropriate source-set bindings.
4. Replace explicit `sentiaryUpdateLocalizations`/`sentiaryUpdateProjectInfo` dependencies with `:sentiaryPull`
   where automatic resource integration does not apply.
5. Run online once, then verify `--offline` and Configuration Cache reuse in your build.

There is no transitional old DSL or embedded HTTP downloader. Existing standalone CLI `export` and `import`
commands remain available. Manual changes to CLI-owned files still require explicit CLI `--force` recovery.

## Validate and release

```sh
./gradlew :gradle-plugin:test :gradle-plugin:ktlintCheck :gradle-plugin:validatePlugins
# Also exercise the actual CLI against the local fixture server:
SENTIARY_TEST_CLI=/absolute/path/to/sentiary-cli ./gradlew :gradle-plugin:test
```

The TestKit fixtures use Python 3 as a small protocol test double; the actual CLI fixture uses the supplied binary.
Android fixtures require an Android SDK (`ANDROID_HOME`, or the standard macOS SDK location) with platform 36.
They run resource tasks for app/library and KMP Compose, parallel modules, Configuration Cache reuse, and collisions.

Before publishing 2.0, complete the backend cache rollout and migration through 6, verify authenticated
`export/state` protocol 1, then publish CLI `v2.0.0` with all six native binaries and `SHA256SUMS`.
Verify downloaded release assets before publishing the plugin. Local tests do not deploy or migrate a backend.

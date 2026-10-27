package com.sentiary

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentiaryPluginTest {
    @TempDir lateinit var directory: File

    private fun write(
        name: String,
        text: String,
    ): File =
        directory.resolve(name).apply {
            parentFile.mkdirs()
            writeText(text.trimIndent())
        }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(directory)
            .withPluginClasspath()
            .withArguments(
                *args,
                "--stacktrace",
                "--configuration-cache",
                "-Dorg.gradle.unsafe.isolated-projects=true",
                "--parallel",
                "--max-workers=2",
            )

    private fun cli(): File =
        write(
            "cli.py",
            """
        #!/usr/bin/env python3
        import json,sys,pathlib,hashlib
        root=pathlib.Path(__file__).parent
        if sys.argv[1]=='version':
            print(json.dumps({'version':'test','integrationProtocols':[1]}))
        else:
            with (root/'pulls.log').open('a') as f: f.write('offline\n' if '--offline' in sys.argv else 'online\n')
            files=[]
            if '--integration-request' in sys.argv:
                request=json.loads(pathlib.Path(sys.argv[sys.argv.index('--integration-request')+1]).read_text())
                assert request['protocolVersion']==1
                for binding in request['bindings']:
                    path=pathlib.Path(binding['resourceRoot'])/'values'/'strings.xml'
                    path.parent.mkdir(parents=True,exist_ok=True)
                    content=('<resources><string name="remote">'+binding["output"]+'</string></resources>').encode()
                    path.write_bytes(content)
                    files.append({'output':binding['output'],'path':str(path),'sha256':hashlib.sha256(content).hexdigest()})
            print(json.dumps({'protocolVersion':1,'files':files}))
    """,
        ).apply { setExecutable(true) }

    @Test fun `generic task needs no frameworks and runs again with configuration reuse`() {
        val cli = cli()
        write(
            "settings.gradle.kts",
            """
            plugins { id("com.sentiary.gradle") }
            rootProject.name = "generic"
        """,
        )
        write("build.gradle.kts", "")
        write(
            "gradle.properties",
            "sentiary.cliExecutable=${cli.absolutePath}\norg.jetbrains.compose.hot.reload.disable=true\nkotlin.internal.collectFUSMetrics=false",
        )
        // Configuration alone must not execute the CLI or read a missing config.
        runner("help").build()
        assertTrue(!directory.resolve("pulls.log").exists())
        runner("sentiaryPull").build()
        val repeated = runner("sentiaryPull").build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(listOf("online", "online"), directory.resolve("pulls.log").readLines())
        runner("sentiaryPull", "--offline").build()
        assertEquals("offline", directory.resolve("pulls.log").readLines().last())
    }

    @Test fun `incompatible override fails clearly`() {
        val cli = write(
            "incompatible.sh",
            """
            #!/bin/sh
            echo '{"version":"old","integrationProtocols":[2]}'
        """,
        ).apply { setExecutable(true) }
        write("settings.gradle.kts", "plugins { id(\"com.sentiary.gradle\") }")
        write(
            "gradle.properties",
            "sentiary.cliExecutable=${cli.absolutePath}\norg.jetbrains.compose.hot.reload.disable=true\nkotlin.internal.collectFUSMetrics=false",
        )
        val result = runner("sentiaryPull").buildAndFail()
        assertTrue(result.output.contains("does not support integration protocol 1"), result.output)
    }

    @Test fun `compose KMP stages conventional and explicit resources with one shared pull`() {
        composeFixture()
        val result = runner(":one:prepareComposeResourcesTaskForCommonMain", ":two:prepareComposeResourcesTaskForCommonMain").build()
        assertTrue(result.output.contains(":sentiaryPull"), result.output)
        val repeated = runner(":one:prepareComposeResourcesTaskForCommonMain", ":two:prepareComposeResourcesTaskForCommonMain").build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(listOf("online", "online"), directory.resolve("pulls.log").readLines())
        val staged = directory.resolve("one/build/sentiary/staging/compose/commonMain")
        assertTrue(staged.resolve("values/strings.xml").isFile)
        assertEquals("local", staged.resolve("files/local.txt").readText())
        assertEquals("extra", staged.resolve("files/extra.txt").readText())
        directory.resolve("generated").deleteRecursively()
        runner(":one:prepareComposeResourcesTaskForCommonMain", ":two:prepareComposeResourcesTaskForCommonMain", "--offline").build()
        assertTrue(directory.resolve("generated/one/values/strings.xml").isFile)
        assertEquals("offline", directory.resolve("pulls.log").readLines().last())
    }

    @Test fun `compose rejects distinct physical inputs at the same relative path`() {
        composeFixture()
        write("extra/one/values/strings.xml", "<resources/>")
        val result = runner(":one:prepareComposeResourcesTaskForCommonMain").buildAndFail()
        assertTrue(result.output.contains("Resource collision at values/strings.xml"), result.output)
    }

    @Test fun `android app and library preserve resource layer priority with direct tasks`() {
        androidFixture()
        runner(":app:mergeFreeDebugResources", ":library:packageDebugResources").build()
        val repeated = runner(":app:mergeFreeDebugResources", ":library:packageDebugResources").build()
        assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
        assertEquals(listOf("online", "online"), directory.resolve("pulls.log").readLines())
        for (layer in listOf("main", "debug", "free", "freeDebug")) {
            val staged = directory.resolve("app/build/sentiary/staging/android/$layer/values/strings.xml")
            assertTrue(staged.readText().contains("app$layer"))
        }
        val merged = directory
            .resolve("app/build/intermediates")
            .walkTopDown()
            .filter { it.isFile && it.name == "values.xml" && it.path.contains("merged.dir") }
            .toList()
        assertTrue(merged.any { it.readText().contains(">appfreeDebug</string>") }, "Variant source set must win: $merged")
        assertEquals(
            "<resources><string name=\"local\">Local</string></resources>",
            directory.resolve("app/build/sentiary/staging/android/main/values/local.xml").readText(),
        )
    }

    @Test fun `android local collision fails without replacing previous staging`() {
        androidFixture()
        write("app/src/main/res/values/strings.xml", "<resources/>")
        val result = runner(":app:mergeFreeDebugResources").buildAndFail()
        assertTrue(result.output.contains("Resource collision at values/strings.xml"), result.output)
    }

    @Test
    fun `unknown Android production source set fails clearly`() {
        androidFixture()
        val settings = directory.resolve("settings.gradle.kts")
        settings.writeText(settings.readText().replace("sourceSet=\"main\"", "sourceSet=\"missing\""))
        val result = runner("help").buildAndFail()
        assertTrue(result.output.contains("does not belong to an enabled production variant"), result.output)
    }

    @Test
    fun `unknown Compose source set fails clearly`() {
        composeFixture()
        val settings = directory.resolve("settings.gradle.kts")
        settings.writeText(settings.readText().replace("sourceSet=\"commonMain\"", "sourceSet=\"missing\""))
        val result = runner("help").buildAndFail()
        assertTrue(result.output.contains("Unknown Compose source set missing"), result.output)
    }

    private fun androidFixture() {
        val cli = cli()
        write(
            "local.properties",
            "sdk.dir=" +
                (
                    System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                        ?: (System.getProperty("user.home") + "/Library/Android/sdk")
                ),
        )
        write(
            "gradle.properties",
            "sentiary.cliExecutable=${cli.absolutePath}\nandroid.useAndroidX=true\nkotlin.internal.collectFUSMetrics=false",
        )
        val bindings = listOf("main", "debug", "free", "freeDebug").joinToString("\n") { layer ->
            "create(\"$layer\") { modulePath=\":app\"; output=\"app$layer\"; sourceSet=\"$layer\"; resourceRoot=\"generated/app$layer\" }"
        }
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            plugins { id("com.sentiary.gradle") }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "android-test"
            include(":app", ":library")
            sentiary { android {
                $bindings
                create("library") { modulePath=":library"; output="library"; sourceSet="main"; resourceRoot="generated/library" }
            } }
        """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "9.0.0" apply false
                id("com.android.library") version "9.0.0" apply false
                id("org.jetbrains.kotlin.android") version "2.3.20" apply false
            }
        """,
        )
        write(
            "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android {
                namespace="test.app"; compileSdk=36
                defaultConfig { minSdk=23; targetSdk=36 }
                flavorDimensions += "tier"
                productFlavors { create("free") { dimension="tier" }; create("paid") { dimension="tier" } }
            }
        """,
        )
        write(
            "library/build.gradle.kts",
            """
            plugins { id("com.android.library") }
            android { namespace="test.library"; compileSdk=36; defaultConfig { minSdk=23 } }
        """,
        )
        write(
            "app/src/main/AndroidManifest.xml",
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>",
        )
        write("library/src/main/AndroidManifest.xml", "<manifest/>")
        write("app/src/main/res/values/local.xml", "<resources><string name=\"local\">Local</string></resources>")
    }

    @Test fun `real CLI verifies online again after reuse and restores resources offline`() {
        val executable = File(System.getProperty("sentiary.test.cli", ""))
        org.junit.jupiter.api.Assumptions
            .assumeTrue(executable.isFile, "Set SENTIARY_TEST_CLI to a protocol 1 CLI binary")
        composeFixture(executable)
        write(
            "sentiary.toml",
            """
            config_version = 1
            [projects.app]
            id = "fixture"
            api_key = "fixture-key"
            languages = ["en"]
            [outputs.one]
            project = "app"
            format = "compose"
            path = "generated/one/values/strings.xml"
            [outputs.two]
            project = "app"
            format = "compose"
            path = "generated/two/values/strings.xml"
            [outputs.unbound]
            project = "app"
            format = "json"
            path = "unbound/{language}.json"
        """,
        )
        val stateRequests = java.util.concurrent.atomic
            .AtomicInteger()
        val exportRequests = java.util.concurrent.atomic
            .AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer
            .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("Bearer fixture-key", exchange.requestHeaders.getFirst("Authorization"))
            if (exchange.requestURI.path.endsWith("/state")) {
                stateRequests.incrementAndGet()
                val bytes = """
                    {"protocolVersion":1,"projectGeneration":"fixture","dataRevision":"1","exporterVersion":"v1","languages":["en"]}
                """.trimIndent()
                    .toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exportRequests.incrementAndGet()
                exchange.responseHeaders.add("ETag", "W/\"fixture\"")
                exchange.responseHeaders.add("X-Sentiary-Project-Generation", "fixture")
                exchange.responseHeaders.add("X-Sentiary-Data-Revision", "1")
                exchange.responseHeaders.add("X-Sentiary-Exporter-Version", "v1")
                if (exchange.requestHeaders.getFirst("If-None-Match") != null) {
                    exchange.sendResponseHeaders(304, -1)
                    exchange.close()
                } else {
                    val bytes = "<resources><string name=\"remote\">Actual CLI</string></resources>".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        val environment =
            System.getenv() +
                mapOf(
                    "SENTIARY_USER_API_KEY" to "",
                    "SENTIARY_BASE_URL" to "http://127.0.0.1:${server.address.port}",
                    "SENTIARY_CACHE_DIR" to directory.resolve("cache").path,
                )
        val tasks = arrayOf(":one:prepareComposeResourcesTaskForCommonMain", ":two:prepareComposeResourcesTaskForCommonMain")
        try {
            val first = runner(*tasks).withEnvironment(environment).build()
            assertEquals(1, first.tasks.count { it.path == ":sentiaryPull" })
            assertEquals(1, stateRequests.get())
            assertEquals(1, exportRequests.get())
            val repeated = runner(*tasks).withEnvironment(environment).build()
            assertTrue(repeated.output.contains("Reusing configuration cache"), repeated.output)
            assertEquals(2, stateRequests.get())
            assertEquals(2, exportRequests.get())
            assertTrue(!directory.resolve("unbound").exists())
            directory.resolve("generated").deleteRecursively()
            server.stop(0)
            runner(*tasks, "--offline").withEnvironment(environment).build()
            assertTrue(directory.resolve("generated/one/values/strings.xml").readText().contains("Actual CLI"))
            assertTrue(directory.resolve("generated/two/values/strings.xml").isFile)
        } finally {
            server.stop(0)
        }
    }

    private fun composeFixture(cli: File = cli()) {
        write(
            "gradle.properties",
            "sentiary.cliExecutable=${cli.absolutePath}\norg.jetbrains.compose.hot.reload.disable=true\nkotlin.internal.collectFUSMetrics=false",
        )
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
            plugins { id("com.sentiary.gradle") }
            rootProject.name = "compose-test"
            include(":one", ":two")
            sentiary {
                compose {
                    create("one") { modulePath=":one"; output="one"; sourceSet="commonMain"; resourceRoot="generated/one"; localDirectories=listOf("extra/one") }
                    create("two") { modulePath=":two"; output="two"; sourceSet="commonMain"; resourceRoot="generated/two" }
                }
            }
        """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.3.20" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
                id("org.jetbrains.compose") version "1.10.3" apply false
            }
        """,
        )
        for (module in listOf("one", "two")) {
            write(
                "$module/build.gradle.kts",
                """
                plugins { kotlin("multiplatform"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
                repositories { mavenCentral(); google() }
                kotlin { jvm() }
            """,
            )
        }
        write("one/src/commonMain/composeResources/files/local.txt", "local")
        write("extra/one/files/extra.txt", "extra")
    }
}

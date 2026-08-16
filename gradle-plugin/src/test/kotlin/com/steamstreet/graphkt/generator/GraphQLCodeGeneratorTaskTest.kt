package com.steamstreet.graphkt.generator

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLCodeGeneratorTaskTest {
    @Test
    fun `tracks schema inputs and reuses cached output`(@TempDir projectDir: File) {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"graphkt-test\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.steamstreet.graphkt")
            }

            GraphQL {
                schema = file("schema.graphql").absolutePath
                basePackage = "example.generated"
                generateClient = true
                generateServer = false
            }
            """.trimIndent(),
        )
        File(projectDir, "schema.graphql").writeText(
            """
            type Query {
                viewer: User
            }
            """.trimIndent(),
        )
        val userSchema = File(projectDir, "user.graphql").apply {
            writeText("type User { id: ID! }")
        }
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(File(projectDir, "test-kit"))
            .withPluginClasspath()
            .withArguments("generateGraphQLCode", "--build-cache", "--stacktrace")

        val first = runner.build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateGraphQLCode")?.outcome)

        val second = runner.build()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateGraphQLCode")?.outcome)

        userSchema.writeText("type User { id: ID!, name: String! }")
        val afterSchemaChange = runner.build()
        assertEquals(TaskOutcome.SUCCESS, afterSchemaChange.task(":generateGraphQLCode")?.outcome)

        val generated = File(projectDir, "build/graphql/generated/example/generated/client/query.kt")
        assertTrue(generated.readText().contains("public val name: Unit"))

        File(projectDir, "build").deleteRecursively()
        val fromCache = runner.build()
        assertEquals(TaskOutcome.FROM_CACHE, fromCache.task(":generateGraphQLCode")?.outcome)
        assertTrue(generated.isFile)
    }

    @Test
    fun `wires generated sources into Kotlin Multiplatform commonMain`(@TempDir projectDir: File) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }

            rootProject.name = "graphkt-kmp-test"
            """.trimIndent(),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.3.0"
                id("com.steamstreet.graphkt")
            }

            kotlin {
                jvm()
                sourceSets {
                    commonMain.dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0-RC")
                    }
                }
            }

            graphKt {
                schemaFiles.from(file("src/commonMain/graphql"))
                packageName.set("example.generated")
                client {
                    enabled.set(false)
                }
                server {
                    enabled.set(false)
                }
            }
            """.trimIndent(),
        )
        File(projectDir, "src/commonMain/graphql/schema.graphql").apply {
            parentFile.mkdirs()
            writeText("type Query { greeting: String! }")
        }
        File(projectDir, "src/commonMain/kotlin/example/generated/Usage.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package example.generated

                fun generatedSourceIsAvailable() = json
                """.trimIndent(),
            )
        }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(File(projectDir, "test-kit"))
            .withPluginClasspath()
            .withArguments("compileKotlinJvm", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateGraphQLCode")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlinJvm")?.outcome)
        assertTrue(File(projectDir, "build/graphql/generated/example/generated/common.kt").isFile)
    }

    @Test
    fun `removes stale server output when the feature is disabled`(@TempDir projectDir: File) {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"graphkt-feature-test\"")
        val buildFile = File(projectDir, "build.gradle.kts")
        fun configureServer(enabled: Boolean) {
            buildFile.writeText(
                """
                plugins {
                    id("com.steamstreet.graphkt")
                }

                graphKt {
                    schemaFiles.from(file("schema.graphql"))
                    packageName.set("example.generated")
                    client {
                        this.enabled.set(false)
                    }
                    server {
                        this.enabled.set($enabled)
                    }
                }
                """.trimIndent(),
            )
        }
        File(projectDir, "schema.graphql").writeText("type Query { greeting: String! }")
        configureServer(enabled = true)

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(File(projectDir, "test-kit"))
            .withPluginClasspath()
            .withArguments("generateGraphQLCode", "--stacktrace")

        val generated = runner.build()
        assertEquals(TaskOutcome.SUCCESS, generated.task(":generateGraphQLCode")?.outcome)
        val serverOutput = File(projectDir, "build/graphql/server/generated")
        assertTrue(serverOutput.walkTopDown().any { it.isFile && it.extension == "kt" })

        configureServer(enabled = false)
        val disabled = runner.build()
        assertEquals(TaskOutcome.SUCCESS, disabled.task(":generateGraphQLCode")?.outcome)
        assertFalse(serverOutput.walkTopDown().any { it.isFile })
    }
}

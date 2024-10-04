@file:Suppress("UnstableApiUsage")

rootProject.name = "graphkt"

class Group(
    val group: String,
    var version: String,
    val artifactPrefix: String = "",
    val aliasPrefix: String = artifactPrefix,
    val builder: VersionCatalogBuilder
) {
    fun artifact(alias: String, id: String) {
        builder.library("${aliasPrefix}$alias", group, "${artifactPrefix}$id").version(version)
    }

    fun artifact(id: String) = artifact(id, id)
}

fun VersionCatalogBuilder.group(
    groupId: String, version: String, artifactPrefix: String = "",
    aliasPrefix: String = artifactPrefix, items: Group.() -> Unit
) {
    val group = Group(groupId, version, artifactPrefix, aliasPrefix, this)
    group.items()
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }

    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }

    versionCatalogs {
        create("libs") {
            group("org.jetbrains.kotlinx", "1.9.0", "kotlinx-coroutines-") {
                artifact("core")
                artifact("test")
            }

            group(
                "org.jetbrains.kotlinx", "1.7.3", "kotlinx-serialization-",
                "kotlin-serialization-"
            ) {
                artifact("core")
                artifact("json")
            }

            group("org.jetbrains.kotlinx", "2.0.20", "kotlin") {
                artifact("test")
                artifact("test-annotations-common")
                artifact("test-junit5")
            }

            group("io.ktor", "2.3.12", "ktor-") {
                artifact("client-core")
                artifact("server-core")
            }

            library("graphql", "com.graphql-java:graphql-java:22.1")
            library("aws-lambda-events", "com.amazonaws:aws-lambda-java-events:3.8.0")

            library("kotlin-poet", "com.squareup", "kotlinpoet").version("1.18.1")
        }
    }
}

include(":gradle-plugin")
include(":common-runtime")
include(":client")
include(":client-ktor")
include(":client-fetch")
include(":server")
include(":server-ktor")
include(":server-lambda")
include(":code-generator")
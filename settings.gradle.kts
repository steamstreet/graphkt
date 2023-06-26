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
            group("org.jetbrains.kotlinx", "1.6.4", "kotlinx-coroutines-") {
                artifact("core")
                artifact("test")
            }

            group(
                "org.jetbrains.kotlinx", "1.5.0", "kotlinx-serialization-",
                "kotlin-serialization-"
            ) {
                artifact("core")
                artifact("json")
            }

            group("org.jetbrains.kotlinx", "1.8.21", "kotlin") {
                artifact("test")
                artifact("test-annotations-common")
                artifact("test-junit5")
            }

            group("io.ktor", "2.3.1", "ktor-") {
                artifact("client-core")
                artifact("server-core")
            }

            library("graphql", "com.graphql-java:graphql-java:20.3")
            library("aws-lambda-events", "com.amazonaws:aws-lambda-java-events:3.8.0")

            library("kotlin-poet", "com.squareup", "kotlinpoet").version("1.6.0")
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
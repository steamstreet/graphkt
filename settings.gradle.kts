@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            val kotlinVersion = version("kotlin", "1.8.21")
            val kotlinSerializationVersion = version("kotlin-serialization", "1.5.0")
            val coroutinesVersion = version("kotlin-coroutines", "1.6.4")
            val awsVersion = version("aws", "2.20.32")
            val ktorVersion = version("ktor", "2.3.0")

            library(
                "kotlin-coroutines",
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-core"
            ).versionRef(coroutinesVersion)

            library(
                "kotlin-coroutines-test",
                "org.jetbrains.kotlinx",
                "kotlinx-coroutines-test"
            ).versionRef(coroutinesVersion)

            fun aws(artifact: String) {
                library("aws-${artifact}", "software.amazon.awssdk", artifact).versionRef(awsVersion)
            }
            library("kotlin-test", "org.jetbrains.kotlinx", "kotlin-test").versionRef(kotlinVersion)
            library(
                "kotlin-test-annotations-common",
                "org.jetbrains.kotlinx",
                "kotlin-test-annotations-common"
            ).versionRef(kotlinVersion)
            library("kotlin-test-junit5", "org.jetbrains.kotlinx", "kotlin-test-junit5").versionRef(kotlinVersion)
            library("kotlin-serialization-core", "org.jetbrains.kotlinx", "kotlinx-serialization-core").versionRef(
                kotlinSerializationVersion
            )
            library("kotlin-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef(
                kotlinSerializationVersion
            )

            library("ktor-client-core", "io.ktor", "ktor-client-core").versionRef(ktorVersion)

            library("graphql", "com.graphql-java:graphql-java:2019-11-07T04-06-09-70d9412")
            library("aws-lambda-events", "com.amazonaws:aws-lambda-java-events:3.8.0")

            library("ktor-server-core", "io.ktor", "ktor-server-core").versionRef(ktorVersion)
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
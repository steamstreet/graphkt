@file:Suppress("UnstableApiUsage")

rootProject.name = "graphkt"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
}

include(":gradle-plugin")
include(":common-runtime")
include(":client")
include(":client-ktor")
include(":client-fetch")
include(":client-direct")
include(":server")
include(":server-ktor")
include(":server-lambda")
include(":code-generator")

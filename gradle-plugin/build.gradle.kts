@file:Suppress("UnstableApiUsage")

plugins {
    id("graphkt.jvm-conventions")
    id("java-gradle-plugin")
}

description = "Gradle plugin that generates GraphKt client and server code from a GraphQL schema."

// Publishing comes from the JVM conventions. The implementation publishes as
// `com.steamstreet.graphkt:gradle-plugin`, and `java-gradle-plugin` adds the marker
// `com.steamstreet.graphkt:com.steamstreet.graphkt.gradle.plugin`, whose coordinates are derived
// from the plugin id rather than from this project. That marker already exists on Central for 2.x,
// where it pointed at `com.steamstreet:graphkt-gradle-plugin`; `plugins { id(...) }` resolution
// depends on it staying exactly as the id spells it.

dependencies {
    api(libs.graphql)
    api(libs.kotlin.poet)
    api(project(":code-generator"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
}

gradlePlugin {
    plugins {
        create("graphkt") {
            id = "com.steamstreet.graphkt"
            implementationClass = "com.steamstreet.graphkt.generator.GraphQLGeneratorPlugin"
            displayName = "GraphKt Plugin"
            description = "Plugin for generating GraphQL code from a GraphQL schema"
        }
    }
}

